package com.lofi.core.models

import com.lofi.core.llm.EngineException
import com.lofi.core.llm.LlamaEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState
    data object Verifying : ModelState
    data object Installed : ModelState          // on disk, not in RAM
    data object Loading : ModelState
    data object Loaded : ModelState             // ready to chat
    data class Error(val message: String) : ModelState
}

/**
 * Owns model files and the engine lifecycle. Phase 1-2: only Gemma.
 * Later phases will load vision/voice models here and release Gemma first when RAM is tight.
 */
class ModelManager(
    private val store: ModelStore,
    private val downloader: ModelDownloader,
    val engine: LlamaEngine,
) {
    private val spec = ModelManifest.GEMMA
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    val modelInfo: String get() = "${spec.displayName} · v${spec.version}"

    fun refresh() {
        if (_state.value is ModelState.Loaded || _state.value is ModelState.Loading) return
        _state.value = when {
            !spec.isConfigured -> ModelState.Error(
                "Model manifest is incomplete: set sha256 and sizeBytes in ModelManifest.kt.")
            store.isInstalled(spec) -> ModelState.Installed
            else -> ModelState.Missing
        }
    }

    suspend fun download() {
        try {
            _state.value = ModelState.Downloading(0, spec.sizeBytes)
            downloader.download(
                spec,
                onProgress = { d, t -> _state.value = ModelState.Downloading(d, t) },
                onVerifying = { _state.value = ModelState.Verifying },
            )
            _state.value = ModelState.Installed
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = ModelState.Missing // partial file stays for resume
            throw e
        } catch (e: ModelException) {
            _state.value = ModelState.Error(e.message ?: "Download failed.")
        }
    }

    suspend fun load() {
        if (!store.isInstalled(spec)) { refresh(); return }
        _state.value = ModelState.Loading
        try {
            engine.loadModel(store.file(spec).absolutePath)
            _state.value = ModelState.Loaded
        } catch (e: EngineException) {
            _state.value = ModelState.Error(e.message ?: "Model loading failed.")
        }
    }

    suspend fun unload() {
        engine.release()
        refresh()
        if (_state.value is ModelState.Loaded) _state.value = ModelState.Installed
    }

    /** Delete the file (e.g. after a corrupt-model error) so it can be downloaded again. */
    suspend fun deleteAndReset() {
        engine.release()
        store.delete(spec)
        _state.value = ModelState.Missing
        refresh()
    }
}
