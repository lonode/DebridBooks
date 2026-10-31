package io.github.lonode.debridbooks.ui

import io.github.lonode.debridbooks.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class LibrarySymbol { More, Tune, Back, Download, Bookmark, Stats, Refresh, Grid, List, Play }

@Composable
internal fun LibraryIcon(symbol: LibrarySymbol, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), stroke.width, StrokeCap.Round)
        when (symbol) {
            LibrarySymbol.More -> listOf(5f,12f,19f).forEach { drawCircle(color, 1.7f*u, Offset(12*u,it*u)) }
            LibrarySymbol.Tune -> listOf(6f to 8f, 12f to 16f, 18f to 10f).forEach { (y,x) -> line(3f,y,21f,y); drawCircle(color,2.6f*u,Offset(x*u,y*u)) }
            LibrarySymbol.Back -> { line(20f,12f,4f,12f); line(4f,12f,11f,5f); line(4f,12f,11f,19f) }
            LibrarySymbol.Download -> { line(12f,3f,12f,16f);line(7f,11f,12f,16f);line(12f,16f,17f,11f);line(4f,17f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,17f) }
            LibrarySymbol.Bookmark -> drawPath(Path().apply { moveTo(6*u,3*u);lineTo(18*u,3*u);lineTo(18*u,21*u);lineTo(12*u,17*u);lineTo(6*u,21*u);close() },color,style=stroke)
            LibrarySymbol.Stats -> { line(5f,20f,5f,13f);line(12f,20f,12f,5f);line(19f,20f,19f,9f) }
            LibrarySymbol.Refresh -> { drawArc(color,40f,290f,false,Offset(4*u,4*u),androidx.compose.ui.geometry.Size(16*u,16*u),style=stroke);line(20f,3f,20f,9f);line(20f,9f,14f,9f) }
            LibrarySymbol.Grid -> for(x in listOf(3f,14f)) for(y in listOf(3f,14f)) drawRect(color,Offset(x*u,y*u),androidx.compose.ui.geometry.Size(7*u,7*u),style=stroke)
            LibrarySymbol.List -> for(y in listOf(5f,12f,19f)) { drawCircle(color,1.4f*u,Offset(4*u,y*u));line(9f,y,21f,y) }
            LibrarySymbol.Play -> drawPath(Path().apply { moveTo(7*u,4*u);lineTo(20*u,12*u);lineTo(7*u,20*u);close() },color)
        }
    }
}

@Composable
internal fun LibraryHeader(kind: Int, onKind: (Int) -> Unit, onTool: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(4.dp)) {
                listOf("Audiobooks", "Comics & manga").forEachIndexed { index, label ->
                    Surface(onClick = { onKind(index) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = kind == index },
                        shape = RoundedCornerShape(24.dp), color = if (kind == index) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shadowElevation = if (kind == index) 1.dp else 0.dp) {
                        Box(Modifier.padding(horizontal=8.dp, vertical=12.dp), contentAlignment=Alignment.Center) {
                            Text(label, style=MaterialTheme.typography.labelLarge, color=if(kind==index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (kind == 0) Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Library tools" }) { LibraryIcon(LibrarySymbol.More) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(Triple("Downloads", "downloads", LibrarySymbol.Download), Triple("Bookmarks", "bookmarks", LibrarySymbol.Bookmark),
                    Triple("Listening stats", "stats", LibrarySymbol.Stats)).forEach { (label, route, icon) ->
                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { LibraryIcon(icon) }, onClick = { menu = false; onTool(route) })
                }
            }
        }
    }
}

@Composable
internal fun ShelfHeading(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 31.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action?.invoke()
    }
}

@Composable
internal fun LibraryCover(poster: String?, title: String, modifier: Modifier, fallback: String? = null) {
    if (poster != null || fallback != null) CoverArt(poster, title, modifier, fallback = fallback)
    else Surface(modifier, shape = RoundedCornerShape(10.dp)) {
        val palette = listOf(Color(0xFFD9C7B5), Color(0xFFC2D1CC), Color(0xFFD4C4D1), Color(0xFFCAD0DD))
        val base = palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(base, lerp(base, Color.White, .22f)))), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(Color.Black.copy(alpha=.12f),Offset(7.dp.toPx(),0f),Offset(7.dp.toPx(),size.height),2.dp.toPx())
                for(y in listOf(.16f,.84f)) drawLine(Color.Black.copy(alpha=.2f),Offset(size.width*.23f,size.height*y),Offset(size.width*.8f,size.height*y),1.dp.toPx())
            }
            Text(title.firstOrNull()?.uppercase().orEmpty(), fontFamily=FontFamily.Serif, fontSize=36.sp,color=Color(0xFF403A35))
        }
    }
}

