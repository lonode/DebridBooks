package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.session.MediaController
import io.github.lonode.debridbooks.playback.*
import kotlinx.coroutines.delay

@Composable fun MiniPlayer(controller: MediaController?, runtime: PlaybackRuntime, onOpen: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller) { while (true) { delay(500); tick++ } }
    @Suppress("UNUSED_VARIABLE") val observed = tick
    val runtimeState by runtime.state.collectAsState()
    val item = controller?.currentMediaItem ?: return
    val metadata = item.mediaMetadata
    val chapter = chapterLabel(metadata.subtitle?.toString().orEmpty(), metadata.title?.toString().orEmpty(), controller.currentMediaItemIndex + 1).title
    var close by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CoverArt(metadata.artworkUri?.toString(), metadata.title?.toString().orEmpty(), Modifier.size(36.dp, 48.dp))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(metadata.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(when { runtimeState.recovering -> "Reconnecting…"; controller.playerError != null -> "Playback stopped · Open to retry"; runtimeState.sleepAtEnd -> "Sleep at end of file"; runtimeState.sleepRemainingMs > 0 -> "Sleep in ${runtimeState.sleepRemainingMs / 60000} min · $chapter"; else -> chapter }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { runtime.send(PlaybackAction.Jump(item.mediaId, shiftedPosition(controller.currentPosition, controller.duration, -10000))) }) { Text("−10") }
            TextButton(onClick = { if (controller.playWhenReady) controller.pause() else controller.play() }) { Text(if (controller.playWhenReady) "Pause" else "Play") }
            TextButton(onClick = { close = true }) { Text("Close") }
        }
        if (controller.duration > 0) LinearProgressIndicator(progress = { (controller.currentPosition.toFloat() / controller.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
    if (close) AlertDialog(onDismissRequest = { close = false }, title = { Text("Close this book?") },
        text = { Text("Your place is saved. Resume from the Library whenever you want.") },
        confirmButton = { TextButton(onClick = { runtime.send(PlaybackAction.Close); close = false }) { Text("Close book") } },
        dismissButton = { TextButton(onClick = { close = false }) { Text("Cancel") } })
}
