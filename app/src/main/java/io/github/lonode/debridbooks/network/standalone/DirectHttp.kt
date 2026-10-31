package io.github.lonode.debridbooks.network.standalone

import java.net.URLEncoder
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Request objects deliberately have no generated toString containing credentials. */
class DirectRequest(val url: String, val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null,
    val contentType: String = "application/x-www-form-urlencoded", val limit: Int = 2_000_000)
class DirectResponse(val status: Int, val bytes: ByteArray, val location: String? = null, val retryAfter: String? = null) {
    fun text() = bytes.toString(Charsets.UTF_8)
    fun json(): JsonObject = try { Json.parseToJsonElement(text()).jsonObject } catch (_: Exception) { throw DirectFailure("Service", "INVALID_RESPONSE") }
}
fun interface DirectTransport { suspend fun execute(request: DirectRequest): DirectResponse }
class DirectFailure(val service: String, val reason: String = "UNAVAILABLE", val retryAt: Long? = null) : IllegalStateException("$service request failed") {
    fun displayMessage(): String = when (reason) {
        "AUTH_BLOCKED" -> "$service needs authorization. Check your account for an approval request, then retry."
        "PREMIUM" -> "$service needs an active premium account for torrent playback."
        "AUTH" -> "$service rejected the API key. Check it and try again."
        "LIMIT" -> "$service is rate limited. Wait before retrying."
        else -> "$service could not complete the request. Check its settings and connectivity, then retry."
    }
}
object DirectHttp : DirectTransport by DirectHttpClient()
internal class DirectHttpClient(private val route: (String) -> String = { it }, private val client: okhttp3.OkHttpClient = sharedClient) : DirectTransport {
    override suspend fun execute(request: DirectRequest): DirectResponse {
        try { StandaloneConfig.endpoint(request.url.substringBefore('?')) }
        catch (_: Exception) { throw DirectFailure("Service") }
        return suspendCancellableCoroutine { continuation ->
            val call = try {
                val builder = okhttp3.Request.Builder().url(route(request.url))
                    .header("User-Agent", "DebridBooks-Android/1 (+https://github.com/lonode/DebridBooks-App)")
                request.headers.forEach { (name, value) -> builder.header(name, value) }
                request.body?.let { builder.post(it.toRequestBody(request.contentType.toMediaType())) }
                client.newCall(builder.build())
            } catch (_: Exception) {
                continuation.resumeWithException(DirectFailure("Service")); return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    continuation.resumeWithException(DirectFailure("Service", transportIssue(e)))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val result = response.use {
                            val content = it.body
                            val expected = content.contentLength()
                            require(expected <= request.limit)
                            val output = java.io.ByteArrayOutputStream()
                            content.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) { throw CancellationException("Request cancelled") }
                                    val count = input.read(buffer)
                                    if (count < 0) { break }
                                    require(output.size() + count <= request.limit)
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (it.isSuccessful) require(expected < 0 || expected == output.size().toLong())
                            DirectResponse(it.code, output.toByteArray(), it.header("Location"), it.header("Retry-After")?.take(256))
                        }
                        continuation.resume(result)
                    } catch (error: Exception) {
                        continuation.resumeWithException(DirectFailure("Service",
                            if (error is java.io.IOException) transportIssue(error) else "INVALID_RESPONSE"))
                    }
                }
            })
        }
    }
    companion object {
        private val sharedClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}

/** Classify only known transport failures; never retain URLs, causes or exception text. */
internal fun transportIssue(error: java.io.IOException): String = when (error) {
    is java.net.UnknownHostException -> "DNS"
    is javax.net.ssl.SSLException -> "TLS"
    is java.io.InterruptedIOException -> "TIMEOUT"
    else -> "UNAVAILABLE"
}

internal fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")
internal fun parameters(values: List<Pair<String, String>>): String = values.joinToString("&") { "${encoded(it.first)}=${encoded(it.second)}" }
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.objects(key: String): List<JsonObject> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(value)
    else -> emptyList()
}
internal fun DirectResponse.requireSuccess(service: String): DirectResponse = apply {
    if (status !in 200..299) throw DirectFailure(service, when (status) { 401, 403 -> "AUTH"; 429 -> "LIMIT"; else -> "UNAVAILABLE" })
}
/** Shared by every API facade and background job, so recreation never resets provider pacing. */
internal class RequestGate(private val interval: Long) {
    private val mutex = Mutex()
    private var last = 0L
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val now = System.nanoTime() / 1_000_000
        if (last > 0) delay((last + interval - now).coerceAtLeast(0))
        last = System.nanoTime() / 1_000_000
        block()
    }
}

internal suspend fun <T> directBoundary(service: String, block: suspend () -> T): T = withContext(Dispatchers.IO) { try { block() }
    catch (cancel: CancellationException) { throw cancel }
    catch (failure: DirectFailure) { throw if (failure.service == "Service") DirectFailure(service, failure.reason) else failure }
    catch (failure: io.github.lonode.debridbooks.network.ApiFailure) { throw failure }
    catch (_: Exception) { throw DirectFailure(service, "INVALID_RESPONSE") }
}
