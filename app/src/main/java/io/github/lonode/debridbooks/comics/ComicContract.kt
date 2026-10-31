package io.github.lonode.debridbooks.comics

import io.github.lonode.debridbooks.network.HttpBoundary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Chapter(val id: String, val num: String? = null, val title: String? = null)
@Serializable
data class ChapterPage(val chapters: List<Chapter>, val total: Int, val offset: Int, val limit: Int, val hasMore: Boolean)
@Serializable
data class PageResponse(val pages: List<String>, val pageCount: Int)
object ComicContract {
    private val json = Json { ignoreUnknownKeys = true }
    fun chapters(body: String): ChapterPage = json.decodeFromString<ChapterPage>(body).also {
        require(it.offset >= 0 && it.limit in 1..500 && it.total >= 0 && it.chapters.size <= it.limit)
        require(it.chapters.map { c -> c.id }.distinct().size == it.chapters.size)
        require(!it.hasMore || it.chapters.isNotEmpty())
        it.chapters.forEach { c -> require(Regex("[0-9a-fA-F-]{36}").matches(c.id)) }
    }
    fun pages(body: String): PageResponse = json.decodeFromString<PageResponse>(body).also {
        require(it.pages.isNotEmpty() && it.pages.size <= 5000 && it.pageCount == it.pages.size)
        it.pages.forEach(HttpBoundary::validateUrl)
    }
}
