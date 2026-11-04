package io.github.lonode.debridbooks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lonode.debridbooks.BuildConfig
import io.github.lonode.debridbooks.network.GitHubReleaseClient
import io.github.lonode.debridbooks.network.ReleaseUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Activity-scoped: rotation does not repeat the request or reopen a dismissed prompt. */
class AppUpdateViewModel internal constructor(check: suspend () -> ReleaseUpdate?) : ViewModel() {
    constructor() : this({ GitHubReleaseClient().newerThan(BuildConfig.VERSION_NAME) })
    private val mutable = MutableStateFlow<ReleaseUpdate?>(null)
    val available = mutable.asStateFlow()
    init { viewModelScope.launch { mutable.value = check() } }
    fun dismiss() { mutable.value = null }
}
