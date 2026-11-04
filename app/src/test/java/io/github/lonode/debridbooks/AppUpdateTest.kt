package io.github.lonode.debridbooks

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.github.lonode.debridbooks.network.GitHubReleaseClient
import io.github.lonode.debridbooks.network.ReleaseUpdate
import io.github.lonode.debridbooks.network.standalone.*
import io.github.lonode.debridbooks.ui.AppUpdateViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppUpdateTest {
    private fun release(tag: String = "1.3.0", flags: String = "\"draft\":false,\"prerelease\":false", asset: Boolean = true): String {
        val repo = GitHubReleaseClient.REPOSITORY
        val name = "DebridBooks-${tag.removePrefix("v")}.apk"
        return """{"tag_name":"$tag","html_url":"$repo/releases/tag/$tag",$flags,"assets":[${if (asset)
            """{"name":"$name","state":"uploaded","size":12345,"browser_download_url":"$repo/releases/download/$tag/$name"}""" else ""}]}"""
    }
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())

    @Test fun `numeric versions notify only for a newer stable release including multi-digit components`() {
        assertEquals("1.10.0", GitHubReleaseClient.parse(release("1.10.0"), "1.9.0")!!.version)
        assertEquals("2.0.0", GitHubReleaseClient.parse(release("v2.0.0"), "1.99.99-debug")!!.version)
        assertNull(GitHubReleaseClient.parse(release("1.2.0"), "1.2.0"))
        assertNull(GitHubReleaseClient.parse(release("1.9.0"), "1.10.0"))
        assertNull(GitHubReleaseClient.parse(release("1.2.9"), "1.3.0"))
        for (tag in listOf("1.3.0-beta", "nightly", "1.03.0", "1.3", "9999999999999999.0.0")) {
            assertNull(GitHubReleaseClient.parse(release(tag), "1.2.0"))
        }
    }

    @Test fun `drafts prereleases missing APKs and incomplete uploads never prompt`() {
        assertNull(GitHubReleaseClient.parse(release(flags = "\"draft\":true,\"prerelease\":false"), "1.2.0"))
        assertNull(GitHubReleaseClient.parse(release(flags = "\"draft\":false,\"prerelease\":true"), "1.2.0"))
        assertNull(GitHubReleaseClient.parse(release(asset = false), "1.2.0"))
        assertNull(GitHubReleaseClient.parse(release().replace("uploaded", "new"), "1.2.0"))
        assertNull(GitHubReleaseClient.parse(release().replace("12345", "0"), "1.2.0"))
    }

    @Test fun `only the expected repository release and APK links are accepted`() {
        assertNull(GitHubReleaseClient.parse(release().replace("https://github.com", "https://other.invalid"), "1.2.0"))
        assertNull(GitHubReleaseClient.parse(release().replace("/releases/download/", "/other/download/"), "1.2.0"))
        assertNull(GitHubReleaseClient.parse(release().replace("DebridBooks-1.3.0.apk", "another.apk"), "1.2.0"))
    }

    @Test fun `request uses public GitHub metadata only without credentials or a body`() = runTest {
        val requests = mutableListOf<DirectRequest>()
        val client = GitHubReleaseClient(DirectTransport { requests += it; ok(release()) })
        assertEquals(ReleaseUpdate("1.3.0", "https://github.com/lonode/DebridBooks/releases/tag/1.3.0"), client.newerThan("1.2.0"))
        val request = requests.single()
        assertEquals(GitHubReleaseClient.API_URL, request.url)
        assertEquals(setOf("Accept", "X-GitHub-Api-Version"), request.headers.keys)
        assertNull(request.body)
        assertEquals(256_000, request.limit)
    }

    @Test fun `network failures rate limits missing releases and malformed data are silent`() = runTest {
        for (status in listOf(403, 404, 429, 500)) {
            assertNull(GitHubReleaseClient(DirectTransport { DirectResponse(status, ByteArray(0)) }).newerThan("1.2.0"))
        }
        for (body in listOf("not JSON", "[]", "{}")) {
            assertNull(GitHubReleaseClient(DirectTransport { ok(body) }).newerThan("1.2.0"))
        }
        assertNull(GitHubReleaseClient(DirectTransport { throw DirectFailure("Service", "DNS") }).newerThan("1.2.0"))
    }

    @Test fun `timeout cancels the request and owner cancellation is preserved`() = runTest {
        var cancelled = 0
        val client = GitHubReleaseClient(DirectTransport { try { awaitCancellation() } finally { cancelled++ } })
        assertNull(client.newerThan("1.2.0"))
        assertEquals(8000, testScheduler.currentTime)
        assertEquals(1, cancelled)
        val job = launch { client.newerThan("1.2.0") }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(2, cancelled)
    }

    @Test fun `activity-scoped check runs once and dismissal does not trigger another check`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var calls = 0
            val model = AppUpdateViewModel { calls++; ReleaseUpdate("1.3.0", "https://github.com/lonode/DebridBooks/releases/tag/1.3.0") }
            store.put("updates", model)
            advanceUntilIdle()
            assertNotNull(model.available.value)
            model.dismiss()
            advanceUntilIdle()
            assertNull(model.available.value)
            assertEquals(1, calls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `leaving the activity cancels a pending check`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var cancelled = false
            val model = AppUpdateViewModel { try { awaitCancellation() } finally { cancelled = true } }
            store.put("updates", model)
            runCurrent()
            store.clear()
            advanceUntilIdle()
            assertTrue(cancelled)
            assertNull(model.available.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
