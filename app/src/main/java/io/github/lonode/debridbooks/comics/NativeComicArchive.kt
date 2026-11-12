package io.github.lonode.debridbooks.comics

import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException

/** libarchive's independent RAR4/RAR5 decoder, 7z and TAR; never extracts paths to disk. */
internal object NativeComicArchive {
    suspend fun extract(file: File, destination: File, maxBytes: Long, maxEntries: Int): List<File> {
        check(destination.mkdirs()) { "Extraction directory exists" }
        val pages = mutableListOf<Pair<String, File>>()
        val names = mutableSetOf<String>()
        var expanded = 0L
        var entries = 0
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val archive = Archive.readNew()
                try {
                    Archive.readSupportFilterNone(archive)
                    Archive.readSupportFilterGzip(archive)
                    Archive.readSupportFilterBzip2(archive)
                    Archive.readSupportFilterXz(archive)
                    Archive.readSupportFilterZstd(archive)
                    Archive.readSupportFormatRar(archive)
                    Archive.readSupportFormatRar5(archive)
                    Archive.readSupportFormat7zip(archive)
                    Archive.readSupportFormatTar(archive)
                    Archive.readSupportFormatZip(archive)
                    Archive.readOpenFd(archive, descriptor.fd, 32768)
                    val buffer = ByteBuffer.allocateDirect(32768)
                    val bytes = ByteArray(buffer.capacity())
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = Archive.readNextHeader(archive)
                        if (entry == 0L) break
                        check(++entries <= maxEntries) { "Too many archive entries" }
                        if (ArchiveEntry.isEncrypted(entry)) throw ComicFormatProblem("Password-protected comic archives are not supported.")
                        val name = (ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8)
                            ?: error("Archive entry has no name")).replace('\\', '/')
                        require(!name.startsWith('/') && !name.contains(':') && name.split('/').none { it == ".." }) { "Unsafe archive path" }
                        check(names.add(name)) { "Duplicate archive entry" }
                        require(ArchiveEntry.symlink(entry) == null && ArchiveEntry.hardlink(entry) == null &&
                            ArchiveEntry.filetype(entry) in setOf(ArchiveEntry.AE_IFREG, ArchiveEntry.AE_IFDIR)) { "Unsupported archive entry" }
                        val output = if (ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && ComicArchive.image(name))
                            File(destination, "page-${pages.size}.img") else null
                        var size = 0L
                        val stream = output?.outputStream()
                        try {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                buffer.clear()
                                Archive.readData(archive, buffer)
                                val count = buffer.position()
                                if (count == 0) break
                                size += count; expanded += count
                                check(expanded <= maxBytes && size <= 64L * 1024 * 1024) { "Expanded archive too large" }
                                check(destination.usableSpace > 4L * 1024 * 1024) { "Storage full" }
                                buffer.flip(); buffer.get(bytes, 0, count)
                                stream?.write(bytes, 0, count)
                            }
                        } finally { stream?.close() }
                        if (output != null) { check(size > 0); pages += name to output }
                    }
                } finally { Archive.readFree(archive) }
            }
            check(pages.isNotEmpty()) { "No readable pages" }
            return pages.sortedWith { a, b -> ComicArchive.naturalOrder.compare(a.first, b.first) }.map { it.second }
        } catch (failure: ArchiveException) {
            destination.deleteRecursively()
            if (failure.message.orEmpty().contains("RAR solid archive support unavailable"))
                throw ComicFormatProblem("Solid RAR4 archives are not supported. Choose a non-solid RAR, CBZ or 7z version.")
            throw ComicFormatProblem("This archive could not be read. It may be damaged, password-protected or split across multiple files.")
        } catch (failure: Exception) { destination.deleteRecursively(); throw failure }
    }
}
