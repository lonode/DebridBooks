package io.github.lonode.debridbooks

import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import io.github.lonode.debridbooks.network.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ComicHttpTest {
    @Test fun `comic sources chapter offsets and saver refresh use existing routes`() = runBlocking {
        val requests = mutableListOf<String>()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            repeat(4) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine().split(" ")[1]
                    requests += path
                    var line = input.readLine()
                    while (!line.isNullOrEmpty()) {
                        check(!line.startsWith("Authorization:", ignoreCase = true))
                        line = input.readLine()
                    }
                    val body = when {
                        path.contains("/search") -> """{"results":[]}"""
                        path.contains("/chapters") -> """{"chapters":[],"total":0,"offset":100,"limit":100,"hasMore":false}"""
                        else -> """{"pages":["https://example.invalid/page.png"],"pageCount":1}"""
                    }.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
        try {
            val api = MobileApi(Connection("http://127.0.0.1:${server.localPort}/synthetic", "account"))
            api.search("Tintin", comic = true)
            api.search("One Piece", comic = true, series = true)
            api.chapters("legacy_id", 100)
            api.pages("00000000-0000-0000-0000-000000000001", saver = true, fresh = true)
            assertEquals("/synthetic/app/search?q=Tintin&page=1&type=comic", requests[0])
            assertEquals("/synthetic/app/search?q=One+Piece&page=1&type=comic&source=series", requests[1])
            assertEquals("/synthetic/app/chapters/legacy_id?offset=100&limit=100", requests[2])
            assertTrue(requests[3].endsWith("?saver=1&fresh=1"))
        } finally { server.close(); worker.join(5000) }
    }
}
