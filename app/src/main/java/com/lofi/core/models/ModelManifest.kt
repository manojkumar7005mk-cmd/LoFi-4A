package com.lofi.core.models

/**
 * THE single place for model URLs, sizes, versions and SHA-256 hashes.
 * Nothing else in the app contains a model URL.
 *
 * A spec is only downloadable once [ModelSpec.isConfigured] is true, i.e. it has a real
 * SHA-256 and byte size. The app never downloads an unverifiable file.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sha256: String,   // 64 lowercase hex chars
    val sizeBytes: Long,  // exact size of the final file
    val version: String,
) {
    val isConfigured: Boolean
        get() = Regex("^[0-9a-f]{64}$").matches(sha256) && sizeBytes > 0
}

object ModelManifest {
    /** Downloads are only allowed from these hosts (https only). */
    val allowedHosts = setOf("huggingface.co")

    // Chat model (text). Already working.
    val GEMMA = ModelSpec(
        id = "gemma-3-1b-it",
        displayName = "Gemma 3 1B Instruct (Q4_K_M)",
        fileName = "gemma-3-1b-it-Q4_K_M.gguf",
        url = "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
        sha256 = "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
        sizeBytes = 806058240L,
        version = "1",
    )

    // Image analysis (Phase 3): the vision model + its projector. Both files are needed together.
    val LFM_VL = ModelSpec(
        id = "lfm2.5-vl-450m",
        displayName = "LFM2.5-VL 450M (Q4_K_M)",
        fileName = "LFM2.5-VL-450M-Q4_K_M.gguf",
        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/resolve/main/LFM2.5-VL-450M-Q4_K_M.gguf",
        sha256 = "1093f1331319199bbcacdbd7ecc9aa6e5678db6b55073948d0f508be90c8ab68",
        sizeBytes = 229313568L,
        version = "1",
    )

    val LFM_VL_MMPROJ = ModelSpec(
        id = "lfm2.5-vl-450m-mmproj",
        displayName = "LFM2.5-VL 450M vision projector (Q8_0)",
        fileName = "mmproj-LFM2.5-VL-450m-Q8_0.gguf",
        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/resolve/main/mmproj-LFM2.5-VL-450m-Q8_0.gguf",
        sha256 = "ebfc428baa37efad8bae93864f914b2634a09009f91ad59f974fe1a1565d8561",
        sizeBytes = 102815168L,
        version = "1",
    )

    // Voice to text (Phase 4). Pinned to an exact commit so the file can never change under us.
    val WHISPER_BASE = ModelSpec(
        id = "whisper-base",
        displayName = "Whisper Base (multilingual)",
        fileName = "ggml-base.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/98aa99a0a9db05ae2342309f5096248665f7cba3/ggml-base.bin",
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        sizeBytes = 147951465L,
        version = "1",
    )

    // The app downloads only these today.
    val all: List<ModelSpec> = listOf(GEMMA)

    // Ready for Phase 3 and 4. Not downloaded yet, because the code to use them isn't wired in.
    val upcoming: List<ModelSpec> = listOf(LFM_VL, LFM_VL_MMPROJ, WHISPER_BASE)
}
