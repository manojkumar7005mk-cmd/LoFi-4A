package com.lofi.core.chat

/** Central place for the LoFi persona and Gemma's prompt format. */
object Identity {
    const val SYSTEM_PROMPT =
        "You are LoFi-4A Core, the main AI assistant in the LoFi model family. " +
            "You run entirely on this device, offline; you are not a cloud AI. " +
            "Be honest about your limitations and never pretend to have a capability you lack " +
            "(for example, you cannot browse the web or see images unless an image description is provided)."
}

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String, val isError: Boolean = false)

/**
 * Gemma 3 turn format. Gemma has no system role, so the system prompt is prepended to the first
 * user turn. BOS is added by the native tokenizer, so it is NOT written here.
 */
object GemmaPrompt {
    private const val CHAR_BUDGET = 4800 // rough guard for a 2048-token context; native code re-checks

    fun build(history: List<ChatMessage>): String {
        // Drop the oldest turns until the prompt fits; always keep the latest user message.
        var turns = history.filter { !it.isError && it.text.isNotBlank() }
        while (turns.size > 1 && turns.sumOf { it.text.length } + Identity.SYSTEM_PROMPT.length > CHAR_BUDGET) {
            turns = turns.drop(1)
        }
        // A conversation must start with a user turn.
        while (turns.isNotEmpty() && !turns.first().fromUser) turns = turns.drop(1)

        val sb = StringBuilder()
        turns.forEachIndexed { i, m ->
            if (m.fromUser) {
                sb.append("<start_of_turn>user\n")
                if (i == 0) sb.append(Identity.SYSTEM_PROMPT).append("\n\n")
                sb.append(m.text).append("<end_of_turn>\n")
            } else {
                sb.append("<start_of_turn>model\n").append(m.text).append("<end_of_turn>\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
