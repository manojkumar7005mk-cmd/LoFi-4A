package com.lofi.core.models

/**
 * THE single place for model URLs, sizes, versions and SHA-256 hashes.
 * Nothing else in the app contains a model URL.
 *
 * A spec is only downloadable once [ModelSpec.isConfigured] is true, i.e. it has a real
 * SHA-256 and byte size. The app never downloads an unverifiable file.
 *
 * To fill in a spec: download the file once yourself, then run
 *     sha256sum gemma-3-1b-it-Q4_K_M.gguf ; stat -c %s gemma-3-1b-it-Q4_K_M.gguf
 * and paste both values below. Verify the URL points at the exact file you hashed.
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

    // TODO(you): confirm this URL, then fill sha256 + sizeBytes. Left blank on purpose:
    // inventing a hash would make the verification meaningless.
    val GEMMA = ModelSpec(
        id = "gemma-3-1b-it",
        displayName = "Gemma 3 1B Instruct (Q4_K_M)",
        fileName = "gemma-3-1b-it-Q4_K_M.gguf",
        url = "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
        sha256 = "",
        sizeBytes = 0L,
        version = "1",
    )

    // Phase 3 will add LFM2-VL 450M and its mmproj here. Phase 4 adds Whisper Base.
    val all: List<ModelSpec> = listOf(GEMMA)
}
