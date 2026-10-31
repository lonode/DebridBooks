package io.github.lonode.debridbooks.network

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** No credentials in headers, exceptions or diagnostics. Redirects are media-only. */
object HttpBoundary {
    fun validateUrl(value: String) {
        try {
            val uri = URI(value)
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null)
        } catch (_: Exception) { throw IllegalArgumentException("Invalid media URL") }
    }
    private suspend fun <T> response(url: String, redirects: Boolean, timeout: Int, apiErrors: Boolean = false, allowedStatus: Int = 200, headers: Map<String, String> = emptyMap(), action: suspend (HttpURLConnection) -> T): T = withContext(Dispatchers.IO) {
        var current = url
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            validateUrl(current)
            val request = URI(current).toURL().openConnection() as HttpURLConnection
            try {
                request.instanceFollowRedirects = false
                request.connectTimeout = 15000
                request.readTimeout = timeout
                request.setRequestProperty("ngrok-skip-browser-warning", "1")
                headers.forEach { (name, value) -> request.setRequestProperty(name, value) }
                val status = request.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    check(redirects && hop < 5) { "Redirect refused" }
                    val target = URI(current).resolve(request.getHeaderField("Location") ?: error("Missing redirect")).toString()
                    require(!current.startsWith("https:") || target.startsWith("https:")) { "Insecure redirect" }
                    current = target
                } else {
                    if (status != allowedStatus) {
                        if (apiErrors) throw apiFailure(request, status)
                        throw IllegalStateException("Request failed")
                    }
                    return@withContext action(request)
                }
            } finally { request.disconnect() }
        }
        error("Too many redirects")
    }
    private suspend fun readLimited(stream: java.io.InputStream, limit: Int): String = stream.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = it.read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "Response too large" }
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
    private suspend fun apiFailure(request: HttpURLConnection, status: Int): ApiFailure {
        val type = request.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != "application/json" && type?.endsWith("+json") != true) return ApiFailure(status)
        return try {
            val stream = request.errorStream ?: return ApiFailure(status)
            parseApiFailure(status, readLimited(stream, 16_384))
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { ApiFailure(status) }
    }
    suspend fun text(url: String, timeout: Int = 20000): String = try {
        response(url, false, timeout, apiErrors = true) { request -> readLimited(request.inputStream, 2_000_000) }
    } catch (cancel: CancellationException) { throw cancel }
    catch (failure: ApiFailure) { throw failure }
    catch (_: Exception) { throw IllegalStateException("Server request could not be completed") }
    data class RangeSlice(val bytes: ByteArray, val total: Long)
    suspend fun range(url: String, offset: Long, length: Int): RangeSlice {
        require(offset >= 0 && length in 1..65536 && offset <= Long.MAX_VALUE - length)
        return try {
            response(url, true, 15000, allowedStatus = 206, headers = mapOf("Range" to "bytes=$offset-${offset + length - 1}", "Accept-Encoding" to "identity")) { request ->
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(request.getHeaderField("Content-Range").orEmpty()) ?: error("Invalid range response")
                val (first, last, total) = match.destructured.toList().map { it.toLong() }
                require(first == offset && total > first && last == minOf(offset + length - 1, total - 1))
                require(request.contentEncoding == null || request.contentEncoding.equals("identity", true))
                val expected = (last - first + 1).toInt()
                val bytes = request.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= expected) { "Range response too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(bytes.size == expected)
                RangeSlice(bytes, total)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw IllegalStateException("Chapter metadata could not be read") }
    }
    suspend fun download(url: String, file: File, limit: Long, networkCheck: () -> Unit = {}, onProgress: (Long, Long?) -> Unit = { _, _ -> }) {
        currentCoroutineContext().ensureActive()
        onProgress(0, null)
        networkCheck()
        response(url, true, 20000) { request ->
            val expected = request.contentLengthLong
            check(expected <= limit) { "File too large" }
            currentCoroutineContext().ensureActive()
            val length = expected.takeIf { it > 0 }
            onProgress(0, length)
            var total = 0L
            var lastProgress = System.nanoTime()
            try {
                file.outputStream().use { output -> request.inputStream.use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        networkCheck()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= limit) { "File too large" }
                        check(file.parentFile!!.usableSpace > 4L * 1024 * 1024) { "Storage full" }
                        output.write(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now - lastProgress >= 200_000_000L) {
                            onProgress(total, length)
                            lastProgress = now
                        }
                    }
                } }
                check(total > 0 && (expected < 0 || total == expected)) { "Incomplete file" }
                onProgress(total, length)
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
}
