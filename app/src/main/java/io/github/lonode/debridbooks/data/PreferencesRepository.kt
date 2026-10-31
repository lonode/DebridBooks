package io.github.lonode.debridbooks.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

data class ListeningPreferences(val speed: Float = 1f, val unmeteredOnly: Boolean = true, val comicSaver: Boolean = true, val immersiveHelpDismissed: Boolean = false, val options: AppOptions = AppOptions())

class PreferencesRepository(private val store: DataStore<Preferences>) {
    private val optionsKey = stringPreferencesKey("app_options_v1")
    private val immersiveHelpKey = booleanPreferencesKey("immersive_help_dismissed")
    private val comicSaverKey = booleanPreferencesKey("comic_data_saver")
    private val speedKey = floatPreferencesKey("speed")
    private val unmeteredKey = booleanPreferencesKey("unmetered_only")
    val preferences = store.data.map { values ->
        val options = try { values[optionsKey]?.let { libraryJson.decodeFromString<AppOptions>(it).also(AppOptions::validate) } ?: AppOptions() } catch (_: Exception) { AppOptions() }
        ListeningPreferences(options = options,
            speed = values[speedKey]?.takeIf { it.isFinite() && it in 0.5f..3.5f } ?: 1f,
            comicSaver = values[comicSaverKey] ?: true,
            immersiveHelpDismissed = values[immersiveHelpKey] ?: false,
            unmeteredOnly = values[unmeteredKey] ?: true,
        )
    }

    suspend fun setOptions(change: (AppOptions) -> AppOptions) {
        store.edit { values ->
            val previous = try { values[optionsKey]?.let { libraryJson.decodeFromString<AppOptions>(it) } ?: AppOptions() } catch (_: Exception) { AppOptions() }
            val value = change(previous).also(AppOptions::validate)
            values[optionsKey] = libraryJson.encodeToString(value)
        }
    }
    suspend fun setImmersiveHelpDismissed(value: Boolean) { store.edit { it[immersiveHelpKey] = value } }

    suspend fun setComicSaver(value: Boolean) { store.edit { it[comicSaverKey] = value } }

    suspend fun setSpeed(speed: Float) {
        require(speed.isFinite() && speed in 0.5f..3.5f)
        store.edit { it[speedKey] = speed }
    }

    suspend fun setUnmeteredOnly(value: Boolean) {
        store.edit { it[unmeteredKey] = value }
    }
}
