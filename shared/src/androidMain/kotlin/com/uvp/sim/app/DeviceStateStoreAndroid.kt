package com.uvp.sim.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android 实现:DataStore Preferences(独立于配置的那一个实例)。
 *
 * **刻意不复用 `ConfigStoreAndroid` 的 DataStore**:那个实例存的是加了密的 [com.uvp.sim.config.SimConfig]
 * (含 device.password),键空间混在一起会让"配置解密失败 → 回退 fallback"这条既有路径
 * 连带影响设备状态。两个 DataStore 名字不同、生命周期独立,互不牵连。
 *
 * 不加密:存档里只有演示用的预置位 / 巡航轨迹 / 姿态,没有任何凭据。
 */
class DeviceStateStoreAndroid(private val context: Context) : DeviceStateStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun loadOnce(): DeviceStateSnapshot? {
        val raw = context.deviceStateDataStore.data.first()[KEY_DEVICE_STATE_JSON] ?: return null
        return runCatching { json.decodeFromString<DeviceStateSnapshot>(raw) }
            .onFailure { t ->
                // 解析失败**不抛**:调用方(冷启动)拿不到存档就按全新设备启动,功能不受影响。
                // 但必须留痕 —— 否则"存档莫名其妙不生效"会变成又一个无从下手的现象。
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Resource,
                    "设备状态存档解析失败,按全新设备启动: ${t::class.simpleName}: ${t.message}",
                    t.stackTraceToString(),
                    ErrorCategory.Internal,
                )
            }
            .getOrNull()
    }

    override suspend fun save(snapshot: DeviceStateSnapshot) {
        context.deviceStateDataStore.edit { it[KEY_DEVICE_STATE_JSON] = json.encodeToString(snapshot) }
    }

    companion object {
        private val KEY_DEVICE_STATE_JSON = stringPreferencesKey("sim_device_state_json")

        /**
         * 仅测试用 — 暴露内部 DataStore 实例。
         *
         * 两个用途:① seed 一份损坏 JSON 验证"解析失败退化成全新设备";② `clear()`
         * 真正清空 —— `preferencesDataStore` 是进程级单例,光删文件清不掉它的内存缓存。
         *
         * 注:public 而非 internal,因为 `:androidApp` 与 `:shared` 是独立 module。
         */
        fun dataStoreForTest(context: Context): DataStore<Preferences> = context.deviceStateDataStore

        val KEY_DEVICE_STATE_JSON_FOR_TEST = KEY_DEVICE_STATE_JSON
    }
}

/**
 * 名字 `uvp_sim_device_state` 必须与 [ConfigStoreAndroid] 的 `uvp_sim_config` 不同 ——
 * `preferencesDataStore` 的同名委托会抛 "There are multiple DataStores active for the same file"。
 */
private val Context.deviceStateDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "uvp_sim_device_state")
