package io.github.lonode.debridbooks

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.room.Room
import io.github.lonode.debridbooks.comics.*
import io.github.lonode.debridbooks.data.ConnectionStore
import io.github.lonode.debridbooks.data.LibraryDatabase
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.Connection
import io.github.lonode.debridbooks.network.MediaPart
import io.github.lonode.debridbooks.network.standalone.StandaloneConfig
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.*

/** Tiny synthetic local files only. Exercises the real native decoder, PDF renderer and Room/cache path. */
internal object ComicFormatQualification {
    suspend fun run(instrumentation: Instrumentation, stage: (String) -> Unit) {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "comic-format-check-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "offline").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repo = ComicRepository(isolated, db.comics(), ConnectionStore(isolated))
        val account = "9".repeat(64)
        val connection = Connection(accountId = account, standalone = StandaloneConfig("synthetic-unused-key"))
        try {
            val names = listOf("book.cbz", "rar4.cbr", "rar5.cbr", "book.cb7", "book.cbt", "book.tar.gz", "book.tar.bz2", "book.tar.xz", "book.tar.zst")
            val sources = names.map { name ->
                val asset = if (name == "book.tar.gz") "book.tgz" else name
                File(root, name).also { output -> instrumentation.context.assets.open("comic-fixtures/$asset").use { input -> output.outputStream().use { stream -> input.copyTo(stream) } } }
            }.toMutableList()
            val pdf = File(root, "book.pdf")
            val document = PdfDocument()
            try {
                listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(200, 300, index + 1).create())
                    page.canvas.drawColor(color); document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            sources += pdf
            LocalFixture().use { server ->
                for (source in sources) {
                    stage("comic ${source.name}")
                    val expected = if (source == pdf) listOf(Color.RED, Color.GREEN, Color.BLUE) else listOf(Color.RED, Color.BLUE)
                    val decoded = ComicArchive.extract(source, File(root, "direct-${source.name}"))
                    checkColors(decoded, expected)
                    server.body = source.readBytes()
                    val part = MediaPart("fixture-${source.name}", server.url, source.name, source.length())
                    val item = SearchItem("fixture-item-${source.name}", "Synthetic ${source.name}", type = "comic")
                    val session = repo.open(connection, item, streams = listOf(part))
                    checkColors(session.pages.map(::File), expected)
                    val expectedIdentity = session.entry.volumeId
                    repo.checkpoint(session.entry, 1)
                    val cached = repo.open(connection, item, streams = listOf(part))
                    check(cached.entry.volumeId == expectedIdentity && cached.entry.page == 1)
                    val offline = repo.download(cached) { _, _ -> }
                    check(offline.entry.page == 1 && offline.entry.offline)
                    repo.clearCache()
                    val reopened = repo.openOffline(offline.entry)
                    check(reopened.entry.page == 1 && reopened.entry.volumeId == expectedIdentity)
                    checkColors(reopened.pages.map(::File), expected)
                    repo.delete(reopened.entry, history = true)
                    for ((suffix, bytes, entries) in listOf(Triple("bytes", 1L, 5000), Triple("entries", 1_000_000L, 1))) {
                        val destination = File(root, "limit-${source.name}-$suffix")
                        check(runCatching { ComicArchive.extract(source, destination, bytes, entries) }.isFailure)
                        check(!destination.exists())
                    }
                }
            }
            for (name in listOf("unsafe.cbr", "symlink.cbt", "duplicate.cbt")) {
                stage("reject $name")
                val source = File(root, name)
                instrumentation.context.assets.open("comic-fixtures/$name").use { input -> source.outputStream().use { stream -> input.copyTo(stream) } }
                val destination = File(root, "reject-$name")
                check(runCatching { ComicArchive.extract(source, destination) }.isFailure)
                check(!destination.exists())
            }
            for ((name, expected) in listOf(
                "rar4-compressed.cbr" to "5a5f16e01faf8adf92eb4499a2d3e93010c4b41dbb7f698f4a8466d9f58e6dd2",
                "rar5-compressed.cbr" to "588870a2dade35c2650fbb7898c9a9c7f21fce7c281198604e8d0c9737f2c375")) {
                stage("compressed decoder $name")
                val source = File(root, name)
                instrumentation.context.assets.open("comic-fixtures/$name").use { input -> source.outputStream().use { input.copyTo(it) } }
                val bytes = ComicArchive.extract(source, File(root, "compressed-$name")).single().readBytes()
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(actual == expected)
            }
            stage("solid RAR4 explanation")
            val solid = File(root, "solid-rar4.cbr")
            instrumentation.context.assets.open("comic-fixtures/solid-rar4.cbr").use { input -> solid.outputStream().use { input.copyTo(it) } }
            val solidOutput = File(root, "solid-output")
            val solidError = runCatching { ComicArchive.extract(solid, solidOutput) }.exceptionOrNull()
            check(solidError?.message.orEmpty().contains("Solid RAR4") && !solidOutput.exists())
            stage("comic cancellation cleanup")
            val many = File(root, "cancel.cbz")
            ZipOutputStream(many.outputStream()).use { zip ->
                repeat(500) { index ->
                    zip.putNextEntry(ZipEntry("page$index.png")); zip.write(byteArrayOf(1)); zip.closeEntry()
                }
            }
            val cancelled = File(root, "cancelled-output")
            coroutineScope {
                val job = launch(Dispatchers.IO) { ComicArchive.extract(many, cancelled) }
                withTimeout(5000) { while (!cancelled.exists()) delay(1) }
                job.cancelAndJoin()
                check(job.isCancelled && !cancelled.exists())
            }
        } finally { db.close(); root.deleteRecursively() }
    }
    private fun checkColors(files: List<File>, colors: List<Int>) {
        check(files.size == colors.size)
        files.zip(colors).forEach { (file, color) ->
            val bitmap = BitmapFactory.decodeFile(file.path) ?: error("Fixture image unreadable")
            try { check(bitmap.getPixel(0, 0) == color) } finally { bitmap.recycle() }
        }
    }
    private class LocalFixture : AutoCloseable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/synthetic-comic"
        @Volatile var body = byteArrayOf()
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                try { socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* Request headers contain no credentials. */ }
                    val content = body
                    client.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(content)
                    }
                } } catch (_: java.io.IOException) { if (socket.isClosed) break }
            }
        }
        override fun close() { socket.close(); worker.join(5000) }
    }
}
