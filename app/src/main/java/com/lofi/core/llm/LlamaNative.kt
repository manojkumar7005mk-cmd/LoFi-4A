package com.lofi.core.llm

/** Receives raw UTF-8 bytes from native code (see llama_jni.cpp for why bytes, not String). */
fun interface TokenSink {
    fun onBytes(bytes: ByteArray)
}

/**
 * The ONLY class that declares JNI methods for Gemma. Keep it dumb: no logic here.
 * Names must match the Java_com_lofi_core_llm_LlamaNative_* symbols in llama_jni.cpp.
 */
internal class LlamaNative {
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int)
    external fun nativeGenerate(prompt: ByteArray, maxTokens: Int, sink: TokenSink): Int
    external fun nativeStop()
    external fun nativeRelease()
    external fun nativeIsLoaded(): Boolean
}
