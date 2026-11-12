package io.github.lonode.debridbooks.comics

import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Comic containers share the same bounded, generated-page cache representation. */
object ComicArchive {
    private val documentExtensions = setOf("cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar", "pdf", "tgz", "tbz2", "txz", "tzst")
    fun document(name: String): Boolean = name.lowercase(java.util.Locale.ROOT).let {
        it.substringAfterLast('.', "") in documentExtensions ||
            listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst").any(it::endsWith)
    }
    fun supported(name: String): Boolean = document(name) || image(name)
    fun image(name: String): Boolean = name.substringAfterLast('/').let {
        !it.startsWith('.') && !name.contains("__MACOSX", true) &&
            it.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
    }
    val naturalOrder = Comparator<String> { a, b ->
        val parts = Regex("[0-9]+|[^0-9]+")
        val left = parts.findAll(a.lowercase()).map { it.value }.toList()
        val right = parts.findAll(b.lowercase()).map { it.value }.toList()
        var result = 0
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            result = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
            if (result != 0) break
        }
        if (result != 0) result else left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
    suspend fun extract(archive: File, destination: File, maxBytes: Long = 1_000_000_000, maxEntries: Int = 5000): List<File> {
        val signature = archive.inputStream().use { input -> ByteArray(8).let { bytes -> val size = input.read(bytes); bytes.take(size.coerceAtLeast(0)).toByteArray() } }
        // Keep existing ZIP behavior and its JVM tests; detect actual content, not a provider's suffix.
        return when {
            signature.take(2) == listOf(0x50.toByte(), 0x4b.toByte()) -> extractZip(archive, destination, maxBytes, maxEntries)
            signature.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> PdfComicDocument.extract(archive, destination, maxBytes, maxEntries)
            else -> NativeComicArchive.extract(archive, destination, maxBytes, maxEntries)
        }
    }
    private suspend fun extractZip(archive: File, destination: File, maxBytes: Long, maxEntries: Int): List<File> {
        check(destination.mkdirs()) { "Extraction directory exists" }
        val images = mutableListOf<Pair<String, File>>()
        val names = mutableSetOf<String>()
        var expanded = 0L; var entries = 0
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    check(++entries <= maxEntries) { "Too many archive entries" }
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith('/') && !name.contains(':') && name.split('/').none { it == ".." }) { "Unsafe archive path" }
                    check(names.add(name)) { "Duplicate archive entry" }
                    // Generated filenames prevent path traversal, symlinks and collisions.
                    val output = if (!entry.isDirectory && image(name)) File(destination, "page-${images.size}.img") else null
                    var size = 0L
                    val stream = output?.outputStream()
                    try {
                        val buffer = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count; expanded += count
                            check(expanded <= maxBytes && size <= 64L * 1024 * 1024) { "Expanded archive too large" }
                            check(destination.usableSpace > 4L * 1024 * 1024) { "Storage full" }
                            stream?.write(buffer, 0, count)
                        }
                    } finally { stream?.close() }
                    if (output != null) { check(size > 0); images += name to output }
                    zip.closeEntry()
                }
            }
            check(images.isNotEmpty()) { "No readable pages" }
            return images.sortedWith { a, b -> naturalOrder.compare(a.first, b.first) }.map { it.second }
        } catch (error: Exception) { destination.deleteRecursively(); throw error }
    }
}
