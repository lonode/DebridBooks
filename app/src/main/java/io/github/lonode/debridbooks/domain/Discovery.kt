package io.github.lonode.debridbooks.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

object CatalogPositionSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("CatalogPosition", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String? = ((decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive)?.contentOrNull?.take(30)
    override fun serialize(encoder: Encoder, value: String?) = (encoder as JsonEncoder).encodeJsonElement(value?.let { JsonPrimitive(it) } ?: JsonNull)
}
@Serializable data class CatalogBook(val title: String, val search: String? = null, val author: String? = null, val poster: String? = null,
    @Serializable(with = CatalogPositionSerializer::class) val position: String? = null, val released: String? = null, val upcoming: Boolean = false, val current: Boolean = false,
    val catalogId: String? = null, val seriesIds: List<String> = emptyList(), val language: String? = null, val description: String? = null, val genre: String? = null, val durationMinutes: Int? = null)
@Serializable data class SeriesDetails(val name: String, @Serializable(with = CatalogPositionSerializer::class) val position: String? = null,
    val total: Int? = null, val books: List<CatalogBook> = emptyList(), val next: CatalogBook? = null)
@Serializable data class SimilarDetails(val because: String? = null, val books: List<CatalogBook> = emptyList(),
    val companions: List<CatalogBook> = emptyList(), val seedIds: List<String> = emptyList(), val seedSeriesIds: List<String> = emptyList(),
    val genreFallback: Boolean = false, val partial: Boolean = false)
@Serializable data class DiscoveryRecord(val connection: String, val seriesAt: Long = 0, val series: SeriesDetails? = null, val similarAt: Long = 0, val similar: SimilarDetails? = null)
@Serializable data class SeriesEnvelope(val series: SeriesDetails? = null)
@Serializable data class SimilarEnvelope(val similar: SimilarDetails? = null)
fun normalizedBookTitle(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
fun groupUploads(items: List<SearchItem>): List<List<SearchItem>> = items.groupBy {
    listOf(normalizedBookTitle(it.title), normalizedBookTitle(it.author.orEmpty()), it.language.orEmpty().lowercase(), it.type)
}.values.toList()

/** Calendar dates from optional catalog metadata; never infer ownership from release titles. */
fun seriesNews(series: List<SeriesDetails>, today: java.time.LocalDate): List<Pair<String, CatalogBook>> =
    series.distinctBy { it.name }.flatMap { details -> details.books.mapNotNull { book ->
        val released = try { java.time.LocalDate.parse(book.released) } catch (_: Exception) { null }
        if (book.current || released == null || released < today.minusDays(45)) null
        else details.name to book.copy(upcoming = released > today)
    } }.distinctBy { listOf(it.first, it.second.position, it.second.title) }
        .sortedWith(compareBy<Pair<String, CatalogBook>> { it.second.upcoming }
            .thenBy { if (it.second.upcoming) it.second.released else null }
            .thenByDescending { if (!it.second.upcoming) it.second.released else null }).take(8)
