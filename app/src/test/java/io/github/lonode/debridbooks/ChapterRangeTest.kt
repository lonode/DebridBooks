package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.HttpBoundary
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChapterRangeTest {
    private fun serve(status: Int, headers: String, body: String, test: (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) { try {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader(); var line = reader.readLine(); val lines = mutableListOf<String>()
                while (!line.isNullOrEmpty()) { lines += line; line = reader.readLine() }
                assertTrue(lines.any { it == "Range: bytes=0-3" }); assertFalse(lines.any { it.startsWith("Authorization:") })
                socket.getOutputStream().apply { write("HTTP/1.1 $status Test\r\nContent-Length: ${body.length}\r\n${headers}Connection: close\r\n\r\n$body".toByteArray()); flush() }
            }
        } catch (_: java.io.IOException) { } }
        try { test("http://127.0.0.1:${server.localPort}/synthetic") } finally { server.close(); worker.join(5000) }
    }
    @Test fun `valid exact range exposes total without downloading the whole book`() {
        serve(206, "Content-Range: bytes 0-3/1000000000\r\n", "ftyp") { url -> runBlocking {
            val slice = HttpBoundary.range(url, 0, 4)
            assertEquals(1000000000L, slice.total); assertEquals("ftyp", slice.bytes.toString(Charsets.UTF_8))
        } }
    }
    @Test fun `whole media wrong ranges compressed replies and excess bytes fail safely`() {
        val replies = listOf(200 to "", 206 to "Content-Range: bytes 1-4/10\r\n", 206 to "Content-Range: bytes 0-9/10\r\n", 206 to "Content-Range: bytes 0-3/*\r\n", 206 to "Content-Range: bytes 0-3/10\r\nContent-Encoding: gzip\r\n")
        for ((status, headers) in replies) serve(status, headers, "xxxx") { url -> runBlocking {
            val failure = runCatching { HttpBoundary.range(url, 0, 4) }.exceptionOrNull()
            assertNotNull(failure); assertEquals("Chapter metadata could not be read", failure!!.message); assertFalse(failure.stackTraceToString().contains(url))
        } }
        serve(206, "Content-Range: bytes 0-3/10\r\n", "xxxxx") { url -> runBlocking { assertTrue(runCatching { HttpBoundary.range(url, 0, 4) }.isFailure) } }
        serve(206, "Content-Range: bytes 0-3/10\r\n", "xx") { url -> runBlocking { assertTrue(runCatching { HttpBoundary.range(url, 0, 4) }.isFailure) } }
    }
}
