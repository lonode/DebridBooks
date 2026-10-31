package io.github.lonode.debridbooks.network

import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Provider sizes are optional hints. Invalid sizes must never break an otherwise playable queue. */
object FileSizeSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FileSize", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long? {
        val value = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: return null
        return value.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
    }
    override fun serialize(encoder: Encoder, value: Long?) {
        (encoder as JsonEncoder).encodeJsonElement(value?.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}

fun fileSizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.ROOT, "%.1f kB", bytes / 1_000.0)
    else -> "$bytes B"
}
data class FileSizeEstimate(val knownBytes: Long, val complete: Boolean)
fun List<MediaPart>.sizeEstimate(): FileSizeEstimate {
    var total = 0L
    var complete = isNotEmpty()
    for (part in this) {
        val size = part.size?.takeIf { it > 0 }
        if (size == null) complete = false
        else if (Long.MAX_VALUE - total < size) return FileSizeEstimate(total, false)
        else total += size
    }
    return FileSizeEstimate(total, complete)
}
fun List<MediaPart>.sizeEstimateLabel(): String {
    val estimate = sizeEstimate()
    return if (estimate.complete) "Download estimate: ${fileSizeLabel(estimate.knownBytes)}"
    else if (estimate.knownBytes > 0) "Known file sizes: ${fileSizeLabel(estimate.knownBytes)} · Total incomplete"
    else "Download size unknown · Total incomplete"
}
fun MediaPart.fileLabel(): String = "${filename ?: title} · ${size?.let(::fileSizeLabel) ?: "Size unknown"}"
