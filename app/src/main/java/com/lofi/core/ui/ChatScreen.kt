package com.lofi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lofi.core.chat.ChatMessage
import com.lofi.core.chat.ChatViewModel
import com.lofi.core.models.ModelState

private val Bg = Color(0xFF15131A)
private val Surface1 = Color(0xFF211E29)
private val Accent = Color(0xFFB69CFF)
private val TextMain = Color(0xFFF4F0FA)
private val TextSoft = Color(0xFFCFC9DC)

@Composable
fun LoFiTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, background = Bg, surface = Surface1,
            onSurface = TextMain, onBackground = TextMain,
        ),
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
            bodyLarge = base.bodyLarge.copy(
                fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 25.sp, letterSpacing = 0.sp,
            ),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
            labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        ),
        content = content,
    )
}

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val modelState by vm.models.state.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val ready = modelState is ModelState.Loaded

    // Surface sets the default text color. Without it, text falls back to black on a dark background.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Bg,
        contentColor = TextMain,
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
        ) {
            TopBar(online)
            ModelBanner(modelState, vm)

            val listState = rememberLazyListState()
            LaunchedEffect(messages.lastOrNull()?.text?.length, messages.size) {
                if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { Bubble(it, showSpinner = generating && it == messages.last()) }
            }

            InputBar(ready = ready, generating = generating, onSend = vm::send, onStop = vm::stop)
        }
    }
}

@Composable
private fun TopBar(online: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LoFi-4A",
            style = MaterialTheme.typography.titleLarge,
            color = TextMain,
            modifier = Modifier.weight(1f),
        )
        // Inference is always offline; this only shows whether a network exists (for downloads).
        Box(Modifier.size(8.dp).background(if (online) Color(0xFFE0B040) else Color(0xFF5BD18B), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            if (online) "Network available · on-device AI" else "Offline",
            style = MaterialTheme.typography.labelMedium,
            color = TextSoft,
        )
    }
}

@Composable
private fun ModelBanner(state: ModelState, vm: ChatViewModel) {
    when (state) {
        ModelState.Loaded -> Unit
        else -> Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1, contentColor = TextMain),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vm.modelInfo, style = MaterialTheme.typography.labelLarge)
                when (state) {
                    ModelState.Missing -> {
                        Text("The model is not on this device yet. It downloads once, then works offline.")
                        Button(onClick = vm::downloadModel) { Text("Download model") }
                    }
                    is ModelState.Downloading -> {
                        val frac = if (state.total > 0) state.downloaded.toFloat() / state.total else 0f
                        LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth())
                        Text("Downloading… ${state.downloaded / 1_000_000} / ${state.total / 1_000_000} MB")
                        TextButton(onClick = vm::cancelDownload) { Text("Pause") }
                    }
                    ModelState.Verifying -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Verifying SHA-256…")
                    }
                    ModelState.Installed -> {
                        Text("Model downloaded.")
                        Button(onClick = vm::loadModel) { Text("Load model") }
                    }
                    ModelState.Loading -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Loading model into memory…")
                    }
                    is ModelState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::downloadModel) { Text("Retry download") }
                            TextButton(onClick = vm::resetModel) { Text("Delete & reset") }
                        }
                    }
                    ModelState.Loaded -> Unit
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, showSpinner: Boolean) {
    val align = if (m.fromUser) Alignment.End else Alignment.Start
    val color = when {
        m.isError -> Color(0xFF5A2A2E)
        m.fromUser -> Color(0xFF4A3E6A)
        else -> Color(0xFF2B2736)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(
            Modifier.widthIn(max = 320.dp).background(color, RoundedCornerShape(16.dp)).padding(12.dp)
        ) {
            if (m.text.isEmpty() && showSpinner) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
            } else {
                Text(m.text, color = TextMain)
            }
        }
    }
}

@Composable
private fun InputBar(ready: Boolean, generating: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Phase 3 (LFM2-VL + mmproj). Disabled on purpose: no fake image support.
        IconButton(onClick = {}, enabled = false) { Text("🖼", color = Color.Gray) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (ready) "Message LoFi-4A" else "Load the model to chat", color = TextSoft) },
            enabled = ready && !generating,
            maxLines = 4,
        )
        if (generating) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            Button(onClick = { onSend(text); text = "" }, enabled = ready && text.isNotBlank()) { Text("Send") }
        }
    }
}