package io.github.lonode.debridbooks.playback

data class ChapterLabel(val title: String, val context: String? = null)

/** Presentation only: file names, media IDs and saved playback positions stay unchanged. */
fun chapterLabel(raw: String, bookTitle: String = "", fallbackNumber: Int? = null): ChapterLabel {
    val fallback = fallbackNumber?.let { "Chapter $it" } ?: "Audio file"
    if (Regex("(?i)^[a-z][a-z0-9+.-]*://").containsMatchIn(raw.trimStart())) return ChapterLabel(fallback)
    val parts = raw.takeLast(8000).replace('\\', '/').split('/').filter { it.isNotBlank() }
    val file = parts.lastOrNull().orEmpty()
    val audioExtension = Regex("(?i)\\.(?:mp3|m4b|m4a|flac|aac|ogg|opus|wav|wma|aiff|alac)$")
    fun words(value: String) = value.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)|_"), " ").replace(Regex("\\s+"), " ").trim()
    val fileTitle = if (audioExtension.containsMatchIn(file) || parts.size > 1 || '_' in file) words(file.replace(audioExtension, "")) else file.trim()
    val title = fileTitle.replace(Regex("(?i)^(?:(?:chapter|chapitre|chap|track|piste|part|partie)\\s*)?0*[0-9]{1,3}(?:\\.[0-9]+)?[\\s:–—-]+(?=\\S)"), "")
        .trim().take(500).ifBlank { fallback }
    var context = parts.dropLast(1).lastOrNull()?.let(::words).orEmpty()
    if (bookTitle.isNotBlank()) context = context.replace(Regex("^${Regex.escape(words(bookTitle))}(?:\\s+|$)", RegexOption.IGNORE_CASE), "")
    context = context.replace(Regex("(?i)\\b(?:tome|t|vol(?:ume)?)\\s*0*([0-9]{1,3})\\b")) { "Tome ${it.groupValues[1].toInt()} ·" }
        .trim(' ', '·', '-').take(250)
    if (context.equals(title, true) || context.lowercase() in setOf("audio", "audiobooks", "mp3", "files", "tracks", "chapters", "downloads")) context = ""
    return ChapterLabel(title, context.takeIf { it.isNotBlank() })
}
