package io.github.lonode.debridbooks.comics

import io.github.lonode.debridbooks.ui.LibraryCover
import io.github.lonode.debridbooks.ui.LibraryIcon
import io.github.lonode.debridbooks.ui.LibrarySymbol
import io.github.lonode.debridbooks.ui.ShelfHeading
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.lonode.debridbooks.network.MediaPart
import io.github.lonode.debridbooks.network.fileLabel
import io.github.lonode.debridbooks.network.sizeEstimateLabel
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.lonode.debridbooks.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.data.ComicEntity
import io.github.lonode.debridbooks.network.Connection
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComicPanel(connection: Connection?, historyOnly: Boolean = false) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val app = context.applicationContext as DebridApplication
    var notificationPermission by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationPermission = it }
    val model: ComicViewModel = viewModel(factory = viewModelFactory { initializer { ComicViewModel(app) } })
    var pendingExport by remember { mutableStateOf<MediaPart?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val part = pendingExport
        pendingExport = null
        if (uri != null && part != null) model.export(part, uri)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val preferences by app.preferences.preferences.collectAsState(initial=io.github.lonode.debridbooks.data.ListeningPreferences())
    val savedHistory by model.readingHistory.collectAsStateWithLifecycle()
    val history = remember(savedHistory, connection?.accountId) {
        savedHistory.filter { connection == null || it.accountId == connection.accountId }
    }
    var openingVolume by remember { mutableStateOf<String?>(null) }
    var readingOptions by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<Pair<ComicEntity, Boolean>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var localSource by rememberSaveable { mutableStateOf(0) }
    val source = if (historyOnly) 2 else localSource
    LaunchedEffect(connection?.accountId) { model.connect(connection) }
    LaunchedEffect(source) { model.source(source) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!historyOnly) Text(stringResource(R.string.comics_manga), style = MaterialTheme.typography.titleLarge)
        if (!notificationPermission && android.os.Build.VERSION.SDK_INT >= 33) TextButton(onClick = { requestNotifications.launch("android.permission.POST_NOTIFICATIONS") }) { Text("Enable download notifications") }
        if (!historyOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(stringResource(R.string.torrent_source), "MangaDex").forEachIndexed { index, label ->
                FilterChip(selected = source == index, onClick = { localSource = index }, label = { Text(label) })
            }
        }
        if (!historyOnly) Text(stringResource(R.string.comic_sources_help), style = MaterialTheme.typography.bodySmall)
        if (source != 2) {
            if (connection == null) Text("Set up API keys in Settings to search. Saved comics remain available in Reading history.") else {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.comic_search)) }, modifier = Modifier.fillMaxWidth())
                if(state.searching) {
                    OutlinedButton(onClick=model::cancelSearch,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Cancel search")}
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Searching…",style=MaterialTheme.typography.bodySmall)
                } else Button(enabled = !state.busy && query.isNotBlank(), onClick = { focusManager.clearFocus(); keyboard?.hide(); model.search(query, source == 1) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.search_comics_manga)) }
                val visibleResults=state.results
                io.github.lonode.debridbooks.ui.SearchFeedback(state.search, state.busy, onRetry = { request -> focusManager.clearFocus(); keyboard?.hide(); model.retrySearch(request) }, visibleCount = visibleResults.size)
                visibleResults.forEach { item -> io.github.lonode.debridbooks.ui.SearchResultCard(item, enabled = !state.busy || state.searching, onClick = { model.select(item) }) }
                io.github.lonode.debridbooks.ui.AutoSearchMore(state.search, state.busy || state.selected != null || state.reader != null, model::retrySearch, visibleCount = visibleResults.size)
                state.selected?.takeIf { state.reader == null }?.let { item ->
                    Dialog(onDismissRequest = model::closeSelection, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.comic_details), style = MaterialTheme.typography.titleLarge)
                                    TextButton(onClick = model::closeSelection) { Text("Close") }
                                }
                                if (state.busy) {
                                    ComicLoadingIndicator(state.loadProgress)
                                    TextButton(onClick = model::cancel) { Text("Cancel") }
                                }
                                if (state.message.isNotEmpty()) Text(state.message)
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                                    item.poster?.let { ComicImage(model, it, "Cover of ${item.title}", Modifier.width(120.dp).height(160.dp), fit = true) }
                                    item.author?.let { Text(it) }
                                    item.releaseName?.takeIf { it != item.title }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (item.provider != "mangadex") io.github.lonode.debridbooks.ui.BookDetailsPanel(connection, item)
                                    listOfNotNull(item.format, item.sizeText).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · ")) }
                                    if (item.provider == "mangadex") item.description?.let { Text(it.take(4000)) }
                                    if (!state.busy) TextButton(onClick = { model.select(item) }) { Text("Refresh comic files") }
                                    if (item.provider == "mangadex") {
                                        MangaDexDataSaver(checked = state.saver, enabled = !state.busy, onCheckedChange = model::saver)
                                        if (!state.busy && state.chapters.isEmpty() && state.message.isEmpty()) Text("No chapters available from this source.")
                                        val readChapters = history.filter { it.completed }.mapNotNull { it.chapterId }.toSet()
                                        state.chapters.forEach { chapter ->
                                            TextButton(enabled = !state.busy, onClick = { model.open(item, chapter) }) {
                                                Text("${if (chapter.id in readChapters) "Read · " else ""}Ch. ${chapter.num ?: "?"} ${chapter.title.orEmpty()}")
                                            }
                                        }
                                        if (state.hasMoreChapters) TextButton(enabled = !state.busy, onClick = model::moreChapters) { Text("More chapters") }
                                    } else {
                                        if (state.streams.isNotEmpty()) Text(state.streams.sizeEstimateLabel(), style = MaterialTheme.typography.bodySmall)
                                        val archives = state.streams.filter { (it.filename ?: it.title).substringAfterLast('.').lowercase() in setOf("cbz", "cbr", "cb7", "cbt", "pdf") }
                                        state.streams.filter { it !in archives }.forEach { Text(it.fileLabel(), style = MaterialTheme.typography.bodySmall) }
                                        if (archives.isNotEmpty()) archives.forEach { archive ->
                                            Text(archive.fileLabel())
                                            if ((archive.filename ?: archive.title).endsWith(".cbz", true)) {
                                                TextButton(enabled = !state.busy, onClick = { model.open(item, streams = listOf(archive)) }) { Text("Read CBZ") }
                                            } else {
                                                Text("This format is not readable in-app. Supported: CBZ and page images.")
                                                TextButton(enabled = !state.busy, onClick = {
                                                    pendingExport = archive
                                                    export.launch((archive.filename ?: archive.title).substringAfterLast('/').take(150))
                                                }) { Text("Save file for another reader") }
                                            }
                                        } else if (state.streams.any { ComicArchive.image(it.filename ?: it.title) }) {
                                            TextButton(enabled = !state.busy, onClick = { model.open(item, streams = state.streams) }) { Text("Read images") }
                                        } else if (!state.busy) Text("No readable comic files yet. Use Refresh comic files to check again.")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            ShelfHeading("Your reading shelf", if(history.isEmpty()) "A new world is a page away." else "${history.size} saved volumes") {
                Box {
                    IconButton(onClick={readingOptions=true},modifier=Modifier.semanticsLabel("Reading options")) { LibraryIcon(LibrarySymbol.More) }
                    DropdownMenu(readingOptions,{readingOptions=false}) {
                        DropdownMenuItem(text={Text("Clear temporary cache")},enabled=!state.busy,onClick={readingOptions=false;openingVolume=null;model.clearCache()})
                    }
                }
            }
            if (history.isEmpty()) Text("Find comics and manga in Search. Open a volume to keep it here with your saved page.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            history.forEach { entry -> key(entry.accountId, entry.volumeId) {
                val presentation by produceState(model.repository.cachedLibraryItem(entry), entry.accountId, entry.volumeId, entry.metadata?.contentHashCode(), entry.offline, connection?.accountId) {
                    value = model.repository.libraryItem(entry)
                    if (entry.metadata == null && connection?.accountId == entry.accountId) {
                        try { model.repository.enrichLibraryItem(connection, entry) }
                        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) { /* Saved page art and reading actions remain available offline. */ }
                    }
                }
                val item = presentation?.item
                val shelfTitle = entry.title.substringBefore(" — ")
                val sourceVolume = entry.title.substringAfter(" — ", "").trim().takeIf { it.isNotBlank() && it != "Ch. ?" }
                val volumeLabel = sourceVolume?.let { filename ->
                    val number = Regex("(?i)(?:^|[ ._-])(?:t|tome|vol(?:ume)?)[ ._-]*(\\d+)").find(filename)?.groupValues?.get(1)?.toIntOrNull()
                    if (number != null) "Volume $number" else filename.replace(Regex("(?i)\\.(?:cbz|zip|cbr)$"), "").replace('_', ' ')
                }
                var menu by remember { mutableStateOf(false) }
                val opening = state.busy && openingVolume==entry.volumeId
                Card(Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                            LibraryCover(item?.poster,shelfTitle,Modifier.size(72.dp,104.dp),presentation?.fallbackCover)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(shelfTitle,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                volumeLabel?.let { Text(it,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(if(entry.completed) "Finished" else "Page ${entry.page+1} of ${entry.pageCount}",style=MaterialTheme.typography.labelMedium)
                                if(entry.offline) Text("Downloaded · Available offline",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                                if(entry.downloadState in listOf("QUEUED","RUNNING","PAUSED","FAILED")) Text(
                                    "${entry.downloadState.lowercase().replaceFirstChar { it.uppercase() }} · ${entry.downloadedPages} / ${entry.pageCount} pages",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if(entry.pageCount>0) LinearProgressIndicator(progress={if(entry.completed) 1f else ((entry.page+1).toFloat()/entry.pageCount).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!state.busy && (entry.offline || connection!=null),onClick={openingVolume=entry.volumeId;model.history(entry)},modifier=Modifier.weight(1f).heightIn(min=48.dp)) {
                                if(opening) { CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp)) }
                                Text(if(opening) "Opening…" else if(entry.completed) "Open book" else "Resume reading")
                            }
                            Box {
                                IconButton(onClick={menu=true},modifier=Modifier.semanticsLabel("Options for ${entry.title}")) { LibraryIcon(LibrarySymbol.More) }
                                DropdownMenu(menu,{menu=false}) {
                                    if(entry.downloadState in listOf("QUEUED","RUNNING")) {
                                        DropdownMenuItem(text={Text("Pause download")},onClick={menu=false;model.pause(entry)})
                                        DropdownMenuItem(text={Text("Cancel download")},onClick={menu=false;model.pause(entry,cancelled=true)})
                                    } else if(!entry.offline && connection!=null) DropdownMenuItem(text={Text("Download for offline reading")},enabled=!state.busy,onClick={menu=false;openingVolume=null;model.download(entry)})
                                    DropdownMenuItem(text={Text("Read from beginning")},enabled=!state.busy && (entry.offline || connection!=null),onClick={menu=false;openingVolume=entry.volumeId;model.history(entry,fromStart=true)})
                                    if(entry.offline) DropdownMenuItem(text={Text("Remove offline pages")},enabled=!state.busy,onClick={menu=false;removal=entry to false})
                                    DropdownMenuItem(text={Text("Remove from history")},enabled=!state.busy,onClick={menu=false;removal=entry to true})
                                }
                            }
                        }
                        if(opening) { ComicLoadingIndicator(state.loadProgress);TextButton(onClick=model::cancel){Text("Cancel opening")} }
                        if(!state.busy && openingVolume==entry.volumeId && state.message.isNotEmpty()) Text(state.message,color=MaterialTheme.colorScheme.error)
                    }
                }
            } }

        }
        if (state.busy && !state.searching && state.selected == null && (!historyOnly || openingVolume==null)) {
            ComicLoadingIndicator(state.loadProgress)
            TextButton(onClick = model::cancel) { Text("Cancel") }
        }
        if (state.message.isNotEmpty() && state.selected == null && (!historyOnly || openingVolume==null)) Text(state.message)
    }
    removal?.let { (entry,historyToo) -> AlertDialog(onDismissRequest={removal=null},title={Text(if(historyToo) "Remove this book?" else "Remove offline pages?")},
        text={Text(if(historyToo) "This removes the book's reading history and downloaded pages." else "Your saved page stays in the library. Reading this volume will require a connection.")},
        confirmButton={TextButton(onClick={removal=null;openingVolume=null;model.delete(entry,history=historyToo)}){Text("Remove")}},dismissButton={TextButton(onClick={removal=null}){Text("Keep")}}) }
    state.reader?.let { session ->
        ComicReader(model, state, session)
    }
}

