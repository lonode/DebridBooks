package io.github.lonode.debridbooks.domain

/** Account readiness is positive evidence only; false never means globally uncached. */
fun visibleSearchResults(results: List<SearchItem>, readyOnly: Boolean, format: String? = null): List<SearchItem> =
    results.filter { (!readyOnly || it.cached) && (format == null || it.format.equals(format, true)) }

fun searchCountLabel(loaded: Int, visible: Int): String = if (loaded == visible) "$loaded results loaded"
    else "$visible shown · $loaded loaded · ${loaded - visible} hidden by filters"
