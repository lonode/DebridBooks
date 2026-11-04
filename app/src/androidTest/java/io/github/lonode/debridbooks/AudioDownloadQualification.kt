package io.github.lonode.debridbooks

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import io.github.lonode.debridbooks.audio.AudioRepository
import io.github.lonode.debridbooks.data.LibraryDatabase
import io.github.lonode.debridbooks.data.SecretCodec
import io.github.lonode.debridbooks.domain.MobileContract
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.Connection
import io.github.lonode.debridbooks.network.MediaPart
import io.github.lonode.debridbooks.network.Resolution
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Real Android HTTP/filesystem/Room checks; all data and requests stay inside this fixture. */
internal object AudioDownloadQualification {
    suspend fun run(context: Context) {
        val root = File(context.cacheDir, "audio-qualification-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        // Synthetic fixture data only. Production encrypted configuration is never opened.
        val codec = object : SecretCodec {
            override fun protect(value: String, account: String) = value.toByteArray(Charsets.UTF_8)
            override fun reveal(bytes: ByteArray, account: String) = bytes.toString(Charsets.UTF_8)
        }
        try {
            Fixture(expireFirstLink = true).use { server ->
                val repo = AudioRepository(isolated, db, codec)
                val item = SearchItem("expired-link-fixture", "Synthetic audio")
                val account = "qualification-expiry"
                val key = MobileContract.localId(item.id)
                repo.queue(account, item)
                check(db.audio().tryStart(account, key, System.currentTimeMillis(), "first-process") == 1)
                repo.download(Connection(server.prefix, account), checkNotNull(db.audio().download(account, key)))
                check(server.resolutions.get() == 2)
                check(server.hits("/expired") == 1 && server.hits("/part-1") == 1 && server.hits("/part-2") == 1)
                val offline = checkNotNull(AudioRepository(isolated, db, codec).offline(account, key))
                check(offline.streams.size == 2)
                offline.streams.forEach { part -> check(File(java.net.URI(part.url)).readBytes().contentEquals(server.audio)) }
                // An incomplete file must never be advertised as available offline.
                File(java.net.URI(offline.streams.last().url)).writeBytes(byteArrayOf(0))
                check(repo.offline(account, key) == null)
                check(db.audio().download(account, key)?.state == "FAILED")
                repo.queue(account, item)
                check(db.audio().tryStart(account, key, System.currentTimeMillis(), "repair-process") == 1)
                repo.download(Connection(server.prefix, account), checkNotNull(db.audio().download(account, key)))
                val repaired = checkNotNull(repo.offline(account, key))
                check(repaired.streams.size == 2)
                check(File(java.net.URI(repaired.streams.first().url)).delete())
                check(repo.offline(account, key) == null)
                check(db.audio().download(account, key)?.state == "FAILED")
                repo.delete(account, key)
                check(db.audio().download(account, key) == null)
                check(db.library().getBook(account, key) != null)
            }
            Fixture(expireFirstLink = false).use { server ->
                val repo = AudioRepository(isolated, db, codec)
                val item = SearchItem("interrupted-fixture", "Synthetic multi-part audio")
                val account = "qualification-interruption"
                val key = MobileContract.localId(item.id)
                repo.queue(account, item)
                check(db.audio().tryStart(account, key, System.currentTimeMillis(), "old-process") == 1)
                var interrupted = false
                try {
                    repo.download(Connection(server.prefix, account), checkNotNull(db.audio().download(account, key)), network = {
                        if (runBlocking { db.audio().download(account, key)?.manifest != null }) {
                            throw CancellationException("Synthetic interruption")
                        }
                    })
                } catch (_: CancellationException) { interrupted = true }
                check(interrupted)
                val partial = checkNotNull(db.audio().download(account, key))
                check(partial.manifest != null && partial.bytes == server.audio.size.toLong())
                db.audio().reconcileInterrupted("new-process")
                check(db.audio().download(account, key)?.state == "PAUSED")
                repo.queue(account, item)
                check(db.audio().tryStart(account, key, System.currentTimeMillis(), "new-process") == 1)
                val reopened = AudioRepository(isolated, db, codec)
                reopened.download(Connection(server.prefix, account), checkNotNull(db.audio().download(account, key)))
                check(server.hits("/part-1") == 1) // Completed files are reused, not fetched again.
                check(server.hits("/part-2") == 1)
                check(reopened.offline(account, key)?.streams?.size == 2)
                reopened.delete(account, key)
            }
        } finally {
            db.close()
            check(root.deleteRecursively())
        }
    }

    private class Fixture(private val expireFirstLink: Boolean) : java.io.Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val prefix = "http://127.0.0.1:${socket.localPort}/fixture"
        val resolutions = AtomicInteger()
        private val requests = ConcurrentHashMap<String, AtomicInteger>()
        // Tiny deterministic payload; decoding is qualified separately using real provider audio.
        val audio = ByteArray(2048) { (it % 97).toByte() }
        fun hits(path: String) = requests[path]?.get() ?: 0
        private val worker = Thread({
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: error("Missing request")
                        while (!reader.readLine().isNullOrEmpty()) { /* Drain HTTP headers. */ }
                        requests.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                        val status: String
                        val body: ByteArray
                        if (path.startsWith("/fixture/app/streams/")) {
                            val count = resolutions.incrementAndGet()
                            status = "200 OK"
                            val base = prefix.removeSuffix("/fixture")
                            val second = if (expireFirstLink && count == 1) "/expired" else "/part-2"
                            body = Json.encodeToString(Resolution(true, listOf(
                                MediaPart("Part 1", "$base/part-1", "part-1.mp3", audio.size.toLong()),
                                MediaPart("Part 2", "$base$second", "part-2.mp3", audio.size.toLong()),
                            ))).toByteArray()
                        } else if (path == "/expired") {
                            status = "403 Forbidden"; body = byteArrayOf()
                        } else {
                            status = "200 OK"; body = audio
                        }
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                } catch (_: Exception) { if (!socket.isClosed) break }
            }
        }, "audio-qualification-http").apply { isDaemon = true; start() }
        override fun close() { socket.close(); worker.join(1000) }
    }
}
