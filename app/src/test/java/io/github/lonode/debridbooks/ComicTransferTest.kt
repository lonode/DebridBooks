package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.HttpBoundary
import java.net.ServerSocket
import java.net.InetAddress
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private class Fixture(private val reply: String, private val chunkSize: Int = Int.MAX_VALUE, private val delayMillis: Long = 0) : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/synthetic"
        val headers = mutableListOf<String>()
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                var line = input.readLine()
                while (!line.isNullOrEmpty()) { headers += line; line = input.readLine() }
                socket.getOutputStream().apply {
                    val bytes = reply.toByteArray()
                    for (offset in bytes.indices step chunkSize) {
                        write(bytes, offset, minOf(chunkSize, bytes.size - offset)); flush()
                        if (delayMillis > 0) Thread.sleep(delayMillis)
                    }
                }
            }
        }
        override fun close() { server.close(); worker.join(5000) }
    }
    @Test fun `media redirects complete without forwarding authorization`() = runBlocking {
        Fixture("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc").use { media ->
            Fixture("HTTP/1.1 302 Found\r\nLocation: ${media.url}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").use { redirect ->
                val file = File(temp.root, "page")
                val progress = mutableListOf<Pair<Long, Long?>>()
                HttpBoundary.download(redirect.url, file, 10, onProgress = { downloaded, total -> progress += downloaded to total })
                assertEquals(3L to 3L, progress.last())
                assertEquals("abc", file.readText())
                assertFalse(media.headers.any { it.startsWith("Authorization:", true) })
            }
        }
    }
    @Test fun `download progress reports intermediate bytes with known and unknown totals`() = runBlocking {
        val body = "x".repeat(256 * 1024)
        for (known in listOf(true, false)) {
            val header = if (known) "Content-Length: ${body.length}\r\n" else ""
            Fixture("HTTP/1.1 200 OK\r\n${header}Connection: close\r\n\r\n$body", 16384, 40).use { fixture ->
                val progress = mutableListOf<Pair<Long, Long?>>()
                val file = File(temp.root, "progress-$known")
                HttpBoundary.download(fixture.url, file, body.length.toLong(), onProgress = { downloaded, total -> progress += downloaded to total })
                assertEquals(body, file.readText())
                assertEquals(0L, progress.first().first)
                assertEquals(body.length.toLong(), progress.last().first)
                assertEquals(if (known) body.length.toLong() else null, progress.last().second)
                assertTrue(progress.any { it.first in 1 until body.length.toLong() })
                assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first })
                if (!known) assertTrue(progress.all { it.second == null })
            }
        }
    }
    @Test fun `truncated oversized and unknown length excess are not finalized`() = runBlocking {
        val responses = listOf(
            "Content-Length: 4\r\n" to "abc",
            "Content-Length: 6\r\n" to "abcdef",
            "" to "abcdef",
            "Content-Length: 0\r\n" to "",
        )
        for ((i, response) in responses.withIndex()) {
            Fixture("HTTP/1.1 200 OK\r\n${response.first}Connection: close\r\n\r\n${response.second}").use { fixture ->
                val target = File(temp.root, "page$i")
                assertTrue(runCatching { HttpBoundary.download(fixture.url, target, 5) }.isFailure)
                assertFalse(target.exists())
            }
        }
    }
}