@Composable
private fun ComicLoadingIndicator(progress: ComicLoadProgress?) {
    val total = progress?.total
    if (progress != null && !progress.extracting && total != null && total > 0) {
        LinearProgressIndicator(progress = { (progress.downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(when {
        progress == null -> "Loading comic files…"
        progress.extracting -> "Preparing comic pages… (${comicMegabytes(progress.downloaded)} MB downloaded)"
        total != null -> "Downloading comic: ${comicMegabytes(progress.downloaded)} / ${comicMegabytes(total)} MB"
        else -> "Downloading comic: ${comicMegabytes(progress.downloaded)} MB"
    })
}

private fun comicMegabytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1_000_000.0)

private fun Modifier.semanticsLabel(label: String): Modifier = this.then(Modifier.semantics { contentDescription = label })

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicReader(model: ComicViewModel, state: ComicState, session: ReadingSession) {
    var continuous by rememberSaveable(session.entry.volumeId) { mutableStateOf(false) }
    var immersive by rememberSaveable(session.entry.volumeId) { mutableStateOf(false) }
    var showImmersiveHelp by rememberSaveable(session.entry.volumeId) { mutableStateOf(false) }
    var helpShownThisSession by rememberSaveable(session.entry.volumeId) { mutableStateOf(false) }
    var doNotRemind by rememberSaveable { mutableStateOf(false) }
    var savingHelp by remember { mutableStateOf(false) }
    var helpSaveError by remember { mutableStateOf(false) }
    var pageGaps by rememberSaveable(session.entry.volumeId) { mutableStateOf(false) }
    var zoomReset by remember { mutableIntStateOf(0) }
    var scrubPage by remember(session.entry.volumeId) { mutableStateOf<Float?>(null) }
    var seekJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val readerScope = rememberCoroutineScope()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.page)
    val nextChapter = state.chapters.indexOfFirst { it.id == session.entry.chapterId }.takeIf { it >= 0 }?.let { state.chapters.getOrNull(it + 1) }
    LaunchedEffect(continuous, session.entry.volumeId) {
        if (continuous) {
            list.scrollToItem(state.page)
            snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect(model::page)
        }
    }
    Dialog(onDismissRequest = { if (immersive) immersive = false else model.closeReader() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ReaderSystemBars(immersive)
        Surface(Modifier.fillMaxSize(), color = if (immersive) Color.Black else MaterialTheme.colorScheme.background) {
            Column(if (immersive) Modifier.fillMaxSize() else Modifier.fillMaxSize().systemBarsPadding().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(if (immersive) 0.dp else 4.dp)) {
                if (!immersive) {
                    Text(session.entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = model::closeReader) { Text("Close") }
                        TextButton(onClick = { continuous = !continuous; zoomReset++ }) { Text(if (continuous) "Paged view" else "Vertical view") }
                        TextButton(enabled = state.immersiveHelpDismissed != null, onClick = {
                            if (state.immersiveHelpDismissed == true || helpShownThisSession) immersive = true
                            else { doNotRemind = false; helpSaveError = false; showImmersiveHelp = true }
                        }) { Text("Immersive view") }
                        TextButton(onClick = { zoomReset++ }) { Text("Reset zoom") }
                        TextButton(enabled = !state.busy, onClick = model::refresh) { Text("Refresh links") }
                        if (!session.entry.offline) TextButton(enabled = !state.busy, onClick = { model.download() }) { Text("Save offline") }
                        if (continuous) FilterChip(selected = pageGaps, onClick = { pageGaps = !pageGaps }, label = { Text("Page spacing") })
                    }
                    if (state.busy) {
                        ComicLoadingIndicator(state.loadProgress)
                        TextButton(onClick = model::cancel) { Text("Cancel operation") }
                    }
                    if (state.message.isNotBlank()) Text(state.message)
                }
                if (continuous) {
                    LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().background(Color.White)) {
                        items(session.pages.size, key = { it }) { index ->
                            ReaderPage(model, session.pages[index], index, paged = false, zoomReset = zoomReset,
                                onControls = { immersive = false }, onNavigate = {}, modifier = Modifier.fillMaxWidth())
                            if (pageGaps && index < session.pages.lastIndex) Spacer(Modifier.fillMaxWidth().height(8.dp))
                        }
                    }
                } else {
                    ReaderPage(model, session.pages[state.page], state.page, paged = true, zoomReset = zoomReset,
                        onControls = { immersive = false }, onNavigate = { if (!state.busy) model.page(state.page + it) },
                        modifier = Modifier.weight(1f).fillMaxWidth())
                }
                if (!immersive) {
                    val displayedPage = scrubPage?.roundToInt() ?: (state.page + 1)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = state.page > 0 && !state.busy, onClick = { continuous = false; model.page(state.page - 1) }) { Text("Previous") }
                        Text("Page $displayedPage / ${session.pages.size}", Modifier.weight(1f), textAlign = TextAlign.Center)
                        TextButton(enabled = state.page < session.pages.lastIndex && !state.busy, onClick = { continuous = false; model.page(state.page + 1) }) { Text("Next") }
                    }
                    Slider(value = scrubPage ?: (state.page + 1).toFloat(),
                        onValueChange = { seekJob?.cancel(); scrubPage = it },
                        onValueChangeFinished = {
                            val target = ((scrubPage?.roundToInt() ?: (state.page + 1)) - 1).coerceIn(0, session.pages.lastIndex)
                            seekJob?.cancel()
                            seekJob = readerScope.launch {
                                if (continuous) list.scrollToItem(target)
                                model.page(target)
                                scrubPage = null
                            }
                        },
                        valueRange = 1f..session.pages.size.coerceAtLeast(2).toFloat(),
                        enabled = !state.busy && session.pages.size > 1,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Reading page"
                            stateDescription = "Page $displayedPage of ${session.pages.size}"
                        })
                    if (state.page == session.pages.lastIndex && nextChapter != null && state.selected != null) {
                        TextButton(enabled = !state.busy, onClick = { model.open(state.selected, nextChapter) }) { Text("Next chapter") }
                    }
                }
            }
        }
    }
    if (showImmersiveHelp) {
        AlertDialog(onDismissRequest = { if (!savingHelp) showImmersiveHelp = false },
            title = { Text(stringResource(R.string.immersive_help_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.immersive_help_message))
                    Row(Modifier.fillMaxWidth().toggleable(value = doNotRemind, enabled = !savingHelp,
                        role = Role.Checkbox, onValueChange = { doNotRemind = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = doNotRemind, onCheckedChange = null)
                        Text(stringResource(R.string.do_not_remind_again), Modifier.padding(start = 8.dp))
                    }
                    if (helpSaveError) Text(stringResource(R.string.immersive_help_save_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !savingHelp, onClick = {
                    if (!doNotRemind) {
                        helpShownThisSession = true; showImmersiveHelp = false; immersive = true
                    } else readerScope.launch {
                        savingHelp = true; helpSaveError = false
                        try {
                            model.rememberImmersiveHelp()
                            helpShownThisSession = true; showImmersiveHelp = false; immersive = true
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) { helpSaveError = true }
                        finally { savingHelp = false }
                    }
                }) { Text(stringResource(R.string.enter_immersive_view)) }
            },
            dismissButton = { TextButton(enabled = !savingHelp, onClick = { showImmersiveHelp = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ReaderSystemBars(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, immersive) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.systemBarsBehavior
        if (immersive) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previous != null) controller?.systemBarsBehavior = previous
        }
    }
}

@Composable
private fun ReaderPage(model: ComicViewModel, source: String, index: Int, paged: Boolean, zoomReset: Int,
    onControls: () -> Unit, onNavigate: (Int) -> Unit, modifier: Modifier) {
    var scale by remember(source, zoomReset) { mutableFloatStateOf(1f) }
    var pan by remember(source, zoomReset) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val latestControls by rememberUpdatedState(onControls)
    val latestNavigate by rememberUpdatedState(onNavigate)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val edge = with(density) { 24.dp.toPx() }
    val left = maxOf(edge, WindowInsets.systemGestures.getLeft(density, direction).toFloat())
    val right = maxOf(edge, WindowInsets.systemGestures.getRight(density, direction).toFloat())
    val bottom = maxOf(edge, WindowInsets.systemGestures.getBottom(density).toFloat())
    val threshold = with(density) { 48.dp.toPx() }
    Box(modifier.clipToBounds().semantics(mergeDescendants = true) {
        stateDescription = "Zoom ${(scale * 100).toInt()}%"
        customActions = listOf(
            CustomAccessibilityAction("Show reader controls") { latestControls(); true },
            CustomAccessibilityAction("Reset zoom") { scale = 1f; pan = androidx.compose.ui.geometry.Offset.Zero; true },
        ) + if (paged) listOf(
            CustomAccessibilityAction("Previous page") { latestNavigate(-1); true },
            CustomAccessibilityAction("Next page") { latestNavigate(1); true },
        ) else emptyList()
    }.pointerInput(source, paged, zoomReset, left, right, bottom) {
        detectReaderGestures(paged, left, right, bottom, threshold,
            scale = { scale }, transform = { zoom, delta ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                if (scale <= 1.01f) { scale = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
                else {
                    val maxX = size.width * (scale - 1) / 2
                    val maxY = size.height * (scale - 1) / 2
                    pan = androidx.compose.ui.geometry.Offset((pan.x + delta.x).coerceIn(-maxX, maxX), (pan.y + delta.y).coerceIn(-maxY, maxY))
                }
            }, tap = { action -> when (action) {
                ReaderTap.Previous -> latestNavigate(-1)
                ReaderTap.Next -> latestNavigate(1)
                ReaderTap.Controls -> latestControls()
                ReaderTap.None -> Unit
            } }, swipe = { latestNavigate(it) })
    }) {
        ComicImage(model, source, "Page ${index + 1}",
            (if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y
            }, fit = paged)
    }
}

@Composable
private fun ComicImage(model: ComicViewModel, source: String, description: String, modifier: Modifier, fit: Boolean = false) {
    var retry by remember(source) { mutableIntStateOf(0) }
    var failed by remember(source, retry) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, source, retry) {
        value = try { model.repository.bitmap(source).asImageBitmap() }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { failed = true; null }
    }
    val bitmap = image
    if (bitmap != null) Image(bitmap, description, modifier, contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth)
    else Column(modifier.padding(16.dp)) {
        if (failed) {
            Text("$description could not be loaded.")
            TextButton(onClick = { retry++; model.refresh() }) { Text("Retry page / refresh links") }
        } else {
            CircularProgressIndicator()
            Text("Loading $description")
        }
    }
}


@Composable
private fun MangaDexDataSaver(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    val label = stringResource(R.string.mangadex_data_saver)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            IconButton(onClick = { showHelp = !showHelp }) {
                Icon(painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(if (showHelp) R.string.hide_data_saver_help else R.string.show_data_saver_help),
                    tint = if (showHelp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
                modifier = Modifier.semanticsLabel(label))
        }
        if (showHelp) Text(stringResource(R.string.mangadex_data_saver_help),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
