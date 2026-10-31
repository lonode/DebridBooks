package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.github.lonode.debridbooks.R
import io.github.lonode.debridbooks.playback.RuntimeState
import java.util.Locale

internal class PlayerActions(val tool: (String) -> Unit, val chapters: () -> Unit, val previous: () -> Unit,
    val next: () -> Unit, val play: () -> Unit, val seek: (Long) -> Unit, val jump: (Int) -> Unit,
    val undo: () -> Unit, val range: () -> Unit)

/** A bounded now-playing surface. Artwork yields space to controls; no scrolling containers. */
@Composable
internal fun PlayerLayout(state: PlaybackSnapshot, runtime: RuntimeState, chapter: String, rangeStart: Long, rangeEnd: Long,
    chapterProgress: Boolean, canChangeRange: Boolean, remainingTime: Boolean, hasPrevious: Boolean, hasNext: Boolean,
    hasChapters: Boolean, actions: PlayerActions) {
    var dragging by remember(state.identity) { mutableStateOf(false) }
    var preview by remember(state.identity) { mutableFloatStateOf(0f) }
    var dragStart by remember(state.identity) { mutableLongStateOf(0) }
    var dragDuration by remember(state.identity) { mutableLongStateOf(0) }
    val duration = (rangeEnd - rangeStart).coerceAtLeast(0)
    val sleepActive = runtime.sleepAtEnd || runtime.sleepRemainingMs > 0
    val status = when {
        runtime.recovering -> stringResource(R.string.player_reconnecting)
        state.failed -> stringResource(R.string.player_error_retry)
        runtime.sleepAtEnd -> stringResource(R.string.player_sleep_file_end)
        runtime.sleepRemainingMs > 0 -> stringResource(R.string.player_sleep_remaining, playbackTime(runtime.sleepRemainingMs))
        else -> runtime.message
    }
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("${state.index + 1} / ${state.count}", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics { contentDescription = "Audio file ${state.index + 1} of ${state.count}" })
            }
            IconButton(onClick = actions.undo, enabled = runtime.undoAvailable,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Undo jump" }) { PlayerIcon(PlayerSymbol.Undo) }
            if (canChangeRange) IconButton(onClick = actions.range,
                modifier = Modifier.size(48.dp).semantics { contentDescription = if (chapterProgress) "Use file progress" else "Use chapter progress" }) { PlayerIcon(PlayerSymbol.Range) }
            IconButton(onClick = { actions.tool("info") },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Playback details" }) { PlayerIcon(PlayerSymbol.Info) }
        }
    }
    val titles: @Composable (Boolean) -> Unit = { compact ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(state.title, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(chapter, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (state.chapterContext ?: state.author.takeIf { it.isNotBlank() })?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val controls: @Composable (Boolean) -> Unit = { compact ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                PlayerTool(Modifier.weight(1f), "Chapters", "Choose a chapter", hasChapters, onClick = actions.chapters) { PlayerIcon(PlayerSymbol.Queue) }
                PlayerTool(Modifier.weight(1f), "Bookmark", "Add bookmark", onClick = { actions.tool("bookmark") }) { PlayerIcon(PlayerSymbol.Bookmark) }
                PlayerTool(Modifier.weight(1f), "Sleep", "Sleep timer", active = sleepActive, onClick = { actions.tool("sleep") }) { PlayerIcon(PlayerSymbol.Sleep) }
                PlayerTool(Modifier.weight(1f), "Time", "Go to time", onClick = { actions.tool("time") }) { PlayerIcon(PlayerSymbol.Time) }
                PlayerTool(Modifier.weight(1f), "Speed", "Playback speed", onClick = { actions.tool("speed") }) {
                    Text(speedLabel(state.speed), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                PlayerTool(Modifier.weight(1f), "Car", "Car mode", onClick = { actions.tool("car") }) { PlayerIcon(PlayerSymbol.Car) }
            }
            Column(Modifier.fillMaxWidth()) {
                if (canChangeRange) Text(stringResource(if (chapterProgress) R.string.player_progress_in_chapter else R.string.player_progress_in_file),
                    style = MaterialTheme.typography.labelSmall)
                val fraction = if (duration > 0) (state.position - rangeStart).toFloat() / duration else 0f
                Slider(value = if (dragging) preview else fraction.coerceIn(0f, 1f),
                    onValueChange = {
                        if (!dragging) { dragStart = rangeStart; dragDuration = duration }
                        dragging = true; preview = it
                    },
                    onValueChangeFinished = { actions.seek(dragStart + (preview * dragDuration).toLong()); dragging = false },
                    enabled = duration > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Playback position" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playbackTime(if (dragging) (preview * dragDuration).toLong() else (state.position - rangeStart).coerceAtLeast(0)),
                        style = MaterialTheme.typography.labelMedium)
                    Text(if (duration <= 0) "−:−−" else if (remainingTime) "−${playbackTime((rangeEnd - state.position).coerceAtLeast(0))}" else playbackTime(duration),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.previous, enabled = hasPrevious,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Previous part" }) { PlayerIcon(PlayerSymbol.Previous, Modifier.size(32.dp)) }
                FilledIconButton(onClick = actions.play, modifier = Modifier.size(72.dp).semantics {
                    contentDescription = if (state.failed) "Retry playback" else if (state.playing) "Pause" else "Play"
                }) {
                    val color = MaterialTheme.colorScheme.onPrimary
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(30.dp)) {
                            if (state.playing) {
                                drawRect(color, Offset(size.width * .18f, 0f), Size(size.width * .22f, size.height))
                                drawRect(color, Offset(size.width * .6f, 0f), Size(size.width * .22f, size.height))
                            } else drawPath(Path().apply { moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close() }, color)
                        }
                        if (state.buffering) CircularProgressIndicator(Modifier.size(60.dp), color = color, strokeWidth = 2.dp)
                    }
                }
                IconButton(onClick = actions.next, enabled = hasNext,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Next part" }) { PlayerIcon(PlayerSymbol.Next, Modifier.size(32.dp)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (seconds in listOf(-30, -10, 10, 30)) OutlinedButton(onClick = { actions.jump(seconds) },
                    contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f).height(48.dp).semantics {
                        contentDescription = if (seconds < 0) "Rewind ${-seconds} seconds" else "Forward $seconds seconds"
                    }) {
                    Text(if (seconds < 0) "−${-seconds}s" else "+${seconds}s", maxLines = 1, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val compact = maxHeight < 650.dp
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    header()
                    PlayerArtwork(state, Modifier.weight(1f).fillMaxWidth())
                    titles(true)
                }
                Box(Modifier.weight(1.4f)) { controls(true) }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
                header()
                PlayerArtwork(state, Modifier.weight(1f).fillMaxWidth())
                titles(compact)
                controls(compact)
            }
        }
    }
}

@Composable
private fun PlayerArtwork(state: PlaybackSnapshot, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * .78f, 320.dp).coerceAtLeast(0.dp)
        CoverArt(state.artwork, state.title, Modifier.size(width, width / .78f), large = true)
    }
}

@Composable
private fun PlayerTool(modifier: Modifier, label: String, description: String, enabled: Boolean = true, active: Boolean = false,
    onClick: () -> Unit, icon: @Composable () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
        modifier = modifier.height(56.dp).semantics { contentDescription = description; role = Role.Button; selected = active }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) { icon() }
            Text(label, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
internal fun speedLabel(speed: Float): String = String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"
