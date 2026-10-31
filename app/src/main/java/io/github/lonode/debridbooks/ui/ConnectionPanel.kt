package io.github.lonode.debridbooks.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import io.github.lonode.debridbooks.network.standalone.*
import io.github.lonode.debridbooks.playback.PlaybackService
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@Composable
fun ConnectionPanel(controller: MediaController?, destination: MainTab, externalQuery: String? = null, onNavigate: (MainTab) -> Unit, library: LibraryState, onSpeed: (Float) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val app = context.applicationContext as DebridApplication
    val scope = rememberCoroutineScope()
    val setup: ConnectionViewModel = viewModel(factory = viewModelFactory { initializer { ConnectionViewModel(app) } })
    var connection by setup.connection
    LaunchedEffect(setup.loading.value) {
        if (!setup.loading.value && !setup.initialNavigationHandled) {
            setup.initialNavigationHandled = true
            if (connection == null) onNavigate(MainTab.Settings)
        }
    }
    var searchSession by remember { mutableStateOf(SearchSession()) }
    var externalConsumed by remember(externalQuery) { mutableStateOf(false) }
    var requestedSearch by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var savedSearchKind by rememberSaveable { mutableIntStateOf(0) }
    val searchKind = savedSearchKind.coerceIn(0, 1)
    var libraryKind by rememberSaveable { mutableIntStateOf(0) }
    var libraryTool by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf<LibraryOpening?>(null) }
    var playJob by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableStateOf<SearchItem?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    fun changeSearchLanguage(language: String) {
        if (busy || setup.loading.value) return
        searchJob?.cancel()
        searchSession = SearchSession()
        selected = null
        message = ""
        busy = true
        scope.launch {
            try { setup.setSearchLanguage(language) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "Language filter could not be saved. Please retry." }
            finally { busy = false }
        }
    }
    fun requestSearch(text: String) {
        query = text; requestedSearch = text; selected = null; savedSearchKind = 0; onNavigate(MainTab.Search)
    }
    LaunchedEffect(externalQuery, connection?.accountId) {
        if (!externalConsumed && externalQuery != null && connection != null) { externalConsumed = true; requestSearch(externalQuery) }
    }
    fun play(item: SearchItem, accountHint: String? = connection?.accountId, target: AudioBookmark? = null) {
        if (busy) {
            if (searchJob?.isActive == true && opening?.busy != true) { searchJob?.cancel();searchSession=searchSession.cancelled() }
            else return
        }
        val account = accountHint ?: return
        val current = connection
        val key = MobileContract.localId(item.id)
        val operation = LibraryOpening(account, key, bookmarkId = target?.id)
        opening = operation
        message = ""
        busy = true
        app.playbackRuntime.send(io.github.lonode.debridbooks.playback.PlaybackAction.BeginSelection)
        playJob = scope.launch {
            var remembered = false
            try {
                app.audio.remember(account, item)
                remembered = true
                val player = checkNotNull(controller)
                val active = player.currentMediaItem?.mediaMetadata?.extras
                if (target == null && active?.getString("account") == account && active.getString("item") == key && player.playerError == null) {
                    operation.progress.value = OpeningProgress(OpeningStage.StartingPlayback)
                    player.play(); libraryTool = ""; onNavigate(MainTab.Player); return@launch
                }
                operation.progress.value = OpeningProgress(OpeningStage.CheckingOfflineFiles)
                val offline = app.audio.offline(account, key)
                val resolution = offline ?: current?.takeIf { it.accountId == account }?.let { MobileApi(it).resolve(item.id) { progress -> operation.progress.value = progress } } ?: error("Reconnect to stream")
                if (!resolution.ready) { message = "Added to your library. ${resolution.preparationMessage()}"; opening = opening?.copy(message = message); return@launch }
                check(offline != null || connection === current)
                if (offline == null) searchSession = searchSession.confirmedReady(item.id)
                operation.progress.value = OpeningProgress(OpeningStage.RestoringPlace)
                val queue = io.github.lonode.debridbooks.playback.audioQueue(account, key, item, resolution)
                val saved = withContext(Dispatchers.IO) {
                    app.database.library().progress(account, key)
                }
                check(offline != null || connection === current)
                val found = queue.indexOfFirst { it.mediaId == (target?.partId ?: saved?.partId) }
                require(target == null && saved == null || found >= 0) { "Saved part is no longer present" }
                val index = found.coerceAtLeast(0)
                operation.progress.value = OpeningProgress(OpeningStage.StartingPlayback)
                player.setMediaItems(queue, index, target?.positionMs ?: if (queue[index].mediaId == saved?.partId) (saved.positionMs - 5000).coerceAtLeast(0) else 0)
                player.prepare(); player.play(); message = ""; libraryTool = ""; onNavigate(MainTab.Player)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = if (remembered) "Saved in your library. Playback could not start. Check the connection and try again." else "Could not save this book to your library. Please retry."; opening = opening?.copy(message = message) }
            finally { if (currentCoroutineContext().isActive) { busy = false; opening = opening?.copy(busy = false) } }
        }
    }
    Column(modifier = if (destination == MainTab.Player) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (destination) {
            MainTab.Settings -> {
                if (setup.error.value && connection == null) Text("Saved connection could not be opened. Connect again.")
                StandaloneSetup(setup.draft, busy || setup.loading.value, connection != null, ::changeSearchLanguage) {
                    scope.launch {
                        busy = true
                        message = ""
                        try {
                            val settings = setup.draft.config()
                            val candidate = withContext(Dispatchers.IO) { app.connections.candidate(settings, connection) }
                            StandaloneApi(settings).validate()
                            // Validation does not touch the active session. Apply only a fully tested configuration.
                            context.stopService(android.content.Intent(context, io.github.lonode.debridbooks.comics.ComicDownloadService::class.java))
                            context.stopService(android.content.Intent(context, io.github.lonode.debridbooks.audio.AudioDownloadService::class.java))
                            searchJob?.cancel(); controller?.stop(); controller?.clearMediaItems()
                            withContext(Dispatchers.IO) { app.connections.save(candidate) }
                            app.bookDetails.capabilities(candidate, MobileApi(candidate).capabilities())
                            connection = candidate; searchSession = SearchSession(); selected = null
                            setup.error.value = false; message = "Keys verified and saved"; onNavigate(MainTab.Search)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: SetupProblem) { message = failure.message.orEmpty() }
                        catch (failure: DirectFailure) { message = failure.displayMessage() }
                        catch (_: Exception) { message = "Setup failed. Check the API keys, enabled sources and HTTPS endpoints, then retry." }
                        finally { if (currentCoroutineContext().isActive) busy = false }
                    }
                }
                if (connection != null) {
                    OutlinedButton(enabled = !busy, onClick = {
                        context.stopService(android.content.Intent(context, io.github.lonode.debridbooks.comics.ComicDownloadService::class.java))
                        context.stopService(android.content.Intent(context, io.github.lonode.debridbooks.audio.AudioDownloadService::class.java))
                        searchJob?.cancel(); controller?.stop(); controller?.clearMediaItems()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { app.connections.clear() }
                                setup.draft.load(StandaloneConfig(""))
                                connection = null; searchSession = SearchSession(); selected = null; message = "Keys removed from this device"
                            } catch (_: Exception) { message = "Could not remove saved keys" }
                        }
                    }) { Text("Remove saved keys") }
                }

            }
            MainTab.Search -> {
                TabRow(selectedTabIndex = searchKind) {
                    listOf("Audiobooks", stringResource(io.github.lonode.debridbooks.R.string.comics_manga)).forEachIndexed { index, label ->
                        Tab(selected = searchKind == index, onClick = { savedSearchKind = index }, text = { Text(label) })
                    }
                }
                if (searchKind == 0) {
                    if (connection == null) {
                        Text("Enter your AllDebrid and source keys in Settings to search for audiobooks.")
                        Button(onClick = { onNavigate(MainTab.Settings) }) { Text("Set up API keys") }
                    } else {
                        OutlinedTextField(query, { query = it }, label = { Text("Title or author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        fun search(request: SearchRequest) {
                            focusManager.clearFocus(); keyboard?.hide()
                            searchJob?.cancel()
                            val current = connection
                            if (current == null || request.query.isEmpty()) {
                                return
                            }
                            query = request.query
                            searchSession = searchSession.requesting(request)
                            selected = null; message = ""
                            busy = true
                            searchJob = scope.launch {
                                try {
                                    val result = MobileApi(current).search(request.query, request.number, onlySources = request.sources, forceAbb = request.forceAbb) { update ->
                                        withContext(Dispatchers.Main.immediate) {
                                            ensureActive()
                                            if (connection === current) searchSession = searchSession.progressing(update)
                                        }
                                    }
                                    ensureActive()
                                    if (connection !== current) return@launch
                                    searchSession = searchSession.received(result)
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { searchSession = searchSession.failed(error) }
                                finally { if (currentCoroutineContext().isActive) busy = false }
                            }
                        }
                        LaunchedEffect(requestedSearch, busy) { if (!busy) requestedSearch?.takeIf { it.isNotBlank() }?.let { search(SearchRequest(it, refresh = true)); requestedSearch = null } }
                        if(busy && searchJob?.isActive==true) {
                            OutlinedButton(onClick={
                                searchJob?.cancel();searchJob=null;requestedSearch=null
                                searchSession=searchSession.cancelled();busy=false;message=""
                            },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Cancel search") }
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Searching audiobooks…",style=MaterialTheme.typography.bodySmall)
                        } else Button(enabled = !busy && query.isNotBlank(), onClick = { search(SearchRequest(query.trim(), refresh = true)) }, modifier = Modifier.fillMaxWidth()) { Text("Search audiobooks") }
                        var pickedFormat by remember(searchSession.query) { mutableStateOf<String?>(null) }
                        val formats = remember(searchSession.results) { searchSession.results.mapNotNull { it.format?.uppercase(java.util.Locale.ROOT) }.distinct() }
                        val shown = visibleSearchResults(searchSession.results, readyOnly = false, format = pickedFormat)
                        SearchFeedback(searchSession, busy, ::search, visibleCount = shown.size)
                        selected?.let { item ->
                            AudioBookDialog(connection!!, item, busy && searchJob?.isActive != true, message, controller != null,
                                onClose = { selected = null; message = "" }, onPlay = { play(item) }, account = connection?.accountId, onSearch = ::requestSearch, onPlayChapter = { play(item, connection?.accountId, it) })
                        }
                        if (formats.size > 1) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(pickedFormat == null, onClick = { pickedFormat = null }, label = { Text("All formats") }) }
                            items(formats.size) { index -> val format = formats[index]; FilterChip(pickedFormat == format, onClick = { pickedFormat = format }, label = { Text(format) }) }
                        }
                        if (shown.isEmpty() && searchSession.results.isNotEmpty()) Text("No uploads match the selected format. Choose another format or scroll for more results.")
                        if (library.preferences.options.groupUploads) groupUploads(shown).forEach { uploads ->
                            var expanded by remember(uploads.first().id) { mutableStateOf(false) }
                            SearchResultCard(uploads.first(), enabled = !busy || searchJob?.isActive == true, onClick = { selected = uploads.first(); message = "" })
                            if (uploads.size > 1) TextButton(onClick = { expanded = !expanded }) { Text("${uploads.size} uploads · ${if (expanded) "Hide alternatives" else "Choose another upload"}") }
                            if (expanded) uploads.drop(1).forEach { item -> SearchResultCard(item, enabled = !busy || searchJob?.isActive == true, onClick = { selected = item; message = "" }) }
                        } else shown.forEach { item -> SearchResultCard(item, enabled = !busy || searchJob?.isActive == true, onClick = { selected = item; message = "" }) }
                        AutoSearchMore(searchSession, busy || selected != null, ::search, visibleCount = shown.size)
                    }
                } else io.github.lonode.debridbooks.comics.ComicPanel(connection)
            }
            MainTab.Player -> PlayerPanel(controller, onSpeed, onSearch = { onNavigate(MainTab.Search) })
            MainTab.Library -> {
                LibraryHeader(libraryKind, { libraryKind = it; libraryTool = "" }, { libraryTool = it })
                LibraryPanes(selected = libraryKind, audio = {
                    AudioLibraryPanel(controller, connection, library, onSearch = ::requestSearch,
                        onPlay = { item, account, mark -> play(item, account, mark) },
                        opening = opening, onCancelOpening = { playJob?.cancel(); opening = null; busy = false; message = "" },
                        tool = libraryTool, onTool = { libraryTool = it },
                        canStart = !busy || (searchJob?.isActive == true && opening?.busy != true))
                }, comics = { io.github.lonode.debridbooks.comics.ComicPanel(connection, historyOnly = true) })
            }
        }
        if (destination != MainTab.Player && destination != MainTab.Library) {
            if (busy && searchJob?.isActive != true) CircularProgressIndicator()
            if (message.isNotEmpty()) Text(message)
        }
    }
}
