package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Composed only for an open book page. Disposing it cancels the optional metadata request. */
@Composable
fun BookDetailsPanel(connection: Connection, item: SearchItem) {
    val app = LocalContext.current.applicationContext as DebridApplication
    key(connection.accountId, connection.cacheKey, item.id, item.detailsLanguage()) {
        var details by remember { mutableStateOf<BookDetails?>(null) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(attempt) {
            loading = true; failed = false
            try { details = app.bookDetails.load(connection, item) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
        if (loading) Text("Loading book details…", style = MaterialTheme.typography.bodySmall)
        if (failed) {
            Text("Book details are unavailable. You can still open this book.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { attempt++ }) { Text("Retry details") }
        }
        details?.let { data ->
            if (!data.title.isNullOrBlank() && data.title != item.title) Text(data.title, style = MaterialTheme.typography.titleMedium)
            if (item.author.isNullOrBlank() && !data.author.isNullOrBlank()) Text(data.author)
            if (!data.genre.isNullOrBlank()) Text(data.genre, style = MaterialTheme.typography.bodyMedium)
            data.year?.let { Text("Publication year: $it", style = MaterialTheme.typography.bodySmall) }
        }
        (details?.description ?: item.description)?.takeIf { it.isNotBlank() }?.let { Text(it.take(4000)) }
    }
}

/** Resolving files is an explicit action; it does not hold playback behind optional details. */
@Composable
fun BookFilesPanel(connection: Connection, item: SearchItem, onChapter: ((MediaPart, io.github.lonode.debridbooks.data.AudioChapter) -> Unit)? = null) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val scope = rememberCoroutineScope()
    key(connection.accountId, item.id) {
        var attempt by remember { mutableIntStateOf(0) }
        var result by remember { mutableStateOf<Resolution?>(null) }
        var loading by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(attempt) {
            if (attempt == 0) return@LaunchedEffect
            loading = true; failed = false
            try { result = MobileApi(connection).resolve(item.id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
        TextButton(enabled = !loading, onClick = { attempt++ }) { Text(if (attempt == 0) "Show actual filenames and sizes" else "Refresh files") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text("Files could not be loaded. Please retry.", style = MaterialTheme.typography.bodySmall)
        result?.let { resolution ->
            if (!resolution.ready) Text(resolution.preparationMessage())
            else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(resolution.streams.sizeEstimateLabel(), style = MaterialTheme.typography.bodyMedium)
                resolution.streams.forEach { part -> key(part.id) {
                    ReleaseFilename(part.filename)
                    if (part.filename.isNullOrBlank()) Text(part.title, style = MaterialTheme.typography.bodySmall)
                    Text(part.size?.let(::fileSizeLabel) ?: "Size unknown", style = MaterialTheme.typography.bodySmall)
                    var contents by remember { mutableStateOf<List<io.github.lonode.debridbooks.data.AudioChapter>?>(null) }
                    var loadingContents by remember { mutableStateOf(false) }
                    var visible by remember { mutableIntStateOf(50) }
                    TextButton(enabled = !loadingContents, onClick = { scope.launch {
                        loadingContents = true
                        try { contents = app.chapters.chapters(connection.accountId, MobileContract.localId(item.id), part.id, part.url).orEmpty() }
                        finally { loadingContents = false }
                    } }) { Text(if (loadingContents) "Loading contents…" else "Contents") }
                    contents?.let { list ->
                        if (list.isEmpty()) Text("No embedded chapters available.", style = MaterialTheme.typography.bodySmall)
                        list.take(visible).forEachIndexed { index, chapter ->
                            TextButton(enabled = onChapter != null, onClick = { onChapter?.invoke(part, chapter) }) { Text("${index + 1}. ${chapter.title} · ${chapter.startMs / 1000}s") }
                        }
                        if (list.size > visible) TextButton(onClick = { visible += 50 }) { Text("More chapters") }
                    }
                } }
            }
        }
    }
}


@Composable
fun AudioBookDialog(connection: Connection?, item: SearchItem, busy: Boolean, message: String,
    playbackAvailable: Boolean, onClose: () -> Unit, onPlay: () -> Unit, account: String? = connection?.accountId, onSearch: ((String) -> Unit)? = null, onPlayChapter: ((io.github.lonode.debridbooks.data.AudioBookmark) -> Unit)? = null) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Audiobook details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Button(enabled = !busy && playbackAvailable, onClick = onPlay) { Text("▶ Listen") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotEmpty()) Text(message)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    ReleaseName(item.releaseName)
                    Text(item.displayMetadata().joinToString(" · "))
                    item.poster?.let { CoverArt(it, item.title, Modifier.size(120.dp, 160.dp)) }
                    item.author?.let { Text(it) }
                    if (account != null) BookPersonalActions(account, item)
                    if (connection != null) {
                        BookDetailsPanel(connection, item)
                        BookFilesPanel(connection, item, onChapter = onPlayChapter?.let { play -> { part, chapter ->
                            play(io.github.lonode.debridbooks.data.AudioBookmark("navigation", part.id, chapter.startMs, chapter.title, createdAt = System.currentTimeMillis()))
                        } })
                        if (onSearch != null) DiscoveryPanel(connection, item, onSearch)
                    }
                }
            }
        }
    }
}
