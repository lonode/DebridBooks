package io.github.lonode.debridbooks.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.session.MediaController
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.audio.AudioDownloadService
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import io.github.lonode.debridbooks.playback.PlaybackAction
import kotlinx.coroutines.*
import java.time.LocalDate

data class ShelfBook(val book: BookEntity, val progress: ProgressEntity?, val personal: BookExtras, val download: AudioDownload?)
@OptIn(ExperimentalLayoutApi::class)
@Composable fun AudioLibraryPanel(controller: MediaController?, connection: Connection?, library: LibraryState,
    onSearch: (String) -> Unit, onPlay: (SearchItem, String, AudioBookmark?) -> Unit,
    opening: LibraryOpening?, onCancelOpening: () -> Unit, tool: String, onTool: (String) -> Unit, canStart: Boolean) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val personal by remember(app) { app.database.audio().observePersonal() }.collectAsState(initial = emptyList())
    val downloads by remember(app) { app.database.audio().observeDownloads() }.collectAsState(initial = emptyList())
    val progress by remember(app) { app.database.audio().observeProgress() }.collectAsState(initial = emptyList())
    val listening by remember(app) { app.database.audio().observeListening() }.collectAsState(initial = emptyList())
    val cacheRevision by app.audioLibraryItems.revision.collectAsState()
    val options = library.preferences.options
    val books = remember(library.books, personal, downloads, progress, connection?.accountId, options.librarySort) {
        val byPersonal = personal.associateBy { it.accountId to it.itemId }; val byDownload = downloads.associateBy { it.accountId to it.itemId }
        val byProgress = progress.associateBy { it.accountId to it.itemId }
        library.books.filter { connection == null || it.accountId == connection.accountId }.map {
            val key = it.accountId to it.itemId; ShelfBook(it, byProgress[key], byPersonal[key].extras(), byDownload[key])
        }.let { list -> when (options.librarySort) { "title" -> list.sortedBy { it.book.title.lowercase() }; "author" -> list.sortedBy { it.book.author.orEmpty().lowercase() }; else -> list.sortedByDescending { it.progress?.updatedAt ?: it.book.addedAt } } }
    }
    val scope = rememberCoroutineScope()
    val modal = tool
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Pair<BookEntity, SearchItem>?>(null) }
    var remove by remember { mutableStateOf<ShelfBook?>(null) }
    fun action(body: suspend () -> Unit) { scope.launch {
        try { body(); error = "" } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = "Could not complete library action. Please retry." }
    } }
    fun settings(change: (AppOptions) -> AppOptions) = action { app.preferences.setOptions(change) }
    var sourceJob by remember { mutableStateOf<Job?>(null) }
    var sourceLoading by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sourceBookmark by remember { mutableStateOf<String?>(null) }
    var rowError by remember { mutableStateOf<Pair<Pair<String, String>, String>?>(null) }
    fun open(book: ShelfBook, mark: AudioBookmark? = null) {
        if (sourceLoading != null || opening?.busy == true) return
        sourceBookmark = mark?.id
        sourceLoading = book.book.accountId to book.book.itemId
        rowError = null
        sourceJob = scope.launch {
            try { onPlay(app.audio.source(book.book.accountId, book.book.itemId), book.book.accountId, mark) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { rowError = (book.book.accountId to book.book.itemId) to "This saved source could not be opened. Try again or find another source." }
            finally { sourceLoading = null }
        }
    }
    var filter by rememberSaveable { mutableStateOf("all") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var viewOptions by rememberSaveable { mutableStateOf(false) }
    val hero = continueBook(books)
    val shownBooks = shelfBooks(books, filter)
    val pageCount = ((shownBooks.size + 11) / 12).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val shown = shownBooks.drop(currentPage * 12).take(12)
    val volume: @Composable (ShelfBook, Boolean, Boolean, Modifier) -> Unit = { row, featured, grid, modifier ->
        key(row.book.accountId, row.book.itemId, featured) {
            val source by produceState(app.audioLibraryItems.cached(row.book), row.book, cacheRevision) { value = app.audioLibraryItems.item(row.book) }
            ShelfVolume(row, source, opening, sourceLoading == (row.book.accountId to row.book.itemId),
                canPlay = canStart && controller != null && source?.item != null && sourceLoading == null && opening?.busy != true &&
                    (row.download?.state == "COMPLETE" || connection?.accountId == row.book.accountId),
                onPlay = { open(row) }, onCancel = { sourceJob?.cancel(); sourceLoading = null; onCancelOpening() },
                onDetails = { source?.item?.let { selected = row.book to it } },
                onFind = { onSearch(row.book.title) },
                localError = rowError?.takeIf { it.first == (row.book.accountId to row.book.itemId) }?.second,
                hero = featured, grid = grid, modifier = modifier)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (hero != null) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ShelfHeading("Continue listening", "A little time for a good story.")
                volume(hero, true, false, Modifier)
            }
        } else if (books.isNotEmpty()) {
            ShelfHeading("Your next chapter", "Choose something from your shelf.")
        }
        if (books.isEmpty() && !library.loading) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    LibraryCover(null,"Stories",Modifier.size(100.dp,140.dp))
                    Text("Make room for a good story",fontFamily=FontFamily.Serif,fontSize=25.sp)
                    Text("Find an audiobook and save it here. Your books and your place will be waiting.",style=MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onSearch("") }) { Text("Find an audiobook") }
                }
            }
        } else if (books.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShelfHeading("On your shelf", "${books.size} ${if(books.size==1) "book" else "books"}") {
                IconButton(onClick = { viewOptions = true }, modifier=Modifier.semantics { contentDescription="Sort and view" }) { LibraryIcon(LibrarySymbol.Tune) }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "progress" to "In progress", "saved" to "Saved", "offline" to "Offline", "finished" to "Finished").forEach { (value,label) ->
                    FilterChip(filter == value, { filter=value;page=0 }, label={Text(label)})
                }
            }
            if(shown.isEmpty()) Text(when(filter) {
                "offline" -> "Downloaded books will appear here, ready for listening without a connection."
                "saved" -> "Save a book from its details to keep it for later."
                "finished" -> "The stories you finish will appear here."
                else -> "Start a book to see it here."
            },style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(options.libraryGrid) shown.chunked(2).forEach { pair ->
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    pair.forEach { volume(it,false,true,Modifier.weight(1f)) }
                    if(pair.size==1) Spacer(Modifier.weight(1f))
                }
            } else shown.forEach { volume(it,false,false,Modifier) }
            if(pageCount>1) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                TextButton(enabled=currentPage>0,onClick={page=currentPage-1}){Text("Previous")}
                Text("${currentPage+1} / $pageCount",style=MaterialTheme.typography.labelMedium)
                TextButton(enabled=currentPage+1<pageCount,onClick={page=currentPage+1}){Text("Next")}
            }
        }
        if(books.isNotEmpty()) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            LibraryRecommendations(connection, books, onSearch)
            books.mapNotNull { row -> row.personal.discovery?.series?.next?.takeIf { !it.upcoming && row.progress?.completed == true } }
                .distinctBy { it.title }.take(4).forEach { next ->
                    TextButton(onClick={onSearch(next.search ?: next.title)}) { Text("Continue the series · ${next.title}") }
                }
            val news = seriesNews(books.filter { it.inProgress() || it.progress?.completed==true }.mapNotNull { it.personal.discovery?.series },LocalDate.now())
            if(news.isNotEmpty()) {
                ShelfHeading("New in your series")
                news.forEach { (series,book) -> TextButton(enabled=!book.upcoming,onClick={onSearch(book.search ?: book.title)}) {
                    Text("$series · ${book.title}" + if(book.upcoming) " · Coming ${book.released}" else " · Released ${book.released}")
                } }
            }
        }
        if (error.isNotEmpty()) Text(error, color=MaterialTheme.colorScheme.error)
    }
    if(viewOptions) AlertDialog(onDismissRequest={viewOptions=false},title={Text("Your shelf")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Sort books",style=MaterialTheme.typography.titleSmall)
            listOf("recent" to "Recently opened", "title" to "Title", "author" to "Author").forEach { (value,label) ->
                Row(Modifier.fillMaxWidth().selectable(selected=options.librarySort==value,onClick={settings{it.copy(librarySort=value)}},role=androidx.compose.ui.semantics.Role.RadioButton).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=options.librarySort==value,onClick=null);Spacer(Modifier.width(12.dp));Text(label)
                }
            }
            HorizontalDivider()
            Text("Display",style=MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!options.libraryGrid,{settings{it.copy(libraryGrid=false)}},label={Text("List")},leadingIcon={LibraryIcon(LibrarySymbol.List)})
                FilterChip(options.libraryGrid,{settings{it.copy(libraryGrid=true)}},label={Text("Covers")},leadingIcon={LibraryIcon(LibrarySymbol.Grid)})
            }
        }
    },confirmButton={TextButton(onClick={viewOptions=false}){Text("Done")}})
    selected?.let { (book, item) -> AudioBookDialog(connection?.takeIf { it.accountId == book.accountId }, item, false, error,
        canStart && controller != null, onClose = { selected = null }, onPlay = { onPlay(item, book.accountId, null); selected = null }, account = book.accountId, onSearch = { selected = null; onSearch(it) }, onPlayChapter = { onPlay(item, book.accountId, it); selected = null }) }
    if (modal.isNotEmpty()) Dialog(onDismissRequest = { onTool("") }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick={onTool("")}, modifier=Modifier.semantics { contentDescription="Back to library" }) { LibraryIcon(LibrarySymbol.Back) }
                    Text(when(modal) { "bookmarks" -> "Bookmarks"; "stats" -> "Your listening"; else -> "Downloads" },
                        fontFamily=FontFamily.Serif,fontSize=26.sp,modifier=Modifier.weight(1f))
                }
                if (modal == "stats") {
                    val entries=listening.filter { entry -> books.any { it.book.accountId==entry.accountId && it.book.itemId==entry.itemId } }
                    ListeningOverview(entries,books,options.dailyGoal) { minutes -> settings { it.copy(dailyGoal=minutes) } }
                } else if (modal == "bookmarks") {
                    val marks = books.flatMap { book -> book.personal.bookmarks.map { book to it } }.sortedByDescending { it.second.createdAt }
                    TextButton(enabled = marks.isNotEmpty(), onClick = {
                        val text = marks.joinToString("\n\n") { (book, mark) -> "${book.book.title} — ${mark.title} (${mark.positionMs / 1000}s)\n${mark.note}" }.take(500_000)
                        app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share bookmarks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Share as text") }
                    if (marks.isEmpty()) {
                        ShelfHeading("Keep the moments that matter")
                        Text("Add a bookmark from the Player to save a place and a note.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(marks, key = { it.first.book.accountId + it.first.book.itemId + it.second.id }) { (book, mark) ->
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(book.book.title, style = MaterialTheme.typography.titleSmall)
                                Text("${mark.title} · ${playbackTime(mark.positionMs)}",style=MaterialTheme.typography.bodySmall)
                                if (mark.note.isNotBlank()) Text(mark.note)
                                val openingMark=opening?.takeIf { it.account==book.book.accountId && it.item==book.book.itemId && it.bookmarkId==mark.id }
                                val fetching=sourceLoading==(book.book.accountId to book.book.itemId) && sourceBookmark==mark.id
                                LibraryListenAction("Listen here",openingMark?.busy==true || fetching,
                                    canStart && controller!=null && opening?.busy!=true && sourceLoading==null,
                                    openingMark?.message ?: rowError?.takeIf { it.first==(book.book.accountId to book.book.itemId) }?.second,
                                    onPlay={open(book,mark)},onCancel={sourceJob?.cancel();sourceLoading=null;onCancelOpening()},
                                    preparation=openingMark?.progress,startedAtNanos=openingMark?.startedAtNanos)
                                Row {
                                    TextButton(onClick = { action { app.database.audio().updatePersonal(book.book.accountId, book.book.itemId) { it.copy(bookmarks = it.bookmarks.filterNot { savedMark -> savedMark.id == mark.id }) } } }) { Text("Remove") }
                                }
                            } }
                        }
                    }
                } else {
                    val rows = books.filter { it.download != null }.sortedByDescending { it.download!!.bytes }
                    if (rows.isEmpty()) {
                        ShelfHeading("Take a story with you")
                        Text("Download a book from its details to listen without a connection.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { it.book.accountId + it.book.itemId }) { row ->
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(row.book.title, style = MaterialTheme.typography.titleSmall)
                                Text("${row.download!!.state.lowercase().replaceFirstChar { it.uppercase() }} · ${fileSizeLabel(row.download.bytes)}",style=MaterialTheme.typography.labelMedium)
                                if(row.download.state=="RUNNING") {
                                    val total=row.download.total
                                    if(total!=null && total>0) LinearProgressIndicator(progress={(row.download.bytes.toFloat()/total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                row.download.status?.let { Text(it) }
                                Row {
                                    if (row.download.state in setOf("QUEUED", "RUNNING")) TextButton(onClick = {
                                        app.startService(Intent(app, AudioDownloadService::class.java).setAction("pause").putExtra("account", row.book.accountId).putExtra("item", row.book.itemId))
                                    }) { Text("Pause") }
                                    else if (row.download.state != "COMPLETE") TextButton(onClick = { action {
                                        app.audio.queue(row.book.accountId, app.audio.source(row.book.accountId, row.book.itemId))
                                        app.startForegroundService(Intent(app, AudioDownloadService::class.java))
                                    } }) { Text("Retry") }
                                    TextButton(onClick = { remove = row }) { Text("Remove files") }
                                }
                            } }
                        }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    remove?.let { row -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove offline files?") },
        text = { Text("The book and saved place stay in your library. Offline playback of this book will stop.") },
        confirmButton = { TextButton(onClick = { remove = null; action {
            val extras = controller?.currentMediaItem?.mediaMetadata?.extras
            if (extras?.getString("account") == row.book.accountId && extras.getString("item") == row.book.itemId) {
                app.playbackRuntime.send(PlaybackAction.Close)
            }
            app.startService(Intent(app, AudioDownloadService::class.java).setAction("pause").putExtra("account", row.book.accountId).putExtra("item", row.book.itemId))
            app.audio.delete(row.book.accountId, row.book.itemId)
        } }) { Text("Remove files") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}
