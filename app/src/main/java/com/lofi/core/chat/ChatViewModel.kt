package com.lofi.core.chat

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lofi.core.llm.EngineException
import com.lofi.core.llm.LlamaEngine
import com.lofi.core.models.ModelDownloader
import com.lofi.core.models.ModelManager
import com.lofi.core.models.ModelState
import com.lofi.core.models.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ModelStore(app.filesDir)
    val models = ModelManager(store, ModelDownloader(store), LlamaEngine())
    val modelInfo get() = models.modelInfo

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var nextId = 0L
    private var downloadJob: Job? = null
    private var generateJob: Job? = null

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = true }
        override fun onLost(network: Network) { _online.value = hasInternet() }
    }

    init {
        models.refresh()
        _online.value = hasInternet()
        connectivity.registerDefaultNetworkCallback(netCallback)
        // If the model is already on disk, load it straight away.
        if (models.state.value is ModelState.Installed) loadModel()
    }

    private fun hasInternet(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            models.download()
            if (models.state.value is ModelState.Installed) models.load()
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun loadModel() { viewModelScope.launch { models.load() } }

    fun resetModel() { viewModelScope.launch { models.deleteAndReset() } }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _generating.value || models.state.value !is ModelState.Loaded) return

        val user = ChatMessage(nextId++, true, trimmed)
        val replyId = nextId++
        _messages.update { it + user + ChatMessage(replyId, false, "") }
        val prompt = GemmaPrompt.build(_messages.value.dropLast(1)) // exclude the empty reply

        _generating.value = true
        generateJob = viewModelScope.launch {
            try {
                models.engine.generate(prompt) { piece ->
                    _messages.update { list ->
                        list.map { if (it.id == replyId) it.copy(text = it.text + piece) else it }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                _messages.update { list ->
                    list.map { if (it.id == replyId && it.text.isEmpty())
                        it.copy(text = e.message ?: "Generation failed.", isError = true) else it }
                }
            } finally {
                _generating.value = false
            }
        }
    }

    fun stop() {
        models.engine.stopGeneration() // native loop exits at the next token, keeping partial text
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(netCallback)
        models.engine.stopGeneration()
        // viewModelScope is already cancelled here; release natively on a throwaway thread so
        // the native context is never leaked.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            withContext(NonCancellable) { models.engine.release() }
        }
    }
}
