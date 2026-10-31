package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.domain.displayMetadata
import kotlinx.coroutines.CancellationException

@Composable
fun CoverArt(url: String?, title: String, modifier: Modifier = Modifier, large: Boolean = false, fallback: String? = null) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val width = if (large) 1024 else 320
    val height = if (large) 1536 else 480
    val warmPrimary = remember(url, large) { app.comics.cachedCover(url, width, height) }
    val warm = remember(warmPrimary, fallback, large) {
        warmPrimary ?: app.comics.cachedCover(fallback, width, height)
    }
    val bitmap by key(url, large, fallback) {
        produceState<ImageBitmap?>(warm?.asImageBitmap()) {
            if (warm == null || (url != null && warmPrimary == null)) {
                for (candidate in listOfNotNull(url, fallback).distinct()) {
                    try {
                        value = app.comics.coverBitmap(candidate, width, height).asImageBitmap()
                        break
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Try the saved page thumbnail before the initial placeholder. */ }
                }
            }
        }
    }
    Surface(modifier.clip(MaterialTheme.shapes.medium), color = MaterialTheme.colorScheme.secondaryContainer) {
        val image = bitmap
        if (image != null) Image(image, "Cover of $title", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(title.firstOrNull()?.uppercase() ?: "♪", style = MaterialTheme.typography.displayMedium)
        }
    }
}

@Composable
fun SearchResultCard(item: SearchItem, enabled: Boolean = true, onClick: () -> Unit) {
    BookCard(item.title, item.poster, item.author, item.displayMetadata(), enabled = enabled, onClick = onClick) {
        if (item.cached) Text("⚡ Ready in your AllDebrid account", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun BookCard(title: String, poster: String?, author: String? = null, details: List<String> = emptyList(),
    fallbackPoster: String? = null, enabled: Boolean = true, onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {}) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CoverArt(poster, title, Modifier.size(64.dp, 96.dp), fallback = fallbackPoster)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (details.isNotEmpty()) Text(details.joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
}
