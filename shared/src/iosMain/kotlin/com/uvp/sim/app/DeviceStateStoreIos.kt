package com.uvp.sim.app

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * iOS 实现:NSUserDefaults + JSON。
 *
 * 与 [ConfigStoreIos] 同一套路,但**键不同**(`com.uvp.sim.device_state` 对 `com.uvp.sim.config`),
 * 且不做密码脱敏 —— 设备状态里没有任何凭据。
 *
 * [jsonStore] 可注入,便于 iosTest 用内存实现绕开真实 NSUserDefaults。
 */
class DeviceStateStoreIos(
    private val jsonStore: DeviceStateJsonStore = UserDefaultsDeviceStateJsonStore(),
) : DeviceStateStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun loadOnce(): DeviceStateSnapshot? {
        val raw = jsonStore.read() ?: return null
        return runCatching { json.decodeFromString<DeviceStateSnapshot>(raw) }
            .onFailure { t ->
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Resource,
                    "设备状态存档解析失败,按全新设备启动: ${t::class.simpleName}: ${t.message}",
                )
            }
            .getOrNull()
    }

    override suspend fun save(snapshot: DeviceStateSnapshot) {
        jsonStore.write(json.encodeToString(snapshot))
    }
}

/** iOS 侧最小的字符串存储抽象(iosTest 用内存实现)。 */
interface DeviceStateJsonStore {
    fun read(): String?
    fun write(value: String)
}

class UserDefaultsDeviceStateJsonStore : DeviceStateJsonStore {
    override fun read(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_DEVICE_STATE_JSON)

    override fun write(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, KEY_DEVICE_STATE_JSON)
        NSUserDefaults.standardUserDefaults.synchronize()
    }

    companion object {
        const val KEY_DEVICE_STATE_JSON = "com.uvp.sim.device_state"
    }
}
