package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lonode.debridbooks.domain.CatalogBook
import io.github.lonode.debridbooks.network.standalone.CatalogClient
import kotlinx.coroutines.*

@Composable
internal fun RecommendationDetailsDialog(book: CatalogBook, onClose: () -> Unit, onSearch: (String) -> Unit) {
    var details by remember(book) { mutableStateOf(book) }
    var loading by remember(book) { mutableStateOf(book.description.isNullOrBlank() && book.catalogId!=null && book.language in setOf("fr","en")) }
    var failed by remember(book) { mutableStateOf(false) }
    var attempt by remember(book) { mutableIntStateOf(0) }
    LaunchedEffect(book, attempt) {
        if(attempt==0 && !book.description.isNullOrBlank()) return@LaunchedEffect
        if(book.catalogId==null || book.language !in setOf("fr","en")) return@LaunchedEffect
        loading=true;failed=false
        try { details=CatalogClient().recommendationDetails(book) }
        catch(timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive();failed=true }
        catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { failed=true }
        finally { loading=false }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(16.dp),shape=MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Book details",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=onClose){Text("Close")}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        LibraryCover(details.poster,details.title,Modifier.size(104.dp,144.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(details.title,fontFamily=FontFamily.Serif,fontSize=25.sp,lineHeight=31.sp)
                            details.author?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    val metadata=listOfNotNull(
                        when(details.language){"fr"->"French";"en"->"English";else->null},
                        details.durationMinutes?.let { if(it>=60) "${it/60}h ${it%60}m" else "$it min" },
                        details.released?.take(4))
                    if(metadata.isNotEmpty()) Text(metadata.joinToString(" · "),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    details.genre?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    HorizontalDivider()
                    if(loading) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading book details…") }
                    details.description?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
                        ?: if(!loading) Text("A synopsis is not available for this book.",color=MaterialTheme.colorScheme.onSurfaceVariant) else Unit
                    if(failed) {
                        Text("Book details could not be loaded. You can retry or search for the audiobook.",color=MaterialTheme.colorScheme.error)
                        TextButton(onClick={attempt++}){Text("Retry details")}
                    }
                }
                Button(onClick={onSearch(details.search ?: details.title)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Search audiobook") }
            }
        }
    }
}
