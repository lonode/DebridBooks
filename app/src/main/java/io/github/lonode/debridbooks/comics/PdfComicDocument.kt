package io.github.lonode.debridbooks.comics

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FilterOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Rasterize PDF pages into the existing reader/offline cache; no new reader or network stack. */
internal object PdfComicDocument {
    suspend fun extract(file: File, destination: File, maxBytes: Long, maxEntries: Int): List<File> {
        check(destination.mkdirs()) { "Extraction directory exists" }
        try {
            val pages = mutableListOf<File>()
            var expanded = 0L
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    check(renderer.pageCount in 1..maxEntries) { "Too many PDF pages or empty PDF" }
                    repeat(renderer.pageCount) { index ->
                        currentCoroutineContext().ensureActive()
                        renderer.openPage(index).use { page ->
                            require(page.width > 0 && page.height > 0) { "Invalid PDF page" }
                            val scale = minOf(3.0, 3000.0 / max(page.width, page.height), kotlin.math.sqrt(6_000_000.0 / page.width / page.height))
                            val width = ceil(page.width * scale).toInt().coerceAtLeast(1)
                            val height = ceil(page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                currentCoroutineContext().ensureActive()
                                check(destination.usableSpace > 32L * 1024 * 1024) { "Storage full" }
                                val target = File(destination, "page-$index.img")
                                var size = 0L
                                target.outputStream().use { output ->
                                    val bounded = object : FilterOutputStream(output) {
                                        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                            check(size + length <= 64L * 1024 * 1024 && expanded + size + length <= maxBytes) { "Expanded PDF too large" }
                                            out.write(bytes, offset, length); size += length
                                        }
                                    }
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)) { "Could not render PDF page" }
                                }
                                expanded += size
                                pages += target
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
            return pages
        } catch (_: SecurityException) {
            destination.deleteRecursively()
            throw ComicFormatProblem("Password-protected PDFs are not supported.")
        } catch (failure: Exception) { destination.deleteRecursively(); throw failure }
    }
}
