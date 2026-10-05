package com.lofi.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.lofi.core.chat.ChatViewModel
import com.lofi.core.ui.ChatScreen
import com.lofi.core.ui.LoFiTheme

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LoFiTheme { ChatScreen(vm) } }
    }
}
