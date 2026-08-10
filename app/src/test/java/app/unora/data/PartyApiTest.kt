package app.unora.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class PartyApiTest {
    @Test
    fun buildsSecureWebSocketUrlFromHttpsEndpoint() {
        val api = PartyApi(OkHttpClient(), Json, "https://edge.example.dev")

        assertEquals(
            "wss://edge.example.dev/api/party/A7BK29/ws",
            api.partySocketUrl("A7BK29"),
        )
    }

    @Test
    fun buildsLocalWebSocketUrlFromHttpEndpoint() {
        val api = PartyApi(OkHttpClient(), Json, "http://10.0.2.2:8787")

        assertEquals(
            "ws://10.0.2.2:8787/api/party/A7BK29/ws",
            api.partySocketUrl("A7BK29"),
        )
    }
}
