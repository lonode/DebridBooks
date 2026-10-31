package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lonode.debridbooks.data.AudioLibraryItem

internal fun ShelfBook.inProgress(): Boolean = progress?.let { !it.completed && (it.partIndex > 0 || it.positionMs > 5000) } == true
internal fun shelfBooks(books: List<ShelfBook>, filter: String): List<ShelfBook> = books.filter {
    when (filter) { "progress" -> it.inProgress(); "saved" -> it.personal.saved; "offline" -> it.download?.state == "COMPLETE";
        "finished" -> it.progress?.completed == true; else -> true }
}
internal fun continueBook(books: List<ShelfBook>): ShelfBook? = books.filter { it.inProgress() }.maxByOrNull { it.progress!!.updatedAt }

@Composable
internal fun ShelfVolume(row: ShelfBook, source: AudioLibraryItem?, opening: LibraryOpening?, loadingSource: Boolean,
    canPlay: Boolean, onPlay: () -> Unit, onCancel: () -> Unit, onDetails: () -> Unit, onFind: () -> Unit,
    localError: String? = null, hero: Boolean = false, grid: Boolean = false, modifier: Modifier = Modifier) {
    val title = source?.item?.title ?: row.book.title
    val author = source?.item?.author ?: row.book.author
    val target = opening?.takeIf { it.account == row.book.accountId && it.item == row.book.itemId }
    val loading = loadingSource || target?.busy == true
    val missing = source != null && source.item == null
    val progress = row.progress?.takeIf { row.inProgress() }?.let { "Part ${it.partIndex + 1} · ${playbackTime(it.positionMs)}" }
    val status = when {
        missing -> "Source unavailable · Your place is saved"
        row.progress?.completed == true -> "Finished"
        row.download?.state == "COMPLETE" -> listOfNotNull("Available offline",progress).joinToString(" · ")
        progress != null -> progress
        row.personal.saved -> "Saved for later"
        else -> "In your library"
    }
    val actionLabel = if (target?.message != null || localError != null) "Try again" else if(grid) "Play" else if(row.inProgress()) "Resume listening" else if(row.download?.state=="COMPLETE") "Listen offline" else "Listen"
    val gradient = Brush.linearGradient(listOf(Color(0xFF2D263F), Color(0xFF49365F)))
    Surface(modifier.fillMaxWidth(), shape=RoundedCornerShape(if(hero) 26.dp else 18.dp),
        color=if(hero) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor=if(hero) Color(0xFFFFF8EF) else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.then(if(hero) Modifier.background(gradient) else Modifier).padding(if(hero) 20.dp else 14.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            val text: @Composable ColumnScope.() -> Unit = {
                Text(title, fontFamily=if(hero) FontFamily.Serif else FontFamily.Default, fontWeight=FontWeight.SemiBold,
                    fontSize=if(hero) 23.sp else 16.sp, lineHeight=if(hero) 29.sp else 22.sp,
                    maxLines=3,overflow=TextOverflow.Ellipsis)
                author?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,
                    color=LocalContentColor.current.copy(alpha=.75f)) }
                if (row.download?.state == "COMPLETE") {
                    Surface(shape = RoundedCornerShape(50),
                        color = if (hero) Color(0xFFFFF8EF) else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (hero) Color(0xFF2D263F) else MaterialTheme.colorScheme.onSecondaryContainer) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LibraryIcon(LibrarySymbol.Download, Modifier.size(16.dp))
                            Text("Downloaded", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(status, style=MaterialTheme.typography.labelMedium,color=LocalContentColor.current.copy(alpha=.8f))
            }
            if(grid && !hero) {
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                    LibraryCover(source?.item?.poster,title,Modifier.fillMaxWidth().aspectRatio(.76f))
                }
                Column(verticalArrangement=Arrangement.spacedBy(6.dp),content=text)
            } else Row(horizontalArrangement=Arrangement.spacedBy(16.dp), verticalAlignment=Alignment.CenterVertically) {
                LibraryCover(source?.item?.poster,title,Modifier.size(if(hero) 104.dp else 68.dp,if(hero) 144.dp else 96.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),content=text)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(missing) OutlinedButton(onClick=onFind,modifier=Modifier.weight(1f).heightIn(min=48.dp),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=if(hero) Color(0xFFFFF8EF) else MaterialTheme.colorScheme.primary)) { Text("Find source") }
                else LibraryListenAction(actionLabel,loading,canPlay,target?.message ?: localError,onPlay,onCancel,
                    modifier=Modifier.weight(1f),hero=hero,preparation=target?.progress,startedAtNanos=target?.startedAtNanos)
                if(source?.item != null) IconButton(onClick=onDetails,
                    modifier=Modifier.align(Alignment.Top).size(48.dp).semantics { contentDescription="Details for $title" }) { PlayerIcon(PlayerSymbol.Info) }
            }
        }
    }
}
