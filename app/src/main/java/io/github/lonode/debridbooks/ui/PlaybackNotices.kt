package io.github.lonode.debridbooks.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.lonode.debridbooks.playback.PlaybackRuntime
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable fun PlaybackNotices(runtime: PlaybackRuntime, host: SnackbarHostState) {
    val message by remember(runtime) { runtime.state.map { it.message }.distinctUntilChanged() }.collectAsState(initial="")
    LaunchedEffect(message) { if (message.isNotBlank()) host.showSnackbar(message, withDismissAction=true) }
    SnackbarHost(host)
}
