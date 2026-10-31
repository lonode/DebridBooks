package io.github.lonode.debridbooks.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.*

fun audioQueue(account: String, itemKey: String, item: SearchItem, resolution: Resolution): List<MediaItem> = resolution.streams.map { part ->
    val label = chapterLabel(part.title, item.title)
    val extras = Bundle().apply {
        putString("account", account); putString("item", itemKey); putString("title", item.title); putString("author", item.author)
        part.size?.let { putLong("fileSize", it) }
        label.context?.let { putString("chapterContext", it) }
    }
    MediaItem.Builder().setMediaId(part.id).setUri(part.url).setMediaMetadata(MediaMetadata.Builder()
        .setTitle(item.title).setArtist(item.author).setSubtitle(label.title)
        .setArtworkUri(item.poster?.let { runCatching { HttpBoundary.validateUrl(it); android.net.Uri.parse(it) }.getOrNull() })
        .setExtras(extras).build()).build()
}
