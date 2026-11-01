package io.github.lonode.debridbooks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lonode.debridbooks.BuildConfig
import io.github.lonode.debridbooks.R
import io.github.lonode.debridbooks.DebridApplication
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import io.github.lonode.debridbooks.audio.ReadinessJobs

@Composable
fun DebridApp(model: LibraryViewModel, externalQuery: String? = null, libraryEvent: Int = 0) {
    val state by model.state.collectAsStateWithLifecycle()
    val controller = rememberPlayerController()
    val uriHandler = LocalUriHandler.current
    var tab by rememberSaveable { mutableStateOf(MainTab.Library) }
    // Dialog windows handle Back first. Library is the root destination;
    // another Back there retains Android's normal leave-app behavior.
    BackHandler(enabled = tab != MainTab.Library) { tab = MainTab.Library }
    LaunchedEffect(externalQuery) { if (externalQuery != null) tab = MainTab.Search }
    LaunchedEffect(libraryEvent) { if (libraryEvent > 0) tab = MainTab.Library }
    val app = LocalContext.current.applicationContext as DebridApplication
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try { ReadinessJobs.check(app, foreground = true) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                delay(3 * 60_000L)
            }
        }
    }
    LaunchedEffect(state.loading, state.preferences.options.notifyReady, state.preferences.options.notifyReleases, state.preferences.unmeteredOnly) {
        if (!state.loading) ReadinessJobs.configure(app, state.preferences.options, state.preferences.unmeteredOnly)
    }
    var whatsNew by remember { mutableStateOf(false) }
    val notesVersion = "native-86c85f3-1"
    LaunchedEffect(state.loading, state.preferences.options.whatsNewSeen) {
        if (!state.loading && !state.loadError && state.preferences.options.whatsNewSeen != notesVersion) {
            if (state.books.isNotEmpty() || state.preferences.options.whatsNewSeen.isNotEmpty()) whatsNew = true
            else app.preferences.setOptions { it.copy(whatsNewSeen = notesVersion) }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scrollStates = MainTab.entries.map { rememberScrollState() }
    val dark = isSystemInDarkTheme()
    val accent = when (state.preferences.options.accent) {
        "blue" -> if (dark) Color(0xFFB5C4FF) else Color(0xFF335ECC)
        "teal" -> if (dark) Color(0xFF6AD8C5) else Color(0xFF006A60)
        "orange" -> if (dark) Color(0xFFFFB77D) else Color(0xFF994400)
        "pink" -> if (dark) Color(0xFFFFAFDA) else Color(0xFFA12D73)
        else -> if (dark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
    }
    val colours = (if (dark) darkColorScheme() else lightColorScheme()).copy(primary = accent,
        secondaryContainer = accent.copy(alpha = if (dark) .25f else .14f), onSecondaryContainer = if (dark) Color.White else Color.Black)
    val screenColours = if (tab == MainTab.Library) colours.copy(
        surface = if(dark) Color(0xFF191719) else Color(0xFFFBF8F3),
        background = if(dark) Color(0xFF191719) else Color(0xFFFBF8F3),
        surfaceContainerLow = if(dark) Color(0xFF242125) else Color(0xFFF3EFE8),
        surfaceContainer = if(dark) Color(0xFF28242B) else Color(0xFFF0EBE5),
        surfaceContainerHigh = if(dark) Color(0xFF322D35) else Color(0xFFEAE4DD),
        onSurface = if(dark) Color(0xFFF0E8E2) else Color(0xFF29232B),
        onSurfaceVariant = if(dark) Color(0xFFC8BEC6) else Color(0xFF665D65),
    ) else colours
    MaterialTheme(colorScheme = screenColours) {
        Scaffold(snackbarHost = { PlaybackNotices(app.playbackRuntime, snackbar) }, bottomBar = {
            Column {
                if (tab != MainTab.Player) MiniPlayer(controller, app.playbackRuntime) { tab = MainTab.Player }
                NavigationBar {
                MainTab.entries.forEach { destination ->
                    NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                        icon = { NavigationIcon(destination) }, label = { Text(destination.label) })
                }
                }
            }
        }) { insets ->
            // Keep the controller/search host composed while scrolling or switching tabs.
            val isPlayer = tab == MainTab.Player
            Column(Modifier.fillMaxSize().padding(insets)
                .then(if (isPlayer) Modifier else Modifier.verticalScroll(scrollStates[tab.ordinal]))
                .padding(horizontal = if (isPlayer) 16.dp else 20.dp, vertical = if (isPlayer) 8.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (isPlayer) 0.dp else 20.dp)) {
                if (!isPlayer && tab != MainTab.Library) {
                    Text(tab.label, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                }
                ConnectionPanel(controller, tab, externalQuery = externalQuery, onNavigate = { tab = it }, library = state, onSpeed = model::setSpeed)
                if (tab == MainTab.Library || tab == MainTab.Settings) {
                    if (state.loading) CircularProgressIndicator()
                    else if (state.loadError) {
                        Text(stringResource(R.string.load_error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = model::reload) { Text(stringResource(R.string.retry)) }
                    }
                }
                if (tab == MainTab.Settings && !state.loading && !state.loadError) {
                    SectionCard {
                        Text(stringResource(R.string.playback_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.playback_speed))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(.75f, 1f, 1.25f, 1.5f, 2f)) { speed ->
                                FilterChip(selected = state.preferences.speed == speed, onClick = { model.setSpeed(speed) },
                                    label = { Text(stringResource(R.string.speed_value, speed.toString())) })
                            }
                        }
                    }
                    SectionCard {
                        Text(stringResource(R.string.downloads_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        val label = stringResource(R.string.unmetered_only)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, Modifier.weight(1f))
                            Switch(checked = state.preferences.unmeteredOnly, onCheckedChange = model::setUnmeteredOnly,
                                modifier = Modifier.semantics { contentDescription = label })
                        }
                        Text(stringResource(R.string.unmetered_description))
                    }
                    AdvancedSettings(state.preferences.options)
                    BackupSettings()
                    if (state.saveError) Text(stringResource(R.string.settings_error), color = MaterialTheme.colorScheme.error)
                    SectionCard {
                        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.about_description))
                        TextButton(onClick = { whatsNew = true }) { Text("What’s new") }
                        TextButton(onClick = { uriHandler.openUri("https://github.com/lonode/DebridBooks-App") }) {
                            Text(stringResource(R.string.github_source))
                        }
                    }
                }
            }
        }
        if (whatsNew) AlertDialog(onDismissRequest = { whatsNew = false; scope.launch { app.preferences.setOptions { it.copy(whatsNewSeen = notesVersion) } } },
            title = { Text("What’s new in DebridBooks") }, text = { Text("Embedded audiobook chapters, chapter search and progress. Sleep and bedtime tools, bookmarks, listening goals, offline audio, library views and protected backup. Comics and MangaDex remain available.") },
            confirmButton = { TextButton(onClick = { whatsNew = false; scope.launch { app.preferences.setOptions { it.copy(whatsNewSeen = notesVersion) } } }) { Text("Got it") } })

    }
}
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}
