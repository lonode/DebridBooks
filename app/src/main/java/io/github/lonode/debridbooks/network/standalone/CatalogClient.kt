package io.github.lonode.debridbooks.network.standalone

import io.github.lonode.debridbooks.domain.*
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** Public metadata never changes resolver identity, media order, or recording language. */
class CatalogClient(private val http: DirectTransport = DirectHttp, private val requestOwner: Job? = null) {
    private suspend fun checkRequestActive() {
        // A parent can be cancelling before every child has received cancellation.
        requestOwner?.ensureActive()
        currentCoroutineContext().ensureActive()
    }
    private suspend fun json(url: String): JsonObject {
        checkRequestActive()
        val host = java.net.URI(url).host
        val now = System.currentTimeMillis()
        val paused = synchronized(catalogPauses) { catalogPauses[http]?.get(host) ?: 0 }
        if (paused > now) throw DirectFailure("Book catalog", "LIMIT", paused)
        val response = http.execute(DirectRequest(url))
        if (response.status == 429) synchronized(catalogPauses) {
            catalogPauses.getOrPut(http) { mutableMapOf() }[host] = now + maxOf(300_000, AbbAccess.retryAfterMillis(response.retryAfter, now) ?: 0)
        }
        return response.requireSuccess("Book catalog").json()
    }
    private suspend fun <T> optional(block: suspend () -> T): T? = try { withTimeoutOrNull(3500) { block() } }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    suspend fun enrich(item: SearchItem): SearchItem {
        checkRequestActive()
        val scope = listOf(item.type, item.language).joinToString(":") + ":"
        val key = scope + (item.releaseName ?: item.title)
        cachedIdentity(item)?.takeIf { !it.poster.isNullOrBlank() }?.let { return it }
        return metadataLimit.withPermit {
            checkRequestActive()
            // A preceding recording may have identified this author while this request queued.
            val known = cachedIdentity(item)
            known?.takeIf { !it.poster.isNullOrBlank() }?.let { return@withPermit it }
            val name = ReleaseName.parse(item.releaseName ?: item.title, item.type == "comic")
            val title = name.title
            val metadata = run {
                // Keep a previously verified identity even if today's cover lookup fails.
                var identity: Cover? = known?.let { Cover(it.title, it.author, it.poster) }
                val candidates = if (item.type != "comic") optional { itunes(name.query, item.language) }.orEmpty() else emptyList()
                val selected = best(name, candidates, { it.string("collectionName") ?: it.string("trackName") }, { it.string("artistName") })
                if (selected != null) {
                    val cover = Cover(selected.second.title, selected.second.author, selected.first.string("artworkUrl100")?.takeIf {
                        sameTitle(selected.second.title, selected.first.string("collectionName") ?: selected.first.string("trackName").orEmpty())
                    }?.replace(Regex("/[0-9]+x[0-9]+bb\\."), "/600x600bb."))
                    if (!cover.poster.isNullOrBlank()) return@run cover
                    identity = cover
                }
                val audio = if (item.type == "comic") null else audioCover(name, item)
                if (audio != null) {
                    if (!audio.poster.isNullOrBlank()) return@run audio
                    if (identity == null) identity = audio
                }
                val volumes = optional { books(name.query, item.language) }.orEmpty()
                val book = best(name, volumes, { it.string("title") }, { authors(it, "authors") })
                if (book != null) {
                    val cover = Cover(book.second.title, book.second.author, (book.first["imageLinks"] as? JsonObject)?.string("thumbnail")?.takeIf {
                        sameTitle(book.second.title, book.first.string("title").orEmpty())
                    }?.replace("http:", "https:"))
                    if (!cover.poster.isNullOrBlank()) return@run cover
                    if (identity == null) identity = cover
                }
                val docs = optional { json("https://openlibrary.org/search.json?${parameters(listOf("q" to name.query, "limit" to "5", "fields" to "title,author_name,cover_i"))}").objects("docs") }.orEmpty()
                val doc = best(name, docs, { it.string("title") }, { authors(it, "author_name") })
                val cover = Cover(doc?.second?.title ?: title, doc?.second?.author ?: name.author ?: item.author, doc?.first?.string("cover_i")?.toLongOrNull()?.takeIf {
                    it > 0 && sameTitle(doc.second.title, doc.first.string("title").orEmpty())
                }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }, verified = doc != null)
                if (!cover.poster.isNullOrBlank()) cover else (identity ?: cover).let { Cover(it.title, it.author, item.poster, it.verified) }
            }
            if (metadata.verified) synchronized(covers) { if (covers.size >= 256) covers.remove(covers.keys.first()); covers[key] = System.nanoTime() to metadata }
            item.copy(title = metadata.title, author = metadata.author, poster = metadata.poster)
        }
    }
    private class Cover(val title: String, val author: String?, val poster: String?, val verified: Boolean = true)
    private suspend fun audioCover(name: ReleaseName, item: SearchItem): Cover? {
        val language = if (item.language == "fr") "fr" else "en"
        val products = optional { audible(language, params = listOf("keywords" to name.query, "num_results" to "10", "products_sort_by" to "Relevance",
            "response_groups" to "contributors,media,product_attrs")).objects("products").filter { validProduct(it, language) } }.orEmpty()
        val selected = best(name, products, { it.string("title") }, { authors(it) }) ?: return null
        val poster = (selected.first["product_images"] as? JsonObject)?.string("500")?.takeIf { sameTitle(selected.second.title, selected.first.string("title").orEmpty()) }
        return Cover(selected.second.title, selected.second.author, poster ?: item.poster)
    }
    private suspend fun itunes(title: String, language: String?): List<JsonObject> = json("https://itunes.apple.com/search?${parameters(listOf("media" to "audiobook", "limit" to "5", "country" to if (language == "fr") "FR" else "US", "term" to title))}").objects("results")
    private suspend fun books(title: String, language: String?): List<JsonObject> = json("https://www.googleapis.com/books/v1/volumes?${parameters(listOf("q" to title, "maxResults" to "5") + if (language in setOf("fr", "en")) listOf("langRestrict" to language!!) else emptyList())}")
        .objects("items").mapNotNull { it["volumeInfo"] as? JsonObject }
    suspend fun details(item: ResolverItem, language: String?): BookDetails {
        val name = ReleaseName.parse(item.n, item.t == "c")
        val title = name.title
        val candidates = optional { if (item.t != "c") itunes(name.query, language) else emptyList() }.orEmpty()
        val audio = best(name, candidates.filter { !it.string("description").isNullOrBlank() }, { it.string("collectionName") ?: it.string("trackName") }, { it.string("artistName") })
        if (audio != null && sameTitle(audio.second.title, audio.first.string("collectionName") ?: audio.first.string("trackName").orEmpty())) return audio.first.let { BookDetails(audio.second.title, TorrentSearch.plainText(it.string("description").orEmpty()), it.string("primaryGenreName"), year(it.string("releaseDate")), audio.second.author) }
        val book = best(name, optional { books(name.query, language) }.orEmpty().filter { !it.string("description").isNullOrBlank() }, { it.string("title") }, { authors(it, "authors") })
        return if (book != null && sameTitle(book.second.title, book.first.string("title").orEmpty())) book.first.let { BookDetails(book.second.title, TorrentSearch.plainText(it.string("description").orEmpty()), (it["categories"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull, year(it.string("publishedDate")), book.second.author) } else BookDetails(title = title)
    }
    private suspend fun audible(language: String, path: String = "", params: List<Pair<String, String>> = emptyList()): JsonObject {
        val host = if (language == "fr") "api.audible.fr" else "api.audible.com"
        return catalogLimit.withPermit { json("https://$host/1.0/catalog/products$path?${parameters(params)}") }
    }
    private fun locale(item: ResolverItem, setting: String): String? = setting.takeIf { it in setOf("fr", "en") }
        ?: TorrentSearch.language(TorrentResult(item.n, tracker = ""))?.takeIf { it in setOf("fr", "en") }
    private data class ProductMatch(val product: JsonObject, val candidates: List<JsonObject>)
    private suspend fun product(item: ResolverItem, language: String, forSimilar: Boolean = false): ProductMatch? {
        val name = ReleaseName.parse(item.n)
        val title = name.title
        val query = if (forSimilar && volume(item.n) == null) name.query.replace(Regex("(?i)\\bint[eé]grale\\b|\\b\\d+\\s*[-–]\\s*\\d+\\b"), " ").trim() else name.query
        val response = audible(language, params = listOf("keywords" to query, "num_results" to "20", "products_sort_by" to "Relevance", "response_groups" to if (forSimilar) recommendationGroups else groups))
        require(response["products"] is JsonArray)
        val candidates = response.objects("products")
        val requestedAuthor = name.author
        val requestedName = name
        val ranked = candidates.filter { validProduct(it, language) }.map { candidate ->
            val name = candidate.string("title").orEmpty()
            val candidateTitle = "$name ${authors(candidate)}"
            val verified = verifiedAuthorName(requestedName, authors(candidate))
            val titleMatch = score(verified.title, candidateTitle)
            val reverse = score(name, verified.title)
            val requestedVolume = volume(item.n)
            val foundVolume = candidate.objects("series").firstOrNull()?.string("sequence")?.toDoubleOrNull() ?: volume(name)
            val valid = titleMatch >= .7 && reverse >= .8 &&
                (requestedAuthor == null || score(requestedAuthor, authors(candidate)) >= .7) && (requestedVolume == null || foundVolume == requestedVolume) &&
                (!Regex("(?i)full[ -]cast|dramatiz|dramatis|\\babridged\\b|\\babr[eé]g[eé]").containsMatchIn("$name ${candidate.string("subtitle")}") || Regex("(?i)full[ -]cast|dramatiz|dramatis|\\babridged\\b|\\babr[eé]g[eé]").containsMatchIn(item.n))
            candidate to if (valid) titleMatch + reverse else 0.0
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (ranked.size >= 2 && ranked[0].second == ranked[1].second && ranked[0].first.string("title") != ranked[1].first.string("title")) return null
        ranked.firstOrNull()?.first?.let { return ProductMatch(it, candidates) }
        if (!forSimilar || volume(item.n) != null) return null
        // A verified collection can seed similar books without inventing its current volume.
        // Series/next-book lookup keeps the stricter single-volume match above.
        val collectionTitle = title.replace(Regex("(?i)\\bintegrale\\b|\\bintégrale\\b|\\b\\d+\\s*[-–]\\s*\\d+\\b"), " ").trim()
        val collections = candidates.filter { validProduct(it, language) }.flatMap { candidate ->
            candidate.objects("series").filter { series -> validAsin(series.string("asin")) &&
                words(series.string("title").orEmpty()) == words(collectionTitle) &&
                series.string("sequence")?.toDoubleOrNull() != null &&
                (name.author == null || score(name.author, authors(candidate)) >= .5)
            }.map { Triple(it.string("asin")!!, it.string("sequence")!!.toDouble(), candidate) }
        }
        if (collections.map { it.first }.distinct().size != 1) return null
        return collections.minByOrNull { it.second }?.third?.let { ProductMatch(it, candidates) }
    }
    suspend fun series(item: ResolverItem, languageSetting: String): SeriesDetails? = withTimeout(15000) {
        val language = locale(item, languageSetting) ?: return@withTimeout null
        val product = product(item, language)?.product ?:  return@withTimeout null
        val own = product.objects("series").filter { validAsin(it.string("asin")) && it.string("sequence")?.toDoubleOrNull() != null }
            .maxByOrNull { score(it.string("title").orEmpty(), item.n) } ?: return@withTimeout null
        val position = own.string("sequence")!!.toDouble()
        val series = audible(language, "/${own.string("asin")}", listOf("response_groups" to "relationships,product_desc"))["product"] as? JsonObject ?: return@withTimeout null
        val members = series.objects("relationships").filter { it.string("relationship_type") == "series" &&
            it.string("relationship_to_product") in setOf(null, "child") && validAsin(it.string("asin")) && it.string("sequence")?.toDoubleOrNull() != null }
            .distinctBy { it.string("asin") }.sortedBy { it.string("sequence")!!.toDouble() }
        val hydrated = mutableMapOf(product.string("asin") to product)
        members.take(500).chunked(50).forEach { batch ->
            val response = audible(language, params = listOf("asins" to batch.joinToString(",") { it.string("asin")!! }, "response_groups" to groups))
            require(response["products"] is JsonArray)
            response.objects("products").filter { validProduct(it, language) }.forEach { hydrated[it.string("asin")] = it }
        }
        val books = (members.take(500).mapNotNull { member -> hydrated[member.string("asin")]?.let { book(it, language, member.string("sequence"), member.string("sequence")?.toDoubleOrNull() == position) } } + book(product, language, own.string("sequence"), true))
            .distinctBy { it.position?.toDoubleOrNull() }.sortedBy { it.position?.toDoubleOrNull() }.take(500)
        if (books.size < 2) return@withTimeout null
        SeriesDetails(own.string("title") ?: series.string("title") ?: cleanTitle(item.n), own.string("sequence"), if (members.size <= 500) books.size else null, books,
            books.firstOrNull { (it.position?.toDoubleOrNull() ?: 0.0) > position })
    }
    /** Bounded catalog expansion; provider ordering is retained within each source. */
    suspend fun similar(item: ResolverItem, languageSetting: String): SimilarDetails? = coroutineScope {
        val deadline = System.nanoTime() + 20_000_000_000L
        val language = locale(item, languageSetting) ?: return@coroutineScope null
        val match = withTimeout(5000) { product(item, language, forSimilar = true) } ?: return@coroutineScope null
        val seed = match.product
        val ownSeries = seriesIds(seed).toSet()
        var failures = 0
        var successes = 0
        suspend fun request(path: String = "", params: List<Pair<String, String>>, field: String): List<JsonObject>? {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(3500)
            if (remaining <= 0) { failures++; return null }
            val response = try { withTimeoutOrNull(remaining) { audible(language, path, params).also { require(it[field] is JsonArray) } } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            if (response == null) { failures++; return null }
            successes++
            return response.objects(field)
        }
        val pool = linkedMapOf<String, JsonObject>()
        val companions = linkedMapOf<String, JsonObject>()
        fun collect(candidates: List<JsonObject>) {
            candidates.forEach { candidate ->
                val id = candidate.string("asin") ?: return@forEach
                if (!validProduct(candidate, language) || id == seed.string("asin") || seriesIds(candidate).any { it in ownSeries }) return@forEach
                val sameTitle = recommendationTitle(candidate.string("title").orEmpty()) == recommendationTitle(seed.string("title").orEmpty())
                val seedAuthor=authors(seed);val candidateAuthor=authors(candidate)
                if(sameTitle && (seedAuthor.isBlank() || candidateAuthor.isBlank() || score(seedAuthor,candidateAuthor)>=.7 || score(candidateAuthor,seedAuthor)>=.7)) return@forEach
                if (book(candidate, language).upcoming || unsuitable(candidate, seed)) return@forEach
                if (companion(candidate) && !companion(seed)) companions.putIfAbsent(id, candidate)
                else if (compatibleGenres(seed, candidate)) pool.putIfAbsent(id, candidate)
            }
        }
        val siblings = match.candidates.filter { candidate ->
            validProduct(candidate, language) && candidate.string("asin") != seed.string("asin") &&
                seriesIds(candidate).any { it in ownSeries } && !companion(candidate) && !unsuitable(candidate, seed)
        }.distinctBy { it.string("asin") }.sortedBy { sequence(it) ?: Double.MAX_VALUE }.take(2)
        for (current in listOf(seed) + siblings) {
            if (pool.size >= 24) break
            request("/${current.string("asin")}/sims", listOf("similarity_type" to "RawSimilarities", "num_results" to "20", "response_groups" to recommendationGroups), "similar_products")?.let(::collect)
        }
        request("/${seed.string("asin")}/sims", listOf("similarity_type" to "ByTheSameAuthor", "num_results" to "20", "response_groups" to recommendationGroups), "similar_products")?.let(::collect)
        var genreFallback = false
        if (pool.size < 12) {
            // Deepest category first. Audience-only roots such as Children are never a fallback.
            val categories = seed.objects("category_ladders").flatMap { it.objects("ladder").drop(1).takeLast(2).asReversed() }
                .mapNotNull { it.string("id") }.distinct().take(2)
            for (category in categories) {
                if (pool.size >= 36) break
                val candidates = request(params = listOf("category_id" to category, "num_results" to "50", "products_sort_by" to "Relevance", "response_groups" to recommendationGroups), field = "products")
                if (candidates != null) { val before = pool.size; collect(candidates); genreFallback = genreFallback || pool.size > before }
            }
        }
        // Recommend a starting point, never an unexplained volume five. Prefer a first volume
        // already in the pool; otherwise resolve at most six distinct series through the catalog.
        val starts = pool.values.filter { (sequence(it) ?: 1.0) <= 1.0 }.flatMap { p -> seriesIds(p).map { it to p } }.toMap()
        val resolved = mutableMapOf<String, JsonObject?>()
        val ordered = mutableListOf<JsonObject>()
        for (candidate in pool.values) {
            if (ordered.size >= 36) break
            if ((sequence(candidate) ?: 1.0) <= 1.0) { ordered += candidate; continue }
            val series = candidate.objects("series").firstOrNull { validAsin(it.string("asin")) } ?: continue
            val id = series.string("asin")!!
            var first = starts[id]
            if (first == null && id !in resolved && resolved.size < 6) {
                val title = series.string("title")
                first = title?.let { request(params = listOf("keywords" to it, "num_results" to "20", "response_groups" to recommendationGroups), field = "products") }
                    ?.firstOrNull { validProduct(it, language) && id in seriesIds(it) && sequence(it) == 1.0 &&
                        !companion(it) && !unsuitable(it, seed) && compatibleGenres(seed, it) && !book(it, language).upcoming }
                resolved[id] = first
            }
            (first ?: resolved[id])?.let { ordered += it }
        }
        val seen = mutableSetOf(seed.string("asin"))
        val usedSeries = mutableSetOf<String>()
        val titles = mutableSetOf<String>()
        val books = ordered.filter { candidate ->
            val series = seriesIds(candidate)
            val title = recommendationIdentity(candidate.string("title").orEmpty(), authors(candidate))
            if (candidate.string("asin") in seen || title in titles || series.any { it in ownSeries || it in usedSeries }) false
            else { seen += candidate.string("asin"); titles += title; usedSeries += series; true }
        }.take(12).map { book(it, language) }
        if (books.isEmpty() && companions.isEmpty() && failures > 0) error("Recommendation lookup incomplete")
        check(successes > 0) { "Recommendation lookup unavailable" }
        if (books.isEmpty() && companions.isEmpty()) null else SimilarDetails(seed.string("title"), books,
            companions.values.take(6).map { book(it, language) }, listOfNotNull(seed.string("asin")), ownSeries.toList(), genreFallback, failures > 0)
    }

    private fun seriesIds(product: JsonObject) = product.objects("series").mapNotNull { it.string("asin") }.filter(::validAsin)
    private fun sequence(product: JsonObject) = product.objects("series").mapNotNull { it.string("sequence")?.toDoubleOrNull() }.maxOrNull()
    private fun companion(product: JsonObject): Boolean = companionPattern.containsMatchIn("${product.string("title")} ${product.string("subtitle").orEmpty()}")
    private fun unsuitable(candidate: JsonObject, seed: JsonObject): Boolean {
        val title = "${candidate.string("title")} ${candidate.string("subtitle").orEmpty()}"
        val sourceTitle = "${seed.string("title")} ${seed.string("subtitle").orEmpty()}"
        if (summaryPattern.containsMatchIn(title) && !summaryPattern.containsMatchIn(sourceTitle)) return true
        if (candidate.string("content_type")?.let { it != "Product" } == true) return true
        val length = candidate.string("runtime_length_min")?.toDoubleOrNull()
        val seedLength = seed.string("runtime_length_min")?.toDoubleOrNull()
        // Keep short-story recommendations for short-story listeners. A children's compilation
        // is a mismatch for a long novel, not a universally bad book.
        val children = candidate.objects("category_ladders").any { ladder ->
            ladder.objects("ladder").firstOrNull()?.string("name")?.let { Regex("(?i)jeunesse|children").containsMatchIn(it) } == true }
        return seedLength != null && seedLength >= 180 && length != null && !compilationPattern.containsMatchIn(sourceTitle) &&
            ((length < 180 && compilationPattern.containsMatchIn(title)) || (length < 90 && children))
    }
    private fun compatibleGenres(seed: JsonObject, candidate: JsonObject): Boolean {
        fun topics(product: JsonObject) = product.objects("category_ladders").flatMap { it.objects("ladder") }
            .flatMap { words(it.string("name").orEmpty().replace(Regex("(?i)science[ -]fiction|sci[ -]fi"), "sciencefiction")) }
            .filterNot { it in genericCategories }.toSet()
        val wanted=topics(seed);val found=topics(candidate)
        // Missing category data is not evidence of a mismatch. Known unrelated genres are.
        return wanted.isEmpty() || found.isEmpty() || wanted.any { it in found }
    }

    /** Fetch public metadata for this exact recording; never turn a catalog card into a resolver. */
    suspend fun recommendationDetails(entry: CatalogBook): CatalogBook = withTimeout(8000) {
        val id = entry.catalogId?.takeIf(::validAsin) ?: return@withTimeout entry
        val language = entry.language?.takeIf { it in setOf("fr", "en") } ?: return@withTimeout entry
        val product = audible(language, "/$id", listOf("response_groups" to recommendationGroups))["product"] as? JsonObject
            ?: error("Missing catalog details")
        require(product.string("asin") == id && validProduct(product, language))
        book(product, language, entry.position, entry.current)
    }

    private fun book(product: JsonObject, language: String, position: String? = null, current: Boolean = false): CatalogBook {
        val released = product.string("release_date")?.take(10)?.let { value -> try { LocalDate.parse(value).toString() } catch (_: Exception) { null } }
        val poster = (product["product_images"] as? JsonObject)?.let { it.string("500") ?: it.string("400") }?.takeIf { it.startsWith("https://") }
        val today = LocalDate.now(if (language == "fr") ZoneId.of("Europe/Paris") else ZoneId.of("UTC")).toString()
        return CatalogBook(product.string("title")!!.take(500), product.string("title")!!.take(500), authors(product).takeIf { it.isNotBlank() }, poster, position, released, released != null && released > today, current, product.string("asin"), seriesIds(product), language,
            product.string("merchandising_summary")?.let { TorrentSearch.plainText(it).take(6000) }?.takeIf { it.isNotBlank() },
            product.objects("category_ladders").mapNotNull { it.objects("ladder").lastOrNull()?.string("name") }.distinct().take(3).joinToString(" · ").take(300).takeIf { it.isNotBlank() },
            product.string("runtime_length_min")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it < 100000 }?.toInt())
    }
    companion object {
        private val genericCategories = setOf("genres", "jeunesse", "adolescents", "jeunes", "adultes", "children", "childrens", "young", "adult", "teen", "teens", "litterature", "literature", "roman", "romans", "fiction", "oeuvres")
        private val companionPattern = Regex("(?i)\\b(companion|study guide|reading guide|guide de lecture|encyclop[eé]d\\w*|making[ -]of|behind the scenes|art of|archives (?:du|de|of the) (?:monde|world|universe))\\b")
        private val summaryPattern = Regex("(?i)\\b(summary|summaries|study guide|analysis|analyse|analyse litt[eé]raire|r[eé]sum[eé]|fiche de lecture)\\b")
        private val compilationPattern = Regex("(?i)\\b(compilation|anthology|anthologie|short stories|histoires|contes|stories)\\b")
        private const val recommendationGroups = "series,product_desc,contributors,media,product_attrs,category_ladders"
        private const val groups = "series,product_desc,contributors,media,product_attrs"
        private val metadataLimit = Semaphore(4); private val catalogLimit = Semaphore(4)
        private val covers = linkedMapOf<String, Pair<Long, Cover>>()
        private val catalogPauses = java.util.WeakHashMap<DirectTransport, MutableMap<String, Long>>()
        internal fun cachedIdentity(item: SearchItem): SearchItem? {
            val scope = listOf(item.type, item.language).joinToString(":") + ":"
            val key = scope + (item.releaseName ?: item.title)
            val exact = synchronized(covers) { covers[key]?.takeIf { System.nanoTime() - it.first < 3_600_000_000_000L }?.second }
            exact?.takeIf { !it.poster.isNullOrBlank() }?.let { return item.copy(title = it.title, author = it.author, poster = it.poster) }
            val name = ReleaseName.parse(item.releaseName ?: item.title, item.type == "comic")
            val entries = synchronized(covers) { covers.entries.filter { it.key.startsWith(scope) && System.nanoTime() - it.value.first < 3_600_000_000_000L }.map { it.key to it.value.second } }
            val full = listOfNotNull(name.title, name.author).joinToString(" - ")
            val matches = entries.mapNotNull { (_, cover) ->
                val author = cover.author ?: return@mapNotNull null
                val verified = verifiedAuthorName(name, author)
                if (withoutAuthor(listOfNotNull(verified.title, verified.author).joinToString(" - "), author) == null) return@mapNotNull null
                matchName(verified, cover.title, author)?.let { it to cover.poster?.takeIf { _ -> sameTitle(it.title, cover.title) } }
            }.sortedByDescending { it.second != null }.distinctBy { words(it.first.title) to words(it.first.author.orEmpty()) }
            return matches.singleOrNull()?.let { item.copy(title = it.first.title, author = it.first.author, poster = it.second ?: item.poster) }
                ?: exact?.let { item.copy(title = it.title, author = it.author, poster = item.poster) }
        }
        private val stop = setOf("le", "la", "les", "de", "du", "des", "un", "une", "the", "a", "and", "et", "tome", "volume", "book")
        private fun foldLatin(value: String) = Normalizer.normalize(value.replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae").replace("Æ", "AE"), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        private fun words(value: String) = Regex("[a-z0-9]+").findAll(foldLatin(value.replace(Regex("(?i)\\b(hyper|hypo)[\\s-]+(?=\\p{L}{3})"), "$1"))).map { it.value }.filter { it !in stop }.toList()
        internal fun score(query: String, candidate: String): Double {
            if (Regex("(?i)\\b(analyse|analysis|summary|résumé|resume|study guide|fiche de lecture)\\b").containsMatchIn(candidate) && !Regex("(?i)\\b(analyse|analysis|summary|résumé|resume|study guide|fiche de lecture)\\b").containsMatchIn(query)) return 0.0
            val volume = volume(query); val other = volume(candidate)
            if (volume != null && other != null && volume != other) return 0.0
            val wanted = words(query); val tokens = words(candidate)
            val have = tokens.toSet() + tokens.zipWithNext().filter { it.first.length == 1 && it.second.length == 1 }.map { it.first + it.second }
            return if (wanted.isEmpty()) 0.0 else wanted.count { it in have }.toDouble() / wanted.size
        }
        /** Compare the whole release, not the tentative title/author split. Either order is valid. */
        internal fun matchName(name: ReleaseName, title: String, author: String): ReleaseName? {
            if (title.isBlank() || author.isBlank()) return null
            val verified = verifiedAuthorName(name, author)
            val full = listOfNotNull(verified.title, verified.author).joinToString(" - ")
            val requestedVolume = volume(full)
            if (requestedVolume != null && volume(title) == null && numbers(title).isNotEmpty() && requestedVolume !in numbers(title)) return null
            if (summaryPattern.containsMatchIn(title) && !summaryPattern.containsMatchIn(full)) return null
            val remaining = withoutAuthor(full, author)
            val catalogLabel = ReleaseName.parse(title).query
            val authorInTitle = withoutAuthor(catalogLabel, author)
            val cleanTitle = authorInTitle ?: catalogLabel
            // An explicit catalogue author at either edge resolves the ambiguous separator.
            // Keep extra subtitles/works in the release, rather than collapsing an omnibus.
            if (remaining != null) {
                if (score(cleanTitle, remaining) < .9 && score(cleanTitle.substringBefore(":"), remaining) < .9) return null
                if (volume(full) != null && volume(title) != null && volume(full) != volume(title)) return null
                val display = when {
                    authorInTitle != null -> remaining
                    words(remaining) == words(cleanTitle) -> cleanTitle
                    words(remaining) == words(cleanTitle.substringBefore(":")) -> cleanTitle.substringBefore(":").trim()
                    else -> remaining
                }
                return ReleaseName(display, author)
            }
            // Without explicit author evidence, require near-exact title agreement both ways.
            if (score(full, cleanTitle) < .9 || score(cleanTitle, full) < .9) return null
            // Never collapse a collection to one volume, or replace a known volume with another.
            if (Regex("(?i)\\b(int[eé]grale|collection|omnibus)\\b").containsMatchIn(full) &&
                !Regex("(?i)\\b(int[eé]grale|collection|omnibus)\\b").containsMatchIn(title)) return null
            val number = volume(full)
            if (number != null && volume(title) != null && number != volume(title)) return null
            val canonical = if (withoutAuthor(ReleaseName.parse(title).query, author) != null) full else title
            val display = if (number != null && volume(title) == null && number !in numbers(canonical)) "$canonical Tome ${number.toString().removeSuffix(".0")}" else canonical
            return ReleaseName(display, author)
        }
        /** Only a real catalogue contributor can establish the author boundary. */
        private fun verifiedAuthorName(name: ReleaseName, author: String): ReleaseName {
            if (author.isBlank()) return name
            val full = listOfNotNull(name.title, name.author).joinToString(" - ")
            val withoutDate = full.replace(Regex("\\s+(?:\\[(?:18|19|20)\\d{2}\\]|\\((?:18|19|20)\\d{2}\\)|(?:18|19|20)\\d{2})$"), "")
            // A year AFTER a confirmed trailing author is outside the work title.
            // Never strip a year that is part of the title (e.g. Paris 1919).
            val title = withoutAuthor(withoutDate, author, endOnly = true) ?: return name
            val recording = title.replace(Regex("(?i)\\s+(?:une?\\s+)?fiction\\s+sonore(?:\\s+originale?)?(?:\\s+d['’]apr[eè]s(?:\\s+l['’][œo]e?uvre\\s+de)?)?\\s*$"), "")
            return if (withoutDate != full || recording != title) ReleaseName(recording, author) else name
        }
        private fun withoutAuthor(full: String, author: String, endOnly: Boolean = false): String? {
            val token = Regex("[\\p{L}\\p{N}]+")
            fun initials(value: String) = value.replace(Regex("\\b([A-Z]{2,3})(?=\\s+\\p{Lu}\\p{Ll})")) { it.value.toCharArray().joinToString(" ") }
            val expanded = Normalizer.normalize(initials(full), Normalizer.Form.NFC)
            val source = token.findAll(expanded).toList()
            val target = token.findAll(Normalizer.normalize(initials(author), Normalizer.Form.NFC)).map { foldLatin(it.value) }.toList()
            if (target.isEmpty() || source.size <= target.size) return null
            for (start in (if (endOnly) listOf(source.size - target.size) else listOf(0, source.size - target.size).distinct())) {
                if (target.indices.all { index ->
                    val found = foldLatin(source[start + index].value)
                    found == target[index] || found.length == 1 && target[index].startsWith(found)
                }) {
                    val end = start + target.lastIndex
                    return expanded.removeRange(source[start].range.first, source[end].range.last + 1)
                        .trim(' ', '-', '–', '—', ':', ',', ';')
                }
            }
            return null
        }
        private fun numbers(value: String) = Regex("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?(?![\\p{L}\\p{N}])").findAll(value)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toSet()
        private fun sameTitle(first: String, second: String): Boolean {
            val one = ReleaseName.parse(first).query
            val two = ReleaseName.parse(second).query
            // Catalogues use both "Series Tome 1" and "Series 1". Require the
            // same numbers without discarding artwork solely for a missing label.
            if (numbers(one) != numbers(two)) return false
            val firstVolume = volume(first); val secondVolume = volume(second)
            if (firstVolume != null && secondVolume != null && firstVolume != secondVolume) return false
            return score(one, two) >= .9 && score(two, one) >= .9
        }
        private fun best(name: ReleaseName, list: List<JsonObject>, title: (JsonObject) -> String?, author: (JsonObject) -> String?): Pair<JsonObject, ReleaseName>? {
            val matches = list.mapNotNull { row -> matchName(name, title(row).orEmpty(), author(row).orEmpty())?.let { row to it } }
            // Prefer an exact title over a catalogue's expanded/repeated series label.
            // Do not turn "Divergente 1 - Divergente" plus "Divergente - Tome 1"
            // into an ambiguity when the latter exactly matches the requested title.
            val fullName = listOfNotNull(name.title, name.author).joinToString(" - ")
            val exact = matches.filter { (row, _) ->
                val wanted = withoutAuthor(fullName, author(row).orEmpty()) ?: fullName
                words(ReleaseName.parse(title(row).orEmpty()).query) == words(wanted)
            }
            val preferred = exact.ifEmpty { matches }
            // Several editions by the same writer are fine; competing identities are ambiguous.
            val identities = preferred.map { words(it.second.title) to words(it.second.author.orEmpty()) }.distinct()
            if (preferred.isNotEmpty()) return if (identities.size == 1) preferred.firstOrNull() else null
            // Catalogues sometimes return the original-language title. A full known author
            // occupying one separated edge still establishes order, without translating the
            // release or borrowing that other edition's artwork/description.
            val full = listOfNotNull(name.title, name.author).joinToString(" - ")
            val parts = full.split(Regex("\\s+[-–—:]\\s*|[-–—]\\s+")).filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val authors = list.mapNotNull { row ->
                val writer = author(row)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (words(writer) !in listOf(words(parts.first()), words(parts.last()))) return@mapNotNull null
                withoutAuthor(full, writer)?.let { row to ReleaseName(it, writer) }
            }
            return if (authors.map { words(it.second.author.orEmpty()) }.distinct().size == 1) authors.firstOrNull() else null
        }
        private fun validAsin(value: String?) = value != null && Regex("[A-Z0-9]{10}").matches(value)
        private fun validProduct(product: JsonObject, language: String): Boolean = validAsin(product.string("asin")) && !product.string("title").isNullOrBlank() &&
            product.string("language")?.lowercase() in if (language == "fr") setOf("fr", "french", "français", "francais") else setOf("en", "english")
        private fun authors(product: JsonObject) = product.objects("authors").filterNot { contributor ->
            Regex("(?i)\\b(traduct(?:eur|rice)|translator|narrat(?:or|eur|rice))\\b").containsMatchIn(listOfNotNull(contributor.string("name"), contributor.string("role")).joinToString(" "))
        }.mapNotNull { it.string("name") }.joinToString(", ")
        private fun authors(product: JsonObject, key: String) = (product[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(", ")
        private fun year(value: String?) = value?.take(4)?.toIntOrNull()?.takeIf { it in 1000..9999 }
        private fun volume(value: String) = Regex("(?i)\\b(?:tome|volume|vol|book|livre|season|saison)\\s*(\\d+(?:[.,]\\d+)?)").find(value)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        internal fun cleanTitle(raw: String): String = ReleaseName.parse(raw).title
    }
}
