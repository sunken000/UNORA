package app.unora.data

import app.unora.BuildConfig
import app.unora.model.CreatePartyResponse
import app.unora.model.IceConfigResponse
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PartyApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val edgeUrl: String = BuildConfig.UNORA_EDGE_URL,
) {
    suspend fun createParty(): CreatePartyResponse = request("api/party", isPost = true)

    suspend fun iceConfig(): IceConfigResponse = request("api/ice")

    fun partySocketUrl(partyId: String): String {
        val httpUrl = edgeUrl.toHttpUrl().newBuilder()
        .addPathSegments("api/party/$partyId/ws")
        .build()
        // HttpUrl deliberately accepts only HTTP(S). OkHttp's WebSocket client accepts WS(S)
        // strings and normalizes them internally, so convert only after the HttpUrl is complete.
        return when (httpUrl.scheme) {
            "https" -> httpUrl.toString().replaceFirst("https://", "wss://")
            "http" -> httpUrl.toString().replaceFirst("http://", "ws://")
            else -> error("Unsupported edge scheme: ${httpUrl.scheme}")
        }
    }

    private suspend inline fun <reified T> request(path: String, isPost: Boolean = false): T {
        val url = edgeUrl.toHttpUrl().newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (isPost) post("{}".toRequestBody(JSON_MEDIA_TYPE)) else get()
            }
            .build()
        val response = client.newCall(request).await()
        response.use {
            val responseText = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val serverMessage = runCatching {
                    json.parseToJsonElement(responseText).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw PartyApiException(
                    statusCode = it.code,
                    message = serverMessage ?: "Servidor indisponível (${it.code}).",
                )
            }
            if (responseText.isBlank()) throw IOException("O servidor respondeu sem conteúdo.")
            return json.decodeFromString(responseText)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class PartyApiException(val statusCode: Int, message: String) : IOException(message)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
