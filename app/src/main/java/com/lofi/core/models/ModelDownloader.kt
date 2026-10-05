package com.lofi.core.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Errors with user-presentable messages. */
class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where model files live: files/models/ inside the app's private storage. */
class ModelStore(filesDir: File) {
    val dir: File = File(filesDir, "models").also { it.mkdirs() }

    fun file(spec: ModelSpec) = File(dir, spec.fileName)
    fun partFile(spec: ModelSpec) = File(dir, spec.fileName + ".part")
    private fun versionFile(spec: ModelSpec) = File(dir, spec.fileName + ".version")

    /**
     * Cheap startup check: file exists, has the exact expected size and the installed version
     * matches. The full SHA-256 is verified at download time (hashing ~800 MB on every launch
     * would be slow on low-end phones).
     */
    fun isInstalled(spec: ModelSpec): Boolean {
        val f = file(spec)
        return spec.isConfigured && f.isFile && f.length() == spec.sizeBytes &&
            runCatching { versionFile(spec).readText().trim() }.getOrNull() == spec.version
    }

    fun markInstalled(spec: ModelSpec) = versionFile(spec).writeText(spec.version)

    fun delete(spec: ModelSpec) {
        file(spec).delete(); partFile(spec).delete(); versionFile(spec).delete()
    }
}

class ModelDownloader(private val store: ModelStore) {

    /**
     * Downloads [spec] to `<file>.part` (resuming if a partial file exists), verifies GGUF magic
     * and SHA-256, then atomically renames to the final name. Retries transient I/O errors.
     */
    suspend fun download(
        spec: ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onVerifying: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!spec.isConfigured) {
            throw ModelException(
                "The manifest for ${spec.displayName} has no SHA-256/size yet. " +
                    "Fill them in ModelManifest.kt (see the comment there)."
            )
        }
        val url = URL(spec.url)
        if (url.protocol != "https" || url.host !in ModelManifest.allowedHosts) {
            throw ModelException("Refusing to download from ${url.host}: host is not allowed.")
        }

        val part = store.partFile(spec)
        var attempt = 0
        while (true) {
            try {
                fetchInto(spec, part, onProgress)
                break
            } catch (e: IOException) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw ModelException("Download failed: ${e.message ?: "network error"}. " +
                        "Progress is kept; try again to resume.", e)
                }
                delay(1000L * attempt) // 1s, 2s backoff; partial file is kept for resume
            }
        }

        onVerifying()
        if (!hasGgufMagic(part)) {
            part.delete()
            throw ModelException("Downloaded file is not a GGUF model (corrupt or wrong URL).")
        }
        val actual = sha256Of(part)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw ModelException("SHA-256 mismatch for ${spec.fileName}; the file was discarded.")
        }

        // Atomic rename: the final file name only ever refers to a fully verified model.
        Files.move(part.toPath(), store.file(spec).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        store.markInstalled(spec)
    }

    private suspend fun fetchInto(
        spec: ModelSpec, part: File, onProgress: (Long, Long) -> Unit,
    ) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing > spec.sizeBytes) { part.delete(); existing = 0L }

        val needed = spec.sizeBytes - existing
        if (store.dir.usableSpace < needed + SPACE_MARGIN) {
            throw ModelException("Not enough free storage. Need about ${(needed + SPACE_MARGIN) / 1_000_000} MB.")
        }

        // Cross-host redirects (Hugging Face -> its CDN) are followed by HttpURLConnection.
        // The SHA-256 check below is what protects against unexpected content.
        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val append = when (conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false // server ignored Range: restart from zero
                416 -> { part.delete(); throw IOException("Partial file was invalid; restarting") }
                else -> throw ModelException("Server returned HTTP ${conn.responseCode}.")
            }
            var done = if (append) existing else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive() // cancellation keeps the .part file
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000L) { onProgress(done, spec.sizeBytes); lastReport = now }
                    }
                }
            }
            onProgress(done, spec.sizeBytes)
            if (done != spec.sizeBytes) throw IOException("Connection ended early ($done of ${spec.sizeBytes} bytes)")
        } finally {
            conn.disconnect()
        }
    }

    private fun hasGgufMagic(f: File): Boolean = f.inputStream().use {
        val b = ByteArray(4)
        it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF"
    }

    private suspend fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val SPACE_MARGIN = 100L * 1024 * 1024
    }
}
