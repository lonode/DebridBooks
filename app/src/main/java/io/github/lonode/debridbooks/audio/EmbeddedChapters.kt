package io.github.lonode.debridbooks.audio

import io.github.lonode.debridbooks.data.AudioChapter
import io.github.lonode.debridbooks.network.HttpBoundary
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** New native implementation of ID3 CHAP, Nero chpl and referenced QuickTime text tracks. */
interface ChapterSource { val size: Long?; suspend fun read(offset: Long, length: Int): ByteArray }
class LocalChapterSource(path: String) : ChapterSource, AutoCloseable {
    private val file = RandomAccessFile(path, "r")
    override val size get() = file.length()
    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length in 0..4 * 1024 * 1024)
        currentCoroutineContext().ensureActive()
        val count = minOf(length.toLong(), (size - offset).coerceAtLeast(0)).toInt()
        return ByteArray(count).also { file.seek(offset); file.readFully(it) }
    }
    override fun close() = file.close()
}
class RemoteChapterSource(private val url: String) : ChapterSource {
    override var size: Long? = null; private set
    private var requests = 0
    private var received = 0L
    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(++requests <= 512 && length in 1..65536 && received + length <= 16 * 1024 * 1024)
        val response = HttpBoundary.range(url, offset, length)
        require(size == null || size == response.total)
        size = response.total; received += response.bytes.size
        return response.bytes
    }
}
class ChapterReader(private val source: ChapterSource) : ChapterSource {
    override val size get() = source.size
    private val blocks = object : LinkedHashMap<Long, ByteArray>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = this.size > 64
    }
    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length in 0..4 * 1024 * 1024 && offset <= Long.MAX_VALUE - length)
        val count = minOf(length.toLong(), size?.let { (it - offset).coerceAtLeast(0) } ?: length.toLong()).toInt()
        val result = ByteArray(count)
        var copied = 0
        while (copied < count) {
            currentCoroutineContext().ensureActive()
            val position = offset + copied; val block = position / 32768
            val bytes = blocks[block] ?: source.read(block * 32768, 32768).also {
                val expected = minOf(32768L, source.size?.let { total -> (total - block * 32768).coerceAtLeast(0) } ?: 32768L).toInt()
                require(it.size == expected) { "Incomplete chapter metadata" }; blocks[block] = it
            }
            val start = (position % 32768).toInt()
            val amount = minOf(count - copied, bytes.size - start)
            require(amount > 0) { "Truncated chapter metadata" }
            bytes.copyInto(result, copied, start, start + amount); copied += amount
        }
        return result
    }
}
object EmbeddedChapters {
    private const val MAX = 5000
    private fun ByteArray.u32(at: Int): Long {
        require(at >= 0 && at + 4 <= size)
        return (0..3).fold(0L) { n, i -> n * 256 + (this[at + i].toInt() and 255) }
    }
    private fun ByteArray.u64(at: Int): Long { val high = u32(at); require(high <= Int.MAX_VALUE); return (high shl 32) or u32(at + 4) }
    private fun ByteArray.u16(at: Int): Int { require(at + 2 <= size); return (this[at].toInt() and 255) * 256 + (this[at + 1].toInt() and 255) }
    private fun ByteArray.text(at: Int, count: Int) = copyOfRange(at, at + count).toString(Charsets.ISO_8859_1)
    private fun ByteArray.sync(at: Int): Int { require(at + 4 <= size); return (0..3).fold(0) { n, i -> require(this[at + i] >= 0); n * 128 + this[at + i] } }
    private fun clean(value: String, fallback: String) = value.replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(500).ifBlank { fallback }
    private fun title(bytes: ByteArray): String = when {
        bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) || (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte())) -> bytes.toString(Charsets.UTF_16)
        else -> bytes.toString(Charsets.UTF_8)
    }
    private fun normalize(value: List<AudioChapter>): List<AudioChapter> {
        val result = mutableListOf<AudioChapter>()
        value.filter { it.startMs in 0..604800000L }.sortedBy { it.startMs }.take(MAX).forEach {
            if (result.isEmpty() || it.startMs - result.last().startMs > 250) result += it.copy(title = clean(it.title, "Chapter ${result.size + 1}"))
        }
        return if (result.size >= 2) result else emptyList()
    }
    suspend fun read(source: ChapterSource): List<AudioChapter> {
        val reader = ChapterReader(source)
        val header = reader.read(0, 10)
        return normalize(if (header.size >= 3 && header.text(0, 3) == "ID3") id3(reader, header) else if (header.size >= 8 && header.text(4, 4) == "ftyp") mp4(reader) else emptyList())
    }
    private suspend fun id3(reader: ChapterSource, head: ByteArray): List<AudioChapter> {
        if (head.size < 10) return emptyList()
        val version = head[3].toInt(); val flags = head[5].toInt()
        if (version !in 3..4 || flags and 0x80 != 0) return emptyList()
        val end = 10L + head.sync(6)
        require(reader.size == null || end <= reader.size!!)
        var at = 10L
        if (flags and 0x40 != 0) { val ext = reader.read(at, 4); at += if (version == 4) ext.sync(0).toLong().also { require(it >= 4) } else ext.u32(0) + 4 }
        val found = mutableListOf<AudioChapter>()
        repeat(4000) {
            if (at + 10 > end) return found
            val header = reader.read(at, 10)
            if (header[0] == 0.toByte()) return found
            val name = header.text(0, 4)
            val length = if (version == 4) header.sync(4).toLong() else header.u32(4)
            require(Regex("[A-Z0-9]{4}").matches(name) && length > 0 && length <= end - at - 10)
            val unreadable = header[9].toInt() and (if (version == 4) 15 else 192) != 0
            if (name == "CHAP" && !unreadable && length <= 65536) {
                val bytes = reader.read(at + 10, length.toInt())
                val idEnd = bytes.indexOf(0)
                if (idEnd >= 0 && idEnd + 17 <= bytes.size) {
                    val start = bytes.u32(idEnd + 1)
                    var title = "Chapter ${found.size + 1}"; var sub = idEnd + 17
                    while (sub + 10 <= bytes.size) {
                        val len = if (version == 4) bytes.sync(sub + 4).toLong() else bytes.u32(sub + 4)
                        if (len <= 0 || len > bytes.size - sub - 10) break
                        if (bytes.text(sub, 4) == "TIT2") {
                            val content = bytes.copyOfRange(sub + 11, sub + 10 + len.toInt())
                            title = when (bytes[sub + 10].toInt()) { 0 -> content.toString(Charsets.ISO_8859_1); 2 -> content.toString(Charsets.UTF_16BE); else -> title(content) }
                        }
                        sub += 10 + len.toInt()
                    }
                    found += AudioChapter(title, start)
                }
            }
            at += 10 + length
        }
        require(at >= end) { "Too many chapter frames" }
        return found
    }
    private data class Box(val type: String, val at: Long, val size: Long, val header: Int) { val body get() = at + header; val end get() = at + size }
    private suspend fun box(reader: ChapterSource, at: Long, limit: Long): Box? {
        if (limit - at < 8) return null
        val bytes = reader.read(at, minOf(16L, limit - at).toInt())
        if (bytes.size < 8) return null
        var size = bytes.u32(0); var header = 8
        if (size == 1L) { require(bytes.size == 16); size = bytes.u64(8); header = 16 }
        if (size == 0L) size = limit - at
        require(size >= header && size <= limit - at)
        return Box(bytes.text(4, 4), at, size, header)
    }
    private suspend fun children(reader: ChapterSource, parent: Box): List<Box> {
        val list = mutableListOf<Box>(); var at = parent.body
        while (at < parent.end && list.size < 512) { val box = box(reader, at, parent.end) ?: break; list += box; at = box.end }
        require(at >= parent.end - 7) { "Too many metadata boxes" }
        return list
    }
    private suspend fun body(reader: ChapterSource, box: Box): ByteArray {
        require(box.size - box.header in 0..4L * 1024 * 1024)
        return reader.read(box.body, (box.size - box.header).toInt())
    }
    private suspend fun mp4(reader: ChapterSource): List<AudioChapter> {
        var at = 0L; var moov: Box? = null
        repeat(64) {
            if (moov != null) return@repeat
            val limit = reader.size ?: Long.MAX_VALUE
            if (at >= limit) return@repeat
            val b = box(reader, at, limit) ?: return@repeat
            if (at == 0L && b.type != "ftyp") return emptyList()
            if (b.type == "moov") moov = b
            at = b.end
        }
        val kids = moov?.let { children(reader, it) } ?: return emptyList()
        val tracks = kids.filter { it.type == "trak" }.map { describe(reader, it) }
        val refs = tracks.flatMap { it.refs }.toSet()
        val track = tracks.firstOrNull { it.id in refs && it.handler !in setOf("soun", "vide") }
        if (track != null) { val result = quickTime(reader, track); if (result.size >= 2) return result }
        val udta = kids.firstOrNull { it.type == "udta" } ?: return emptyList()
        val chpl = children(reader, udta).firstOrNull { it.type == "chpl" } ?: return emptyList()
        val data = body(reader, chpl); require(data.size >= 5)
        var cursor = if (data[0] > 0) 8 else 4
        require(cursor < data.size)
        val count = data[cursor++].toInt() and 255; val found = mutableListOf<AudioChapter>()
        repeat(count) { index ->
            require(cursor + 9 <= data.size)
            val start = data.u64(cursor) / 10000; val len = data[cursor + 8].toInt() and 255; cursor += 9
            require(cursor + len <= data.size)
            found += AudioChapter(clean(title(data.copyOfRange(cursor, cursor + len)), "Chapter ${index + 1}"), start); cursor += len
        }
        return found
    }
    private data class Track(val id: Long?, val refs: List<Long>, val handler: String?, val mdia: Box?)
    private suspend fun describe(reader: ChapterSource, track: Box): Track {
        val kids = children(reader, track)
        val tkhd = kids.firstOrNull { it.type == "tkhd" }?.let { body(reader, it) }
        val id = tkhd?.let { it.u32(if (it[0] == 1.toByte()) 20 else 12) }
        val refs = kids.firstOrNull { it.type == "tref" }?.let { children(reader, it) }.orEmpty().filter { it.type == "chap" }.flatMap {
            val bytes = body(reader, it); require(bytes.size % 4 == 0); (bytes.indices step 4).map { i -> bytes.u32(i) }
        }
        val mdia = kids.firstOrNull { it.type == "mdia" }
        val handler = mdia?.let { children(reader, it).firstOrNull { child -> child.type == "hdlr" } }?.let { body(reader, it).text(8, 4) }
        return Track(id, refs, handler, mdia)
    }
    private suspend fun quickTime(reader: ChapterSource, track: Track): List<AudioChapter> {
        val mdia = track.mdia ?: return emptyList(); val kids = children(reader, mdia)
        val mdhd = body(reader, kids.firstOrNull { it.type == "mdhd" } ?: return emptyList())
        val scale = mdhd.u32(if (mdhd[0] == 1.toByte()) 20 else 12); require(scale > 0)
        val minf = kids.firstOrNull { it.type == "minf" } ?: return emptyList()
        val stbl = children(reader, minf).firstOrNull { it.type == "stbl" } ?: return emptyList()
        val tables = children(reader, stbl)
        suspend fun table(name: String) = body(reader, tables.firstOrNull { it.type == name } ?: error("Missing chapter table"))
        val stts = table("stts"); val starts = mutableListOf<Long>(); var clock = 0L
        val timeCount = stts.u32(4).toInt(); require(timeCount in 1..MAX && 8L + timeCount * 8L <= stts.size)
        repeat(timeCount) { i ->
            val count = stts.u32(8 + i * 8); val delta = stts.u32(12 + i * 8)
            require(count <= MAX - starts.size)
            repeat(count.toInt()) { starts += Math.multiplyExact(clock, 1000) / scale; clock = Math.addExact(clock, delta) }
        }
        val stsz = table("stsz"); val sampleCount = stsz.u32(8).toInt(); require(sampleCount in 1..MAX)
        val uniform = stsz.u32(4)
        val sizes = (0 until sampleCount).map { if (uniform != 0L) uniform else stsz.u32(12 + it * 4) }
        require(starts.size == sampleCount && sizes.all { it in 2..65536 })
        val offsetsBox = tables.firstOrNull { it.type == "stco" || it.type == "co64" } ?: return emptyList()
        val offsets = body(reader, offsetsBox); val chunks = offsets.u32(4).toInt(); require(chunks in 1..MAX)
        val wide = offsetsBox.type == "co64"
        val chunkOffsets = (0 until chunks).map { if (wide) offsets.u64(8 + it * 8) else offsets.u32(8 + it * 4) }
        val stsc = table("stsc"); val runsCount = stsc.u32(4).toInt(); require(runsCount in 1..MAX)
        val runs = (0 until runsCount).map { stsc.u32(8 + it * 12) to stsc.u32(12 + it * 12) }
        require(runs.first().first == 1L && runs.zipWithNext().all { it.first.first < it.second.first } && runs.all { it.second in 1..MAX.toLong() })
        val result = mutableListOf<AudioChapter>(); var sample = 0
        for (chunk in 1..chunks) {
            val run = runs.last { it.first <= chunk }; var position = chunkOffsets[chunk - 1]
            repeat(minOf(run.second.toInt(), sampleCount - sample)) {
                val data = reader.read(position, sizes[sample].toInt())
                val count = data.u16(0); require(count <= data.size - 2)
                result += AudioChapter(title(data.copyOfRange(2, 2 + count)), starts[sample])
                position = Math.addExact(position, sizes[sample]); sample++
            }
            if (sample == sampleCount) break
        }
        require(sample == sampleCount)
        return result
    }
}
