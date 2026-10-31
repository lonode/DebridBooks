package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.standalone.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StandaloneTransportTest {
    private class Fixture(private val serve: (java.net.Socket) -> Unit) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val headers = mutableListOf<String>()
        val transport = DirectHttpClient(route = { requested -> "http://127.0.0.1:${socket.localPort}${URI(requested).rawPath}" })
        private val worker = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; synchronized(headers) { headers += line } }
                    serve(client)
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); worker.join(3000) }
    }
    @Test fun `API transport refuses redirects and sends bearer only on the original request`() = runBlocking {
        Fixture { client -> client.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: https://attacker.invalid/collect\r\nRetry-After: 3600\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }.use { fixture ->
            val response = fixture.transport.execute(DirectRequest("https://api.alldebrid.com/v4/user", mapOf("Authorization" to "Bearer synthetic-private")))
            assertEquals("3600", response.retryAfter); assertEquals(302, response.status); assertEquals("https://attacker.invalid/collect", response.location)
            assertEquals(1, fixture.headers.count { it.startsWith("GET ") }); assertTrue(fixture.headers.any { it == "Authorization: Bearer synthetic-private" })
        }
    }
    @Test fun `API transport rejects oversized and truncated success responses without leaking the URL`() = runBlocking {
        for (response in listOf("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n{}", "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\n{}")) {
            Fixture { client -> client.getOutputStream().write(response.toByteArray()) }.use { fixture ->
                val failure = runCatching { fixture.transport.execute(DirectRequest("https://source.invalid/private-passkey", limit = 100)) }.exceptionOrNull()!!
                assertTrue(failure is DirectFailure); assertFalse(failure.toString().contains("private-passkey")); assertNull(failure.cause)
            }
        }
    }
    @Test fun `cancelling a stalled API body disconnects the underlying request`() = runBlocking {
        val disconnected = java.util.concurrent.CountDownLatch(1)
        Fixture { client ->
            client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray())
            client.getOutputStream().flush()
            client.soTimeout = 3000
            try { client.getInputStream().read() } finally { disconnected.countDown() }
        }.use { fixture ->
            val started = System.nanoTime()
            val result = withTimeoutOrNull(250) { fixture.transport.execute(DirectRequest("https://source.invalid/stalled")) }
            assertNull(result)
            assertTrue(disconnected.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
        }
    }
    @Test fun `read timeouts retain a safe reason without leaking URL or network exception text`() = runBlocking {
        Fixture { client ->
            client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray())
            client.getOutputStream().flush()
            client.soTimeout = 2000
            client.getInputStream().read()
        }.use { fixture ->
            val client = okhttp3.OkHttpClient.Builder().readTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val http = DirectHttpClient(route = { requested ->
                val port = fixture.port
                "http://127.0.0.1:$port${URI(requested).rawPath}"
            }, client = client)
            val failure = runCatching { http.execute(DirectRequest("https://source.invalid/private-passkey")) }.exceptionOrNull() as DirectFailure
            assertEquals("TIMEOUT", failure.reason)
            assertFalse(failure.toString().contains("private-passkey"))
            assertNull(failure.cause)
        }
        assertEquals("DNS", transportIssue(java.net.UnknownHostException("private-passkey")))
        assertEquals("TLS", transportIssue(javax.net.ssl.SSLException("private-passkey")))
    }

}