/** Ephemeral feedback, keyed to the existing account/book identity; never persisted in saved state. */
data class LibraryOpening(val account: String, val item: String, val busy: Boolean = true, val message: String? = null, val bookmarkId: String? = null,
    val startedAtNanos: Long = System.nanoTime(),
    val progress: MutableStateFlow<OpeningProgress> = MutableStateFlow(OpeningProgress(OpeningStage.CheckingCurrentSession)))

@Composable
internal fun LibraryListenAction(label: String, loading: Boolean, enabled: Boolean, message: String?, onPlay: () -> Unit,
    onCancel: () -> Unit, modifier: Modifier = Modifier, hero: Boolean = false, preparation: StateFlow<OpeningProgress>? = null, startedAtNanos: Long? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onPlay, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth().heightIn(min=48.dp),
            colors = if (hero) ButtonDefaults.buttonColors(containerColor=Color(0xFFFFF6E9),contentColor=Color(0xFF302537),
                disabledContainerColor=Color(0xFFFFF6E9).copy(alpha=.16f),disabledContentColor=Color.White) else ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical=10.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth=2.dp, color=LocalContentColor.current)
            else LibraryIcon(LibrarySymbol.Play, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp));Text(if (loading) "Opening…" else label)
        }
        if (loading) {
            OpeningFeedback(preparation, startedAtNanos, onCancel, hero)
        } else message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style=MaterialTheme.typography.bodySmall, modifier=Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color=if(hero) Color(0xFFFFDED2) else MaterialTheme.colorScheme.error)
        }
    }
}


@Composable
private fun OpeningFeedback(flow: StateFlow<OpeningProgress>?, startedAtNanos: Long?, onCancel: () -> Unit, hero: Boolean) {
    val fallback=remember { OpeningProgress(OpeningStage.ReadingSavedBook) }
    val progress=if(flow!=null) flow.collectAsState().value else fallback
    val start=remember(startedAtNanos) { startedAtNanos ?: System.nanoTime() }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while(true) { now=System.nanoTime();delay(1000) } }
    val elapsed=openingSeconds(start,now)
    val waiting=openingSeconds(progress.updatedAtNanos,now)
    val label=when(progress.stage) {
        OpeningStage.ReadingSavedBook -> "Reading saved book"
        OpeningStage.CheckingCurrentSession -> "Checking the current player"
        OpeningStage.CheckingOfflineFiles -> "Checking offline files"
        OpeningStage.WaitingForProvider -> "Waiting for a provider request"
        OpeningStage.CheckingAccount -> "Checking your AllDebrid account"
        OpeningStage.FetchingTorrent -> "Fetching the torrent source"
        OpeningStage.AddingTorrent -> "Adding the torrent to AllDebrid"
        OpeningStage.CheckingTransfer -> "Checking torrent readiness"
        OpeningStage.ReadingFiles -> "Loading the file list"
        OpeningStage.PreparingFiles -> "Preparing audio files · ${progress.completed} / ${progress.total}"
        OpeningStage.RestoringPlace -> "Restoring your saved chapter and place"
        OpeningStage.StartingPlayback -> "Starting playback"
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics { if(progress.stage!=OpeningStage.PreparingFiles) liveRegion=LiveRegionMode.Polite })
        if(progress.stage==OpeningStage.PreparingFiles && progress.total!=null) LinearProgressIndicator(
            progress={progress.completed.toFloat()/progress.total},modifier=Modifier.fillMaxWidth(),
            color=if(hero) Color(0xFFFFF6E9) else MaterialTheme.colorScheme.primary,
            trackColor=if(hero) Color.White.copy(alpha=.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("${elapsed}s elapsed",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
            TextButton(onClick=onCancel,colors=ButtonDefaults.textButtonColors(contentColor=if(hero) Color.White else MaterialTheme.colorScheme.primary)){Text("Cancel")}
        }
        if(waiting>=8) {
            Text("No new update for ${waiting}s",style=MaterialTheme.typography.labelSmall)
            Text("This step is taking longer than usual. You can cancel and retry.",style=MaterialTheme.typography.bodySmall,
                modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
        }
    }
}
