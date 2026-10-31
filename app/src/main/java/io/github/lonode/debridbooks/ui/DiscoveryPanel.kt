package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.R
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import kotlinx.coroutines.CancellationException

@Composable fun DiscoveryPanel(connection: Connection, item: SearchItem, onSearch: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val libraryBooks by remember(app) { app.database.library().observeBooks() }.collectAsState(initial=emptyList())
    val owned = libraryBooks.filter { it.accountId==connection.accountId }.map { CatalogBook(it.title,author=it.author) }
    var result by remember(connection.cacheKey, item.id, item.detailsLanguage()) { mutableStateOf<DiscoveryResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(connection.cacheKey, item.id, item.detailsLanguage(), attempt) {
        loading = true; failed = false
        try {
            result = app.discovery.loadResult(connection, item,
                MobileContract.localId(item.id).takeIf { app.database.library().audioSource(connection.accountId, it) != null },
                refreshSeries = attempt > 0, refreshSimilar = attempt > 0)
            failed = result?.similarFailed == true || result?.data?.similar?.partial == true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val shown = result?.data?.similar?.let { mergeRecommendations(listOf(it),owned) }
    RecommendationList(shown, loading, failed, stringResource(R.string.similar_no_match), { attempt++ }, onSearch)
    result?.data?.series?.let { series ->
        var expanded by remember(item.id) { mutableStateOf(false) }
        Text(series.name, style = MaterialTheme.typography.titleMedium)
        series.position?.let { Text("Book $it" + (series.total?.let { total -> " of $total" } ?: "")) }
        series.next?.takeIf { !it.upcoming }?.let { next -> TextButton(onClick = { onSearch(next.search ?: next.title) }) { Text("Next in series · ${next.title}") } }
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.hide_series_books else R.string.show_series_books)) }
        if (expanded) series.books.take(100).forEach { book -> TextButton(onClick = { onSearch(book.search ?: book.title) }) {
            Text(listOfNotNull(book.position, book.title, book.released?.takeIf { book.upcoming }?.let { "Coming $it" }).joinToString(" · "))
        } }
    }
}

/** Load from recent listening without requiring a details-page visit or notification opt-in. */
@Composable fun LibraryRecommendations(connection: Connection?, books: List<ShelfBook>, onSearch: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val seeds = books.filter { it.book.accountId == connection?.accountId && it.progress?.let { progress ->
        progress.completed || progress.partIndex > 0 || progress.positionMs > 5000
    } == true }.sortedByDescending { it.progress?.updatedAt }.take(3)
    val identities = seeds.map { it.book.itemId }
    val owned = books.map { row -> CatalogBook(row.book.title, author=row.book.author,
        catalogId=row.personal.discovery?.similar?.seedIds?.firstOrNull(), seriesIds=row.personal.discovery?.similar?.seedSeriesIds.orEmpty()) }
    var result by remember(connection?.cacheKey, identities) { mutableStateOf<SimilarDetails?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(connection?.cacheKey, identities, attempt) {
        val current = connection ?: return@LaunchedEffect
        loading = seeds.isNotEmpty(); failed = false
        try {
            val feeds = mutableListOf<SimilarDetails>()
            for (seed in seeds) {
                val item = app.audioLibraryItems.item(seed.book).item ?: continue
                try {
                    val found = app.discovery.loadResult(current, item, seed.book.itemId,
                        refreshSeries = attempt > 0, refreshSimilar = attempt > 0)
                    failed = failed || found.similarFailed || found.data.similar?.partial == true
                    found.data.similar?.let { feeds += it }
                    mergeRecommendations(feeds, emptyList(), 36)?.let { result = it }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { failed = true }
            }
            if (feeds.isNotEmpty() || !failed) result = mergeRecommendations(feeds, emptyList(), 36)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val shown = result?.let { mergeRecommendations(listOf(it),owned) }
    RecommendationList(shown, loading, failed, stringResource(when {
        connection == null -> R.string.similar_connect
        seeds.isEmpty() -> R.string.similar_start_listening
        else -> R.string.similar_no_match
    }), { attempt++ }, onSearch, canRefresh = connection != null && seeds.isNotEmpty())
}

@Composable
private fun RecommendationList(data: SimilarDetails?, loading: Boolean, failed: Boolean, emptyText: String,
    onRetry: () -> Unit, onSearch: (String) -> Unit, canRefresh: Boolean = true) {
    var selected by remember { mutableStateOf<CatalogBook?>(null) }
    val books = data?.books.orEmpty().filter { !it.upcoming }.distinctBy { recommendationIdentity(it.title, it.author) }.take(12)
    ShelfHeading(stringResource(R.string.similar_books)) {
        if(canRefresh) IconButton(enabled=!loading,onClick=onRetry,modifier=Modifier.semantics { contentDescription="Refresh recommendations" }) {
            if(loading) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else LibraryIcon(LibrarySymbol.Refresh)
        }
    }
    if (loading) Text(stringResource(R.string.similar_loading), style = MaterialTheme.typography.bodySmall)
    if (failed && !loading) Text(stringResource(if(books.isEmpty()) R.string.similar_unavailable else R.string.similar_partial), style = MaterialTheme.typography.bodySmall)
    if (!loading && !failed && books.isEmpty()) Text(emptyText, style = MaterialTheme.typography.bodySmall)
    if (books.isNotEmpty()) {
        data?.because?.let { Text(stringResource(R.string.similar_based_on, it), style = MaterialTheme.typography.bodySmall) }
        if(data?.genreFallback==true) Text(stringResource(R.string.similar_genre_expanded),style=MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { recommendationIdentity(it.title,it.author) }) { book ->
                Card(onClick = { selected = book }, modifier = Modifier.width(140.dp),
                    colors=CardDefaults.cardColors(containerColor=Color.Transparent)) {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        LibraryCover(book.poster,book.title,Modifier.fillMaxWidth().height(190.dp))
                        Text(book.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                        book.author?.takeIf { it.isNotBlank() }?.let { Text(it,maxLines=1,overflow=TextOverflow.Ellipsis,
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    val companions=data?.companions.orEmpty().filterNot { it.upcoming }
    if(companions.isNotEmpty()) {
        var expanded by remember(data?.because) { mutableStateOf(false) }
        TextButton(onClick={expanded=!expanded}) { Text(stringResource(if(expanded) R.string.similar_hide_companions else R.string.similar_show_companions, companions.size)) }
        if(expanded) companions.forEach { book -> TextButton(onClick={selected=book}) { Text(listOfNotNull(book.title,book.author).joinToString(" · ")) } }
    }

    selected?.let { book -> RecommendationDetailsDialog(book,onClose={selected=null},onSearch={
        selected=null;onSearch(it)
    }) }


}
