package io.github.lonode.debridbooks.network.standalone

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.ApiFailure
import java.io.StringReader
import java.text.Normalizer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class TorrentResult(val name: String, val hash: String? = null, val magnet: String? = null, val torrentUrl: String? = null,
    val size: Long = 0, val seeders: Int = 0, val tracker: String, val language: String? = null, val format: String? = null,
    val abbPage: String? = null, val origin: String? = null, val detail1337x: String? = null) {
    fun fromSource(source: String) = TorrentResult(name, hash, magnet, torrentUrl, size, seeders, tracker, language, format, abbPage, source, detail1337x)
}

class TorrentSearch internal constructor(private val config: StandaloneConfig, private val http: DirectTransport = DirectHttp,
    private val abbAccess: AbbAccess = AbbRuntime.forTransport(http)) {
    suspend fun validateSources() {
        config.trackers().forEach { tracker -> tracker(tracker, "DebridBooks", 1, false) }
        if (config.jackettUrl.isNotBlank()) jackett("DebridBooks", false)
    }
    suspend fun search(query: String, page: Int, comic: Boolean, onlySources: Set<String> = emptySet(), forceAbb: Boolean = false, onProgress: suspend (SearchProgress) -> Unit = {}): SearchPage = coroutineScope {
        val events = Channel<SourceResult>(Channel.UNLIMITED)
        val requests = config.trackers().map { source -> source.name to suspend { tracker(source, query, page, comic) } }.toMutableList()
        if (config.publicSourcesAllowed) {
            if (config.knabenEnabled) requests += "Knaben" to suspend { publicPage("Knaben", query, page, comic) }
            requests += "API Bay" to suspend { publicPage("API Bay", query, page, comic) }
            requests += "1337x" to suspend { publicPage("1337x", query, page, comic) { partial ->
                events.send(SourceResult("1337x", partial.copy(results = partial.results.map { it.fromSource("1337x") }), pending = true))
            } }
        }
        if (config.jackettUrl.isNotBlank() && page == 1) requests += "Jackett" to suspend { jackett(query, comic) }
        if (config.abbDomain.isNotBlank() && !comic) requests += "AudiobookBay" to suspend { abb(query, page, forceAbb) }
        requests.removeAll { onlySources.isNotEmpty() && it.first !in onlySources }
        if (requests.isEmpty()) {
            if (config.jackettUrl.isNotBlank() && page > 1) return@coroutineScope SearchPage(emptyList())
            throw ApiFailure(503, "NO_SEARCH_SOURCES")
        }
        val statuses = requests.map { SearchSourceProgress(it.first) }.toMutableList()
        statuses += SearchSourceProgress("AllDebrid")
        val settled = linkedMapOf<String, SourcePage>()
        val warnings = linkedMapOf<String, SearchWarning>()
        var hashes: Set<String> = emptySet()
        fun snapshot() = buildPage(requests.flatMap { settled[it.first]?.results.orEmpty() }, hashes, warnings.values.toList(), comic)
        onProgress(SearchProgress(snapshot(), statuses.toList()))
        try {
            requests.forEach { (name, block) -> launch {
                val result = try { SourceResult(name, block().let { it.copy(results = it.results.map { result -> result.fromSource(name) }) }) }
                catch (cancel: CancellationException) { this@coroutineScope.cancel(cancel); throw cancel }
                catch (failure: Exception) { SourceResult(name, issue = (failure as? DirectFailure)?.reason ?: "SOURCE_UNAVAILABLE", retryAt = (failure as? DirectFailure)?.retryAt) }
                events.send(result)
            } }
            launch {
                val result = try { SourceResult("AllDebrid", hashes = AllDebridClient(config, http).readyHashes()) }
                catch (cancel: CancellationException) { this@coroutineScope.cancel(cancel); throw cancel }
                catch (failure: Exception) { SourceResult("AllDebrid", issue = (failure as? DirectFailure)?.reason ?: "SOURCE_UNAVAILABLE", retryAt = (failure as? DirectFailure)?.retryAt) }
                events.send(result)
            }
            var remaining = requests.size + 1
            while (remaining > 0) {
                val event = events.receive()
                if (!event.pending) remaining--
                event.items?.let { settled[event.name] = it }
                event.hashes?.let { hashes = it }
                val issue = event.issue ?: event.items?.takeIf { it.partial }?.issue
                settled.forEach { (name, items) ->
                    val existing = statuses.indexOfFirst { it.source == name }
                    statuses[existing] = statuses[existing].copy(count = buildPage(items.results, hashes, emptyList(), comic).results.size)
                }
                if (issue != null) warnings[event.name] = SearchWarning(event.name, issue, "Source unavailable or incomplete.")
                val position = statuses.indexOfFirst { it.source == event.name }
                statuses[position] = SearchSourceProgress(event.name,
                    if (event.pending) SearchSourceState.Pending else if (event.items?.partial == true || issue != null && settled[event.name]?.results?.isNotEmpty() == true) SearchSourceState.Partial else if (issue != null) SearchSourceState.Failed else SearchSourceState.Complete,
                    settled[event.name]?.let { buildPage(it.results, hashes, emptyList(), comic).results.size } ?: 0, issue, event.retryAt ?: event.items?.retryAt, event.items?.cached ?: false)
                onProgress(SearchProgress(snapshot(), statuses.toList(), finished = remaining == 0 && settled.isNotEmpty()))
            }
        } finally { events.cancel() }
        if (settled.isEmpty()) throw ApiFailure(503, "SEARCH_UNAVAILABLE", warnings.values.toList())
        snapshot()
    }
    private fun buildPage(items: List<TorrentResult>, hashes: Set<String>, warnings: List<SearchWarning>, comic: Boolean): SearchPage {
        val results = items.filter { config.languageFilter.isEmpty() || language(it) == config.languageFilter }
            .filter { config.maxSizeGB == null || it.size <= 0 || it.size <= config.maxSizeGB * 1_073_741_824 }
            .distinctBy { it.hash?.lowercase() ?: it.detail1337x ?: it.abbPage ?: it.torrentUrl ?: it.magnet ?: it.name }
            .sortedWith(compareByDescending<TorrentResult> { it.hash?.lowercase() in hashes }
                .thenByDescending { config.preferredFormat.isNotEmpty() && format(it).equals(config.preferredFormat, true) }
                .thenByDescending { quality(it, comic) }.thenByDescending { it.seeders })
            .take(1000).map { result ->
                val format = format(result)
                val payload = if (result.abbPage != null) ResolverItem(n = result.name, a = result.abbPage) else ResolverItem(result.hash, result.magnet, result.torrentUrl, result.name, format, s = result.size.takeIf { it > 0 }, t = if (comic) "c" else null, a = result.abbPage, x = result.detail1337x)
                SearchItem(payload.encode(), result.name, type = if (comic) "comic" else "audiobook", format = format,
                    sizeText = result.size.takeIf { it > 0 }?.let { io.github.lonode.debridbooks.network.fileSizeLabel(it) },
                    language = language(result), tracker = result.tracker, releaseName = result.name, cached = result.hash?.lowercase() in hashes, searchSource = result.origin ?: result.tracker, infoHash = result.hash)
            }
        return SearchPage(results, partial = warnings.isNotEmpty(), warnings = warnings)
    }
    private class SourceResult(val name: String, val items: SourcePage? = null, val hashes: Set<String>? = null, val issue: String? = null, val retryAt: Long? = null, val pending: Boolean = false)
    private data class SourcePage(val results: List<TorrentResult>, val partial: Boolean = false, val issue: String = "SOURCE_UNAVAILABLE", val retryAt: Long? = null, val cached: Boolean = false)
    private suspend fun publicPage(source: String, query: String, page: Int, comic: Boolean, onPartial: suspend (SourcePage) -> Unit = {}): SourcePage {
        val result = PublicTorrentRuntime.forTransport(http).search(source, query, page, comic) { partial ->
            onPartial(SourcePage(partial.results, cached = partial.cached))
        }
        return SourcePage(result.results, result.cooldown != null, "COOLDOWN", result.cooldown, result.cached)
    }
    private suspend fun tracker(source: Tracker, query: String, page: Int, comic: Boolean): SourcePage {
        val params = listOf("apikey" to source.key, "t" to "search", "q" to query, "cat" to if (comic) source.comicCategory else source.audioCategory,
            "limit" to "40", "offset" to ((page.toLong() - 1) * 40).toString())
        val gate = synchronized(trackerGates) { trackerGates.getOrPut(source.name) { RequestGate(source.delayMs) } }
        return gate.run {
            val response = http.execute(DirectRequest("${source.url}?${parameters(params)}", mapOf("Accept" to "application/rss+xml, application/xml, application/json"))).requireSuccess(source.name)
            SourcePage(parseTorznab(response.text(), source.name))
        }
    }
    private suspend fun jackett(query: String, comic: Boolean): SourcePage {
        val params = listOf("apikey" to config.jackettApiKey, "Query" to query) + if (comic) listOf("Category[]" to "7030") else emptyList()
        val root = http.execute(DirectRequest("${config.jackettUrl.trimEnd('/')}/api/v2.0/indexers/all/results?${parameters(params)}")).requireSuccess("Jackett").json()
        require(root["Results"] is JsonArray)
        return SourcePage(root.objects("Results").take(1000).mapNotNull { row ->
            result(row.string("Title"), row.string("InfoHash"), row.string("MagnetUri"), row.string("Link"), row.string("Size"), row.string("Seeders"),
                row.string("Tracker") ?: "Jackett", row.string("Language"))
        })
    }
    private suspend fun abb(query: String, page: Int, force: Boolean): SourcePage {
        val base = "https://${config.abbDomain}"
        val document = abbAccess.get("$base${if (page > 1) "/page/$page" else ""}/?s=${encoded(query)}", force = force)
        val html = document.html
        val anchors = Regex("""(?is)<a\b[^>]*href=["'](/(?:abss|audio-books)/[^"']+)["'][^>]*>(.*?)</a>""").findAll(html).toList()
        val bookmarks = anchors.filter { Regex("""(?i)\brel=["']bookmark["']""").containsMatchIn(it.value) }
        val titles = bookmarks.ifEmpty { anchors }.filter { plainText(it.groupValues[2]).isNotBlank() }.distinctBy { it.groupValues[1] }.take(12)
        val results = titles.mapIndexed { index, anchor ->
            val url = base + anchor.groupValues[1]
            val title = plainText(anchor.groupValues[2])
            val end = titles.getOrNull(index + 1)?.range?.first ?: html.length
            val summary = html.substring(anchor.range.first, end)
            val detail = abbAccess.peek(url)
            val known = detail?.let { parseAbbDetail(title, it) }
            TorrentResult(title, known?.hash, known?.magnet, size = known?.size ?: abbSize(summary), tracker = "AudiobookBay",
                language = known?.language ?: field(summary, "Language"), format = known?.format ?: abbFormat(summary), abbPage = url)
        }
        return SourcePage(results, document.cooldownUntil != null, "COOLDOWN", document.cooldownUntil, document.cached)
    }
    /** Only explicit file resolution fetches a selected ABB detail page. Existing hash IDs are unchanged. */
    suspend fun resolveAbb(item: ResolverItem): ResolverItem {
        val page = item.a ?: return item
        val uri = java.net.URI(page)
        require(uri.host.equals(config.abbDomain.ifBlank { StandaloneConfig.DEFAULT_ABB_DOMAIN }, true))
        require(uri.scheme == "https" && uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.startsWith("/abss/") || uri.path.startsWith("/audio-books/"))
        val text = abbAccess.get(page, detail = true).html
        val result = parseAbbDetail(item.n, text) ?: throw DirectFailure("AudiobookBay", "INVALID_RESPONSE")
        return ResolverItem(result.hash, result.magnet, n = item.n, f = result.format ?: item.f, b = item.b, s = result.size.takeIf { it > 0 } ?: item.s, t = item.t)
    }
    companion object {
        private val trackerGates = mutableMapOf<String, RequestGate>()
        internal fun parseAbbDetail(title: String, text: String): TorrentResult? {
            val hash = Regex("(?i)info\\s*hash[\\s\\S]{0,400}?([a-f0-9]{40})").find(text)?.groupValues?.get(1)
                ?: Regex("(?i)xt=urn:btih:([a-f0-9]{40})").find(text)?.groupValues?.get(1) ?: return null
            val trackers = Regex("""(?i)(?:udp|https?)://[^\s"'<>]+""").findAll(text).map { it.value }
                .filter { it.startsWith("udp:") || it.contains("announce", true) }.distinct().take(30).toList()
            val magnet = "magnet:?xt=urn:btih:${hash.lowercase()}" + trackers.joinToString("") { "&tr=${encoded(it)}" }
            return TorrentResult(title, hash.lowercase(), magnet, size = abbSize(text), tracker = "AudiobookBay", language = field(text,"Language"), format = abbFormat(text))
        }
        private fun abbFormat(text: String): String? = Regex("(?i)\\b(mp3|m4b|m4a|flac|aac|ogg|opus|wav)\\b")
            .find(field(text, "Format").orEmpty())?.value?.uppercase(java.util.Locale.ROOT)
        private fun abbSize(text: String): Long {
            val value = field(text, "Combined File Size") ?: field(text, "File Size") ?: return 0
            val match = Regex("(?i)([0-9.,]+)\\s*(KB|MB|GB|TB|B)s?\\b").find(value) ?: return 0
            val factor = when(match.groupValues[2].uppercase()) { "KB" -> 1024.0; "MB" -> 1048576.0; "GB" -> 1073741824.0; "TB" -> 1099511627776.0; else -> 1.0 }
            return ((match.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0) * factor).toLong()
        }
        internal fun quality(result: TorrentResult, comic: Boolean): Int {
            val format = format(result)?.lowercase()
            val container = if (comic) when (format) { "cbz" -> 400; "cb7", "cbt" -> 250; "pdf" -> 150; "cbr" -> 50; else -> 0 }
                else when (format) { "m4b" -> 400; "flac", "m4a", "aac" -> 200; "mp3" -> 100; else -> 0 }
            val size = minOf(result.size.coerceAtLeast(0) / 1_048_576.0, if (comic) 4000.0 else 2000.0) * 0.1
            return kotlin.math.round(container + size).toInt()
        }
        fun plainText(value: String): String = value.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>|</(?:p|div|li)>"), "\n\n").replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { entity ->
                val raw = entity.groupValues[1]
                val code = if (raw.startsWith("x", true)) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
                code?.takeIf { it in 1..0x10ffff && it !in 0xd800..0xdfff }?.let { String(Character.toChars(it)) }.orEmpty()
            }.let { text ->
                mapOf("eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à", "acirc" to "â", "ccedil" to "ç", "rsquo" to "’", "lsquo" to "‘", "ndash" to "–", "mdash" to "—", "hellip" to "…")
                    .entries.fold(text) { value, (entity, decoded) -> value.replace("&$entity;", decoded) }
            }.trim().take(4000)
        private fun field(text: String, name: String): String? {
            val property = when (name) { "Language" -> "inLanguage"; "Format" -> "encodingFormat"; else -> null }
            if (property != null) {
                Regex("""(?is)<([a-z][a-z0-9]*)\b[^>]*\bitemprop\s*=\s*["']$property["'][^>]*>(.*?)</\1\s*>""")
                    .find(text)?.groupValues?.get(2)?.let(::plainText)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            for (row in Regex("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>").findAll(text)) {
                val cells = Regex("(?is)<(td|th)\\b[^>]*>(.*?)</\\1\\s*>").findAll(row.groupValues[1])
                    .map { plainText(it.groupValues[2]) }.take(2).toList()
                if (cells.size == 2 && cells[0].trim().removeSuffix(":").trim().equals(name, true)) {
                    return cells[1].takeIf { it.isNotBlank() }
                }
            }
            // A colon distinguishes visible labels from Content-Language and script attributes.
            val value = Regex("(?is)(?<![a-z-])${Regex.escape(name)}\\s*:\\s*(?:<[^>]+>\\s*)*([^\\s<][^<\\r\\n]{0,119})")
                .find(text)?.groupValues?.get(1) ?: return null
            val cleaned = plainText(value)
            val nextLabel = Regex("(?i)\\s+(?:Language|Format|Bitrate|Keywords):").find(cleaned)?.range?.first ?: cleaned.length
            return cleaned.take(nextLabel).trim().takeIf { it.isNotBlank() }
        }
        fun format(result: TorrentResult): String? = result.format ?: Regex("(?i)\\b(mp3|m4b|m4a|flac|aac|ogg|opus|wav|cbz|cbr|cb7|cbt|pdf)\\b").find(result.name)?.value?.uppercase()
        fun language(result: TorrentResult): String? {
            val declared = result.language?.lowercase()?.trim().orEmpty()
            if (declared.isNotEmpty()) return when {
                Regex("(?:fr|fra|fre|french|français|francais)(?:[-_].*)?").matches(declared) -> "fr"
                Regex("(?:en|eng|english|anglais)(?:[-_].*)?").matches(declared) -> "en"
                Regex("[a-z]{2}(?:[-_][a-z]{2})?").matches(declared) -> declared.take(2)
                else -> null
            }
            val name = Normalizer.normalize(result.name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            if (Regex("(?i)(?:^|[^a-z])(?:multi|multilingual)(?:$|[^a-z])").containsMatchIn(name)) return null
            val french = Regex("(?i)(?:^|[^a-z])(?:french|francais|vff|vfq|vf|vfi)(?:$|[^a-z])|\\[FR]|\\(FR\\)|(?:^|[._ -])FR$").containsMatchIn(name)
            val english = Regex("(?i)(?:^|[^a-z])(?:english|anglais|eng)(?:$|[^a-z])|\\[EN]|\\(EN\\)|(?:^|[._ -])EN$").containsMatchIn(name)
            return when { french && english -> null; french -> "fr"; english -> "en"; result.tracker.lowercase() in setOf("c411", "tr4ker", "yggreborn", "v3x") -> "fr"; else -> null }
        }
        private fun result(title: String?, hash: String?, magnet: String?, link: String?, size: String?, seeds: String?, tracker: String, language: String?): TorrentResult? {
            val m = magnet ?: link?.takeIf { it.startsWith("magnet:?") }
            val h = (hash ?: m?.let { Regex("(?i)btih:([a-f0-9]{40})").find(it)?.groupValues?.get(1) })?.takeIf { Regex("[a-fA-F0-9]{40}").matches(it) }?.lowercase()
            val url = link?.takeIf { it.startsWith("https://") }
            if (title.isNullOrBlank() || title.length > 2000 || h == null && m == null && url == null) return null
            return TorrentResult(title, h, m, url, size?.toLongOrNull()?.coerceAtLeast(0) ?: 0, seeds?.toIntOrNull()?.coerceAtLeast(0) ?: 0, tracker, language)
        }
        fun parseTorznab(body: String, tracker: String): List<TorrentResult> {
            if (body.trimStart().startsWith("{")) {
                val root = Json.parseToJsonElement(body).jsonObject
                require(root["error"] == null)
                val channel = (root["rss"] as? JsonObject)?.get("channel") as? JsonObject ?: root["channel"] as? JsonObject ?: error("Invalid feed")
                return channel.objects("item").take(1000).mapNotNull { item ->
                    val attrs = (item.objects("torznab:attr") + item.objects("newznab:attr")).associate { raw ->
                        val row = raw["@attributes"] as? JsonObject ?: raw
                        row.string("name").orEmpty() to row.string("value").orEmpty()
                    }
                    val enclosure = (item["enclosure"] as? JsonObject)?.let { it["@attributes"] as? JsonObject ?: it }
                    fun scalar(key: String) = item.string(key) ?: (item[key] as? JsonObject)?.string("#text")
                    result(scalar("title"), attrs["infohash"] ?: scalar("guid"), attrs["magneturl"], enclosure?.string("url") ?: scalar("link"),
                        scalar("size") ?: attrs["size"] ?: enclosure?.string("length"), attrs["seeders"], tracker, attrs["language"])
                }
            }
            require(!Regex("(?i)<!DOCTYPE|<!ENTITY").containsMatchIn(body)) { "Invalid feed" }
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(StringReader(body))
            val out = mutableListOf<TorrentResult>()
            var inChannel = false; var seenChannel = false; var inItem = false
            var values = mutableMapOf<String, String>(); var attrs = mutableMapOf<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 1) require(parser.name in setOf("rss", "channel", "error"))
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "error" -> throw DirectFailure(tracker, "AUTH")
                        "channel" -> { inChannel = true; seenChannel = true }
                        "item" -> { require(inChannel && !inItem && out.size < 1000); inItem = true; values = mutableMapOf(); attrs = mutableMapOf() }
                        "attr" -> if (inItem) attrs[parser.getAttributeValue(null, "name").orEmpty()] = parser.getAttributeValue(null, "value").orEmpty()
                        "enclosure" -> if (inItem) { values["url"] = parser.getAttributeValue(null, "url").orEmpty(); values["length"] = parser.getAttributeValue(null, "length").orEmpty() }
                        "title", "link", "guid", "size" -> if (inItem) { val name = parser.name; values[name] = parser.nextText() }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "channel" -> inChannel = false
                        "item" -> { require(inItem); inItem = false
                            result(values["title"], attrs["infohash"] ?: values["guid"], attrs["magneturl"], values["url"]?.takeIf { it.isNotBlank() } ?: values["link"],
                                values["size"] ?: attrs["size"] ?: values["length"], attrs["seeders"], tracker, attrs["language"])?.let(out::add)
                        }
                    }
                }
                parser.next()
            }
            require(seenChannel && !inItem && !inChannel)
            return out
        }
    }
}
