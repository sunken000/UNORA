package app.unora.party

import app.unora.model.ClientSignal
import app.unora.model.ServerSignal
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface PartySocketEvent {
    data object Connected : PartySocketEvent
    data class Message(val signal: ServerSignal) : PartySocketEvent
    data class Closed(val code: Int, val reason: String) : PartySocketEvent
    data class Failure(val throwable: Throwable) : PartySocketEvent
}

interface PartySocket {
    val events: SharedFlow<PartySocketEvent>
    fun connect(url: String, join: ClientSignal.Join)
    fun send(signal: ClientSignal): Boolean
    fun close()
}

class OkHttpPartySocket(
    private val client: OkHttpClient,
    private val json: Json,
) : PartySocket {
    private val _events = MutableSharedFlow<PartySocketEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<PartySocketEvent> = _events
    private var webSocket: WebSocket? = null

    override fun connect(url: String, join: ClientSignal.Join) {
        close()
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _events.tryEmit(PartySocketEvent.Connected)
                webSocket.send(json.encodeToString<ClientSignal>(join))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString<ServerSignal>(text) }
                    .onSuccess { _events.tryEmit(PartySocketEvent.Message(it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _events.tryEmit(PartySocketEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _events.tryEmit(PartySocketEvent.Failure(t))
            }
        })
    }

    override fun send(signal: ClientSignal): Boolean = webSocket?.send(json.encodeToString<ClientSignal>(signal)) == true

    override fun close() {
        webSocket?.close(NORMAL_CLOSURE, "Leaving party")
        webSocket = null
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
