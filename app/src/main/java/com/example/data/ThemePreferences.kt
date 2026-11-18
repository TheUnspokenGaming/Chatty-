package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.NexusThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nexusPrefsDataStore by preferencesDataStore(name = "nexus_ui_prefs")

class ThemePreferences(private val context: Context) {

    private val themeModeKey = stringPreferencesKey("theme_mode")

    val themeModeFlow: Flow<NexusThemeMode> = context.applicationContext.nexusPrefsDataStore.data
        .map { prefs ->
            val raw = prefs[themeModeKey]
            NexusThemeMode.entries.firstOrNull { it.name == raw } ?: NexusThemeMode.OBSIDIAN
        }

    suspend fun setThemeMode(mode: NexusThemeMode) {
        context.applicationContext.nexusPrefsDataStore.edit { prefs ->
            prefs[themeModeKey] = mode.name
        }
    }
}
