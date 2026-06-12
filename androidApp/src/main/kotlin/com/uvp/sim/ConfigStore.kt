package com.uvp.sim

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uvp.sim.config.SimConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists [SimConfig] across app restarts via DataStore Preferences.
 *
 * The whole config is serialized to JSON and stored under one key. We don't
 * split per-field because:
 *   - Schema migration is simpler (one decode, one encode)
 *   - SimConfig is small (~1 KB JSON), atomic writes are fine
 *   - Adding a new field with a default just works on read
 *
 * If the stored JSON fails to decode (e.g. after a breaking schema change),
 * we fall back to [fallback] and overwrite next time the user saves.
 */
class ConfigStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Latest stored config, or [fallback] if nothing's stored / decode fails. */
    fun observe(fallback: SimConfig): Flow<SimConfig> =
        context.configDataStore.data.map { prefs ->
            val raw = prefs[KEY_CONFIG_JSON] ?: return@map fallback
            runCatching { json.decodeFromString<SimConfig>(raw) }.getOrDefault(fallback)
        }

    /** Read once (used on cold start before the ViewModel attaches its flow). */
    suspend fun loadOnce(fallback: SimConfig): SimConfig {
        val prefs = context.configDataStore.data.first()
        val raw = prefs[KEY_CONFIG_JSON] ?: return fallback
        return runCatching { json.decodeFromString<SimConfig>(raw) }.getOrDefault(fallback)
    }

    suspend fun save(config: SimConfig) {
        context.configDataStore.edit { it[KEY_CONFIG_JSON] = json.encodeToString(config) }
    }

    companion object {
        private val KEY_CONFIG_JSON = stringPreferencesKey("sim_config_json")
    }
}

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "uvp_sim_config")
