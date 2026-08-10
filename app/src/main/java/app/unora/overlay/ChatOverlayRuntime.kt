package app.unora.overlay

import app.unora.model.PartyUiState
import kotlinx.coroutines.flow.StateFlow

/** Process-local bridge used while the party Activity is alive in the background. */
object ChatOverlayRuntime {
    @Volatile var partyState: StateFlow<PartyUiState>? = null
        private set
    @Volatile private var sendMessage: ((String) -> Unit)? = null

    fun bind(state: StateFlow<PartyUiState>, sender: (String) -> Unit) {
        partyState = state
        sendMessage = sender
    }

    fun send(text: String) {
        text.trim().takeIf(String::isNotEmpty)?.let { sendMessage?.invoke(it.take(1_000)) }
    }

    fun clear() {
        partyState = null
        sendMessage = null
    }
}
