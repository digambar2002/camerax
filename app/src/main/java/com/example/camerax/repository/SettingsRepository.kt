package com.example.camerax.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.camerax.model.AppSettings
import com.example.camerax.model.CameraFacing
import com.example.camerax.model.Resolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore extension on [Context] — created lazily once per process. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "webcam_settings")

/**
 * Persistent settings repository backed by Jetpack DataStore.
 *
 * All reads are exposed as [Flow]s so the UI and ViewModel can react to changes.
 * Writes are suspending functions and must be called from a coroutine.
 *
 * Designed for future extensibility: add new [Preferences.Key]s here for
 * white balance, ISO, focus mode, etc.
 */
class SettingsRepository(private val context: Context) {

    // -------------------------------------------------------------------------
    // Preference keys
    // -------------------------------------------------------------------------

    private object Keys {
        val RESOLUTION_INDEX = intPreferencesKey("resolution_index")
        val FPS              = intPreferencesKey("fps")
        val CAMERA_FACING    = stringPreferencesKey("camera_facing")
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    /**
     * Emits the current [AppSettings] and updates whenever any preference changes.
     */
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val resIndex = prefs[Keys.RESOLUTION_INDEX]
            ?.coerceIn(0, Resolution.ALL.lastIndex)
            ?: 1   // default 720p
        val fps = prefs[Keys.FPS]
            ?.let { if (it == 60) 60 else 30 }
            ?: 30
        val facing = prefs[Keys.CAMERA_FACING]
            ?.let { runCatching { CameraFacing.valueOf(it) }.getOrDefault(CameraFacing.REAR) }
            ?: CameraFacing.REAR
        AppSettings(resolutionIndex = resIndex, fps = fps, cameraFacing = facing)
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    /** Persist the user-selected resolution by its index in [Resolution.ALL]. */
    suspend fun saveResolutionIndex(index: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RESOLUTION_INDEX] = index.coerceIn(0, Resolution.ALL.lastIndex)
        }
    }

    /** Persist the user-selected FPS (30 or 60). */
    suspend fun saveFps(fps: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FPS] = if (fps == 60) 60 else 30
        }
    }

    /** Persist the user-selected camera facing. */
    suspend fun saveCameraFacing(facing: CameraFacing) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CAMERA_FACING] = facing.name
        }
    }
}
