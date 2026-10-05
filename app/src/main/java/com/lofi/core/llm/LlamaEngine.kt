package com.lofi.core.llm

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Kotlin facade over the Gemma JNI. All native calls except stop()/isLoaded() run on one
 * dedicated thread, so load/generate/release are strictly serialized. After release(),
 * every call fails with a clear error instead of touching freed native memory.
 */
class LlamaEngine {
    private val native = LlamaNative()
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lofi-llama") }.asCoroutineDispatcher()

    val isLoaded: Boolean get() = libraryReady && native.nativeIsLoaded()

    suspend fun loadModel(path: String, nCtx: Int = 2048) = withContext(dispatcher) {
        ensureLibrary()
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
        try {
            native.nativeLoad(path, nCtx, threads)
        } catch (e: OutOfMemoryError) {
            throw EngineException("Not enough memory to load the model. Close other apps and retry.", e)
        } catch (e: IllegalStateException) {
            throw EngineException(e.message ?: "Model loading failed.", e)
        }
    }

    /** Streams decoded text through [onText]; returns the number of generated tokens. */
    suspend fun generate(prompt: String, maxTokens: Int = 512, onText: (String) -> Unit): Int =
        withContext(dispatcher) {
            if (!native.nativeIsLoaded()) throw EngineException("The model is not loaded.")
            try {
                native.nativeGenerate(prompt.toByteArray(Charsets.UTF_8), maxTokens) { bytes ->
                    onText(String(bytes, Charsets.UTF_8))
                }
            } catch (e: IllegalArgumentException) {
                throw EngineException(e.message ?: "Invalid prompt.", e)
            } catch (e: IllegalStateException) {
                throw EngineException(e.message ?: "Generation failed.", e)
            }
        }

    /** Safe to call from any thread, while generate() is running. */
    fun stopGeneration() {
        if (libraryReady) native.nativeStop()
    }

    suspend fun release() = withContext(dispatcher) {
        if (libraryReady) native.nativeRelease()
    }

    companion object {
        @Volatile private var libraryReady = false
        @Volatile private var libraryError: String? = null

        @Synchronized
        private fun ensureLibrary() {
            if (libraryReady) return
            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                throw EngineException("This device is not ARM64 (arm64-v8a). LoFi-4A cannot run here.")
            }
            try {
                System.loadLibrary("llama_jni")
                libraryReady = true
            } catch (e: UnsatisfiedLinkError) {
                libraryError = e.message
                throw EngineException("Native library libllama_jni.so failed to load: ${e.message}", e)
            }
        }
    }
}
