package io.github.lonode.debridbooks.network.standalone

/** Conservative release cleanup; catalogues confirm ambiguous identities. Never rewrites resolver IDs. */
internal data class ReleaseName(val title: String, val author: String?) {
    val query: String get() = listOfNotNull(title, author?.replace(Regex("\\b([A-Z]{2,3})(?=\\s+\\p{Lu}\\p{Ll})")) {
        it.value.toCharArray().joinToString(" ")
    }).joinToString(" ").replace(Regex("\\s+[-–—:]\\s+"), " ").replace(Regex("(?i)\\b(hyper|hypo)[\\s-]+(?=\\p{L}{3})"), "$1")

    companion object {
        fun parse(raw: String, comic: Boolean = false): ReleaseName {
            val source = repairEncoding(raw)
            val protected = mutableListOf<String>()
            var marker = "\uE000"
            while (marker in source) marker += "\uE000"
            var text = source.replace(Regex("(?i)([\\[(][^\\])]*(?:MP3|M4B|M4A|FLAC|AAC|OGG|OPUS)[^\\])]*[\\])])\\s*-[\\p{L}\\d][\\p{L}\\d._-]{0,39}\\s*$"), "$1")
                .replace(Regex("(?i)(\\b(?:FR|EN|FRENCH|ENGLISH|VFF|VFQ|VFI|MP3|M4B|M4A|FLAC|AAC|OGG|OPUS|AUDIOBOOK|EBOOK)\\b[\\s.\\]})]*)-[\\p{L}\\d][\\p{L}\\d._-]{0,39}\\s*$"), "$1")
                .replace(Regex("(?i)[\\[(]\\s*(int[eé]grale|omnibus|collection|(?:tome|volume|vol|t)\\s*\\d+(?:\\.\\d+)?)\\s*[\\])]"), " $1 ")
                .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}")) { group ->
                    val content = group.value.drop(1).dropLast(1).trim()
                    if (isTechnicalGroup(content)) " " else {
                        protected += group.value
                        "$marker${protected.lastIndex}\uE001"
                    }
                }
                .replace(Regex("(?i)(?:^|[\\s._-])NOTAG\\s*$"), " ")
                .replace(Regex("(?i)\\b(MP3|M4B|M4A|FLAC|AAC|OGG|OPUS)\\.(?=\\d)"), "$1 ")
                .replace(Regex("(?i)\\b(MP3|AAC|M4B)[._ -]+(?:32|48|64|96|112|128|160|192|224|256|320)(?=[\\s._-]*$|[\\s._-]+(?:FR|EN|FRENCH|ENGLISH|NOTAG)\\b)"), "$1")
                .replace(Regex("(?i)(?<=\\d)\\.(?=(?:18|19|20)\\d{2}[._\\s-]+(?:FR|EN|FRENCH|ENGLISH|CD|MP3)\\b)"), " ")
                .replace(Regex("(?<!\\d)\\.|\\.(?!\\d)|_"), " ")
                .replace(Regex("(?i)\\b(?:Tome|T|Vol(?:ume)?)\\s*0*(\\d+(?:\\.\\d+)?)\\b"), "Tome $1")
                .replace(Regex("(?i)\\b(?:FR|EN)\\s*$"), " ")
                .replace(Regex("(?i)\\b(?:narrated by|performed by|racont[eé]\\s+par|lect(?:eur|rice)\\s+b[eé]n[eé]vole)\\b.*$"), " ")
                .replace(Regex("(?i)\\b(?:mp3|m4b|m4a|flac|aac|ogg|opus|wav|vbr|cbr)\\b|\\d+(?:\\.\\d+)?\\s?(?:kbps|kpbs|kbs|khz|kb/s|kbits?|kb)"), " ")
                .replace(Regex("\\b(?:ENG|VO|CD|CDRiP|eBOOK|EBOOK|EPUB|MOBI|AZW3|VFF|VFQ|VFI)\\b"), " ")
                .replace(Regex("(?:^|\\s)[.,]+(?=\\s|$)"), " ")
                .replace(Regex("(?i)\\b(?:FR|EN)[\\s-]*$"), " ")
                .replace(Regex("(?i)\\b(?:livres?\\s+audio|lu\\s+par|non\\s+abr[eé]g[eé])\\b.*$"), " ")
            if (comic) text = text.replace(Regex("(?i)\\b(?:cbz|cb7|cbt)\\b"), " ")
            text = text.replace(Regex("${Regex.escape(marker)}(\\d+)\uE001")) { protected[it.groupValues[1].toInt()] }
                .replace(Regex("\\s+"), " ").trim().trimEnd('-', '–', '—', ' ').take(500)
            // A name-shaped suffix or a separator is not proof of an author.
            // Catalogue contributors can establish that identity without losing title words.
            return ReleaseName(text.ifBlank { raw.take(500) }, null)
        }
        // Match the entire bracket content. Unknown subtitles, dates, editions and
        // Roman-numbered volumes are retained, even if that leaves some release noise.
        private const val technicalTokens = "MP3|M4B|M4A|FLAC|AAC|AAX|OGG|OPUS|WAV|VBR|CBR|CBZ|CB7|CBT|PDF|EPUB|MOBI|AZW3|FR|EN|ENG|FRENCH|ENGLISH|VFF|VFQ|VFI|MULTI|CD|CDRIP|RETAIL|UNABRIDGED|ABRIDGED|\\d+(?:[.,]\\d+)?[ ._-]*(?:kbps|kpbs|kbs|khz|kb/s|kbits?|kb)"
        private val technicalGroup = Regex("(?i)(?:(?:$technicalTokens)(?:[ ._/-]+|$))+")
        private val technicalRates = Regex("(?i)(?:(?:$technicalTokens|32|48|64|96|112|128|160|192|224|256|320)(?:[ ._/-]+|$))+")
        private val codecMarker = Regex("(?i)\\b(?:MP3|M4B|M4A|FLAC|AAC|AAX|OGG|OPUS|WAV)\\b")
        private fun isTechnicalGroup(content: String): Boolean {
            val technical = content.replace(Regex("(?i)\\s+à\\s+"), " ")
            return technicalGroup.matches(technical) || codecMarker.containsMatchIn(technical) && technicalRates.matches(technical)
        }

        private fun repairEncoding(raw: String): String = Regex("[\\u00c2-\\u00f4][\\u0080-\\u00bf]{1,3}").replace(raw) { sequence ->
            // Repair only valid UTF-8 byte sequences mistakenly exposed as Latin-1.
            // Strict decoding leaves genuine/malformed text intact; resolver IDs keep raw.
            runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(sequence.value.toByteArray(Charsets.ISO_8859_1))).toString() }.getOrElse { sequence.value }
        }
    }
}
