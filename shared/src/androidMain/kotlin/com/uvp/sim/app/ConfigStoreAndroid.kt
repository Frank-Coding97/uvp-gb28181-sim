package com.uvp.sim.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uvp.sim.config.SimConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android 实现:DataStore Preferences 持久化(从 androidApp/ConfigStore.kt 迁过来)。
 *
 * SharedPreferences key 保持 `sim_config_json`(用户已有数据兼容,无 schema 迁移)。
 */
class ConfigStoreAndroid(private val context: Context) : ConfigStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun loadOnce(fallback: SimConfig): SimConfig {
        val prefs = context.configDataStore.data.first()
        val raw = prefs[KEY_CONFIG_JSON] ?: return fallback
        return runCatching { json.decodeFromString<SimConfig>(raw) }.getOrDefault(fallback)
    }

    override suspend fun save(config: SimConfig) {
        context.configDataStore.edit { it[KEY_CONFIG_JSON] = json.encodeToString(config) }
    }

    companion object {
        private val KEY_CONFIG_JSON = stringPreferencesKey("sim_config_json")
    }
}

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "uvp_sim_config")
