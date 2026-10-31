package io.github.lonode.debridbooks.domain

import java.text.Normalizer
import java.util.Locale

/** Public work identity for presentation only. Never used as a resolver or library primary key. */
fun recommendationTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
    .replace(Regex("\\([^)]*(?:edition|unabridged|abridged|full.cast|version integrale)[^)]*\\)"), " ")
    .replace(Regex("\\b(?:book|tome|volume|livre)\\s*\\d+(?:[.,]\\d+)?\\b"), " ")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
private fun recommendationAuthor(author: String?) = author.orEmpty().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
fun recommendationIdentity(title: String, author: String?) = recommendationTitle(title) + ":" + recommendationAuthor(author)

/** Blend ranked feeds fairly; retain each provider's relative order rather than inventing scores. */
fun mergeRecommendations(feeds: List<SimilarDetails>, owned: List<CatalogBook>, limit: Int = 12): SimilarDetails? {
    if (feeds.isEmpty()) return null
    val blockedIds = owned.mapNotNull { it.catalogId }.toSet() + feeds.flatMap { it.seedIds }
    val blockedSeries = owned.flatMap { it.seriesIds }.toSet() + feeds.flatMap { it.seedSeriesIds }
    val seenIds = mutableSetOf<String>()
    val seenTitles = mutableSetOf<String>()
    val usedSeries = mutableSetOf<String>()
    fun take(book: CatalogBook, diversity: Boolean): Boolean {
        val title = recommendationTitle(book.title)
        val identity = recommendationIdentity(book.title, book.author)
        if (book.upcoming || book.current || book.catalogId in blockedIds || (diversity && book.seriesIds.any { it in blockedSeries || it in usedSeries })) return false
        if (owned.any { recommendationTitle(it.title) == title &&
                (it.author.isNullOrBlank() || book.author.isNullOrBlank() || recommendationAuthor(it.author) == recommendationAuthor(book.author)) }) return false
        if (identity in seenTitles || book.catalogId?.let { it in seenIds } == true) return false
        seenTitles += identity; book.catalogId?.let { seenIds += it }
        if (diversity) usedSeries += book.seriesIds
        return true
    }
    val books = mutableListOf<CatalogBook>()
    val depth = feeds.maxOfOrNull { it.books.size } ?: 0
    for (index in 0 until depth) for (feed in feeds) {
        val book = feed.books.getOrNull(index) ?: continue
        if (books.size < limit && take(book, true)) books += book
    }
    val companions = feeds.flatMap { it.companions }.filter { take(it, false) }.take(6)
    return SimilarDetails(feeds.mapNotNull { it.because }.distinct().joinToString(" · ").takeIf { it.isNotBlank() }, books, companions,
        feeds.flatMap { it.seedIds }.distinct(), feeds.flatMap { it.seedSeriesIds }.distinct(),
        feeds.any { it.genreFallback }, feeds.any { it.partial })
}
