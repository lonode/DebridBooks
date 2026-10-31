package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.audio.*
import io.github.lonode.debridbooks.data.AudioChapter
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmbeddedChaptersTest {
    private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun long(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload
    private fun sync(value: Int) = byteArrayOf(((value shr 21) and 127).toByte(), ((value shr 14) and 127).toByte(), ((value shr 7) and 127).toByte(), (value and 127).toByte())
    private fun source(bytes: ByteArray) = object : ChapterSource {
        override val size = bytes.size.toLong()
        override suspend fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
    }
    private fun frame(name: String, value: ByteArray, version: Int) = name.toByteArray() + (if (version == 4) sync(value.size) else int(value.size)) + byteArrayOf(0, 0) + value
    private fun id3(version: Int): ByteArray {
        fun chapter(id: String, start: Int, title: String): ByteArray = frame("CHAP", id.toByteArray() + byteArrayOf(0) + int(start) + int(start + 30000) + int(-1) + int(-1) + frame("TIT2", byteArrayOf(3) + title.toByteArray(), version), version)
        val frames = frame("APIC", ByteArray(40000), version) + chapter("two", 30000, "Deuxième chapitre") + chapter("one", 0, "Début")
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + sync(frames.size) + frames
    }
    @Test fun `ID3 versions three and four retain French titles and sort absolute chapter starts`() = runBlocking {
        for (version in listOf(3, 4)) {
            val chapters = EmbeddedChapters.read(source(id3(version)))
            assertEquals(listOf(AudioChapter("Début", 0), AudioChapter("Deuxième chapitre", 30000)), chapters)
        }
    }
    @Test fun `truncated ID3 metadata is not a remembered empty chapter list`() = runBlocking {
        val data = id3(4)
        try { EmbeddedChapters.read(source(data.copyOf(data.size - 5))); fail("Truncated tag accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun `Nero chapters after a gigabyte mdat need only small sparse reads`() = runBlocking {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(12))
        val entries = listOf(0L to "Début", 30000L to "Fin")
        val chpl = ByteArray(8).also { it[0] = 1 } + byteArrayOf(2) + entries.fold(byteArrayOf()) { all, (start, title) ->
            val bytes = title.toByteArray(); all + long(start * 10000) + byteArrayOf(bytes.size.toByte()) + bytes
        }
        val moov = box("moov", box("udta", box("chpl", chpl)))
        val mdatSize = 1_000_000_000
        val moovAt = ftyp.size.toLong() + mdatSize
        var received = 0L
        val regions = listOf(0L to ftyp, ftyp.size.toLong() to (int(mdatSize) + "mdat".toByteArray()), moovAt to moov)
        val sparse = object : ChapterSource {
            override val size = moovAt + moov.size
            override suspend fun read(offset: Long, length: Int): ByteArray {
                val bytes = ByteArray(minOf(length.toLong(), (size - offset).coerceAtLeast(0)).toInt()); received += bytes.size
                for ((start, data) in regions) {
                    val first = maxOf(start, offset); val end = minOf(start + data.size, offset + bytes.size)
                    if (end > first) data.copyInto(bytes, (first - offset).toInt(), (first - start).toInt(), (end - start).toInt())
                }
                return bytes
            }
        }
        assertEquals(listOf(AudioChapter("Début", 0), AudioChapter("Fin", 30000)), EmbeddedChapters.read(sparse))
        assertTrue("Read audio payload while looking for chapters", received < 100000)
    }
    @Test fun `QuickTime referenced text track maps sample timing sizes chunks and UTF titles`() = runBlocking {
        val ftyp = box("ftyp", "M4B ".toByteArray() + ByteArray(12))
        val titles = listOf("Premier", "Deuxième").map { val bytes = it.toByteArray(); byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes }
        fun tkhd(id: Int) = box("tkhd", ByteArray(12) + int(id) + ByteArray(8))
        fun handler(name: String) = box("hdlr", ByteArray(8) + name.toByteArray())
        fun moov(offset: Int): ByteArray {
            val audio = box("trak", tkhd(1) + box("tref", box("chap", int(2))) + box("mdia", handler("soun")))
            val stts = box("stts", ByteArray(4) + int(1) + int(2) + int(10000))
            val stsz = box("stsz", ByteArray(8) + int(2) + titles.fold(byteArrayOf()) { acc, bytes -> acc + int(bytes.size) })
            val stsc = box("stsc", ByteArray(4) + int(1) + int(1) + int(2) + int(1))
            val stco = box("stco", ByteArray(4) + int(1) + int(offset))
            val mdhd = box("mdhd", ByteArray(12) + int(1000) + int(20000))
            val text = box("trak", tkhd(2) + box("mdia", mdhd + handler("text") + box("minf", box("stbl", stts + stsz + stsc + stco))))
            return box("moov", audio + text)
        }
        val movie = moov(ftyp.size + moov(0).size + 8)
        val bytes = ftyp + movie + box("mdat", titles.fold(byteArrayOf()) { acc, title -> acc + title })
        assertEquals(listOf(AudioChapter("Premier", 0), AudioChapter("Deuxième", 10000)), EmbeddedChapters.read(source(bytes)))
    }
    @Test fun `unsupported media is a valid empty result and malformed boxes are bounded`() = runBlocking {
        assertTrue(EmbeddedChapters.read(source("RIFF".toByteArray() + ByteArray(40))).isEmpty())
        val malformed = int(1000) + "ftyp".toByteArray() + ByteArray(10)
        try { EmbeddedChapters.read(source(malformed)); fail("Malformed MP4 accepted") } catch (_: IllegalArgumentException) { }
    }
}
