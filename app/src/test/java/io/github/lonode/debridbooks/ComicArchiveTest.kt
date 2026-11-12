package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.comics.ComicArchive
import io.github.lonode.debridbooks.comics.ComicContract
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, ByteArray>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }
    @Test fun `nested pages are naturally ordered and metadata is omitted`() = runTest {
        val zip = archive("book/page10.png" to byteArrayOf(10), "book/page2.png" to byteArrayOf(2),
            "__MACOSX/cover.jpg" to byteArrayOf(3), ".hidden.jpg" to byteArrayOf(4), "readme.txt" to byteArrayOf(5))
        val pages = ComicArchive.extract(zip, File(temp.root, "out"))
        assertEquals(listOf(2, 10), pages.map { it.readBytes().single().toInt() })
        assertTrue(pages.all { it.canonicalPath.startsWith(File(temp.root, "out").canonicalPath + "/") })
    }
    @Test fun `traversal is rejected and incomplete extraction is removed`() = runTest {
        for ((index, path) in listOf("../outside.jpg", "/absolute.jpg", "C:/outside.jpg", "..\\outside.jpg").withIndex()) {
            val out = File(temp.root, "out$index")
            try { ComicArchive.extract(archive(path to byteArrayOf(1)), out); fail("Unsafe path accepted") }
            catch (_: IllegalArgumentException) { }
            assertFalse(out.exists())
        }
    }
    @Test fun `actual expanded bytes and entry counts are bounded`() = runTest {
        val zip = archive("p1.jpg" to ByteArray(1024), "p2.jpg" to ByteArray(1024))
        val bytesOut = File(temp.root, "bytes")
        try { ComicArchive.extract(zip, bytesOut, maxBytes = 100); fail("Expansion limit ignored") }
        catch (_: IllegalStateException) { }
        assertFalse(bytesOut.exists())
        val countOut = File(temp.root, "count")
        try { ComicArchive.extract(zip, countOut, maxEntries = 1); fail("Entry limit ignored") }
        catch (_: IllegalStateException) { }
        assertFalse(countOut.exists())
    }
    @Test fun `archive with no pages is rejected`() = runTest {
        try { ComicArchive.extract(archive("readme.txt" to byteArrayOf(1)), File(temp.root, "empty")); fail("No pages accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun `all readable document extensions are recognized without accepting unrelated files`() {
        for (name in listOf("book.CBZ", "book.zip", "book.cbr", "book.RAR", "book.cb7", "book.7z", "book.cbt", "book.tar", "book.pdf", "book.tar.gz", "book.tar.bz2", "book.tar.xz", "book.tar.zst", "book.tgz"))
            assertTrue(name, ComicArchive.document(name))
        for (name in listOf("readme.txt", "book.epub", "book.pdf.exe", "audio.mp3", "plain.gz", "image.jpg"))
            assertFalse(name, ComicArchive.document(name))
        assertTrue(ComicArchive.supported("folder/image.jpg"))
        assertTrue(ComicArchive.supported("folder/book.cbr"))
    }
    @Test fun `ZIP content is recognized even when the provider names it CBR`() = runTest {
        val source = archive("page1.png" to byteArrayOf(1))
        val renamed = File(temp.root, "actually-zip.cbr")
        check(source.renameTo(renamed))
        assertEquals(1.toByte(), ComicArchive.extract(renamed, File(temp.root, "renamed")).single().readBytes().single())
    }
    @Test fun `page contracts validate URLs counts and chapter pagination`() {
        assertEquals(1, ComicContract.pages("""{"pages":["https://example.invalid/one.jpg"],"pageCount":1}""").pages.size)
        listOf("""{"pages":["file:///private"],"pageCount":1}""", """{"pages":[],"pageCount":0}""",
            """{"pages":["https://example.invalid/a"],"pageCount":2}""").forEach { body ->
            assertThrows(IllegalArgumentException::class.java) { ComicContract.pages(body) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComicContract.chapters("""{"chapters":[],"total":1,"offset":0,"limit":100,"hasMore":true}""")
        }
    }
}
