# LoFi-4A (Phase 1 + 2 skeleton)

Offline, on-device Android assistant. Phase 1: Gemma 3 1B + llama.cpp + JNI + Compose chat.
Phase 2: model downloader (resume, retry, SHA-256, atomic rename) + model manager.
Phases 3-5 (LFM2-VL/mmproj/libmtmd, Whisper, RAM tuning) are intentionally not stubbed.

## Before the first build

1. **Pin llama.cpp** (not done in this skeleton; pick a release tag you have tested):
   ```
   git init && git submodule add https://github.com/ggml-org/llama.cpp third_party/llama.cpp
   cd third_party/llama.cpp && git checkout <tag> && cd ../..
   git add . && git commit -m "Pin llama.cpp"
   ```
   `llama_jni.cpp` uses these llama.h functions: `llama_model_load_from_file`, `llama_init_from_model`,
   `llama_model_get_vocab`, `llama_tokenize`, `llama_memory_clear` / `llama_get_memory`,
   `llama_batch_get_one`, `llama_decode`, `llama_sampler_*`, `llama_vocab_is_eog`,
   `llama_token_to_piece`, `llama_model_free`, `llama_free`. If your pinned revision differs, the
   compiler will point at the exact line; fix against that revision's `include/llama.h`.
2. **Fill the manifest** in `ModelManifest.kt`: confirm the URL, then set `sha256` and `sizeBytes`
   from a copy you downloaded and hashed yourself. Until then the app shows a clear error and
   refuses to download.
3. Push to GitHub: the `Build APK` workflow uploads `LoFi-4A-debug-apk`.
   Locally: open in Android Studio (it will create the Gradle wrapper) and run.

## Layout

- `app/src/main/cpp/` JNI + CMake (`libllama_jni.so`, llama.cpp built static inside it)
- `llm/` JNI declarations + `LlamaEngine` (single native thread, lock-free stop)
- `models/` manifest, downloader, store, `ModelManager`
- `chat/` persona, Gemma prompt format, `ChatViewModel`
- `ui/` Compose chat screen

## Known limits of this skeleton

- Not compiled or run here (no Android SDK/NDK in this workspace): expect to fix small build errors.
- The whole prompt is re-evaluated each turn (no KV reuse yet); context is 2048 tokens.
- Image button is disabled until Phase 3.
