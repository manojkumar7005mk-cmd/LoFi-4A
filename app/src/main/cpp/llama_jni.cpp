// Gemma text engine: thin JNI wrapper over llama.cpp.
//
// Lifecycle rules (Kotlin must never touch a released context):
//   * One global engine guarded by g_mutex. load/generate/release all take the lock.
//   * stopGeneration() only flips an atomic flag (no lock), so it works mid-generation.
//   * release() raises the stop flag first, then waits for the lock; a running generate()
//     exits at its next token, so release can never free memory that generate() is using.
//
// Text crossing JNI is passed as UTF-8 byte[] rather than jstring: NewStringUTF expects
// *modified* UTF-8 and misbehaves on 4-byte sequences (emoji). Kotlin encodes/decodes.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LoFi-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr; // owned by model
    int n_ctx = 0;
    int n_batch = 0;
};

std::mutex g_mutex;
Engine g_engine;
std::atomic<bool> g_stop{false};
std::atomic<bool> g_loaded{false}; // mirrors g_engine.ctx != nullptr
bool g_backend_ready = false;

void throw_java(JNIEnv *env, const char *cls, const std::string &msg) {
    jclass c = env->FindClass(cls);
    if (c) env->ThrowNew(c, msg.c_str());
}

void free_engine_locked() {
    if (g_engine.ctx) llama_free(g_engine.ctx);
    if (g_engine.model) llama_model_free(g_engine.model);
    g_engine = Engine{};
    g_loaded.store(false);
}

// Length of the longest prefix of `s` that does not end in the middle of a UTF-8 sequence.
// Token pieces can split a multi-byte character; we hold the tail back until it completes.
size_t complete_utf8_prefix(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= std::min<size_t>(3, n); ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // continuation byte, keep looking for the lead byte
        size_t need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        return (need > back) ? n - back : n;
    }
    return n;
}

std::string to_std_string(JNIEnv *env, jbyteArray arr) {
    jsize len = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(len), '\0');
    if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_lofi_core_llm_LlamaNative_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx,
                                              jint n_threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_engine.ctx) free_engine_locked(); // reload replaces the previous model

    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    std::string path_str(path);
    env->ReleaseStringUTFChars(jpath, path);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU only
    mp.use_mmap = true;    // weights are paged from disk: lowest resident RAM

    llama_model *model = llama_model_load_from_file(path_str.c_str(), mp);
    if (!model) {
        throw_java(env, "java/lang/IllegalStateException",
                   "Could not load the model file. It may be corrupt or not a supported GGUF.");
        return;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        throw_java(env, "java/lang/OutOfMemoryError",
                   "Could not allocate the inference context (not enough memory?).");
        return;
    }

    g_engine.model = model;
    g_engine.ctx = ctx;
    g_engine.vocab = llama_model_get_vocab(model);
    g_engine.n_ctx = static_cast<int>(llama_n_ctx(ctx));
    g_engine.n_batch = static_cast<int>(cp.n_batch);
    g_loaded.store(true);
    LOGI("model loaded, n_ctx=%d", g_engine.n_ctx);
}

// Returns the number of generated tokens. Streams UTF-8 bytes to sink.onBytes(byte[]).
JNIEXPORT jint JNICALL
Java_com_lofi_core_llm_LlamaNative_nativeGenerate(JNIEnv *env, jobject, jbyteArray jprompt,
                                                  jint max_tokens, jobject sink) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine.ctx) {
        throw_java(env, "java/lang/IllegalStateException", "No model is loaded.");
        return 0;
    }
    g_stop.store(false);

    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_bytes = env->GetMethodID(sink_cls, "onBytes", "([B)V");
    if (!on_bytes) return 0; // NoSuchMethodError already pending

    const std::string prompt = to_std_string(env, jprompt);
    const llama_vocab *vocab = g_engine.vocab;

    // Tokenize. add_special=true prepends BOS (Gemma requires it);
    // parse_special=true makes <start_of_turn>/<end_of_turn> real control tokens.
    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                   nullptr, 0, true, true);
    if (n_prompt <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt could not be tokenized.");
        return 0;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       n_prompt, true, true) < 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt could not be tokenized.");
        return 0;
    }
    if (n_prompt >= g_engine.n_ctx) {
        throw_java(env, "java/lang/IllegalArgumentException",
                   "The conversation is too long for the context window.");
        return 0;
    }
    const int budget = std::min<int>(max_tokens, g_engine.n_ctx - n_prompt);

    // Simple strategy for the skeleton: re-evaluate the whole prompt every turn.
    // (Reusing the KV cache across turns is a Phase 5 optimisation.)
    llama_memory_clear(llama_get_memory(g_engine.ctx), true);

    for (int i = 0; i < n_prompt; i += g_engine.n_batch) {
        if (g_stop.load()) return 0;
        int n = std::min(g_engine.n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(g_engine.ctx, batch) != 0) {
            throw_java(env, "java/lang/IllegalStateException", "Failed to evaluate the prompt.");
            return 0;
        }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending; // bytes not yet sent (possible partial UTF-8 tail)
    int generated = 0;
    bool failed = false;

    auto flush = [&](bool final_flush) {
        size_t n = final_flush ? pending.size() : complete_utf8_prefix(pending);
        if (n == 0) return;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n),
                                reinterpret_cast<const jbyte *>(pending.data()));
        env->CallVoidMethod(sink, on_bytes, arr);
        env->DeleteLocalRef(arr);
        pending.erase(0, n);
    };

    while (generated < budget && !g_stop.load()) {
        llama_token tok = llama_sampler_sample(smpl, g_engine.ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) pending.append(buf, static_cast<size_t>(len));
        ++generated;

        flush(false);
        if (env->ExceptionCheck()) break; // Kotlin sink threw; stop and propagate

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_engine.ctx, batch) != 0) {
            failed = true;
            break;
        }
    }
    if (!env->ExceptionCheck()) flush(true);
    llama_sampler_free(smpl);

    if (failed && !env->ExceptionCheck()) {
        throw_java(env, "java/lang/IllegalStateException", "Generation failed while decoding.");
    }
    return generated;
}

JNIEXPORT void JNICALL
Java_com_lofi_core_llm_LlamaNative_nativeStop(JNIEnv *, jobject) {
    g_stop.store(true); // intentionally lock-free
}

JNIEXPORT void JNICALL
Java_com_lofi_core_llm_LlamaNative_nativeRelease(JNIEnv *, jobject) {
    g_stop.store(true);                       // make a running generate() exit promptly
    std::lock_guard<std::mutex> lock(g_mutex); // ...then wait for it
    free_engine_locked();
    LOGI("model released");
}

JNIEXPORT jboolean JNICALL
Java_com_lofi_core_llm_LlamaNative_nativeIsLoaded(JNIEnv *, jobject) {
    // Lock-free on purpose: must not block the UI thread while generate() holds g_mutex.
    return g_loaded.load() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
