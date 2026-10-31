package io.github.lonode.debridbooks

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.lonode.debridbooks.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `preferences survive reopening storage and invalid speed preserves previous value`() = runTest {
        val file = temporary.root.resolve("settings.preferences_pb")
        val firstJob = Job()
        val first = PreferencesRepository(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + firstJob), produceFile = { file },
        ))
        try {
            assertFalse(first.preferences.first().immersiveHelpDismissed)
            first.setImmersiveHelpDismissed(true)
            first.setSpeed(1.5f)
            first.setUnmeteredOnly(false)
            try { first.setSpeed(Float.NaN); fail("NaN accepted") } catch (_: IllegalArgumentException) { }
        } finally { firstJob.cancelAndJoin() }
        val secondJob = Job()
        try {
            val second = PreferencesRepository(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + secondJob), produceFile = { file },
            ))
            assertTrue(second.preferences.first().immersiveHelpDismissed)
            assertEquals(1.5f, second.preferences.first().speed)
            assertFalse(second.preferences.first().unmeteredOnly)
        } finally { secondJob.cancelAndJoin() }
    }
}
