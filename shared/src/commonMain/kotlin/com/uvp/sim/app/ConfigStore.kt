package com.uvp.sim.app

import com.uvp.sim.config.SimConfig

/**
 * 配置持久化(PR6 T6.2)。
 *
 * **设计调整**(plan §1 决策 4 修订):
 *   spec §1.1 原锁定为 expect class,实际工程上 interface 更顺(commonTest fake 直接实现),
 *   且 ConfigStore 本身没有 expect class 独有的能力(没有需要每平台不同构造期参数的字段)。
 *   - Android `ConfigStoreAndroid(context: Context)` 用 DataStore Preferences
 *   - iOS `ConfigStoreIos()` 占位 / 走 NSUserDefaults
 *   - commonTest `FakeConfigStore` 直接 implements 即可
 *
 * 用法:
 *   val cfg = configStore.loadOnce(defaultConfig)
 *   configStore.save(cfg.copy(...))
 */
interface ConfigStore {
    /** 同步加载;失败回落 [fallback]。 */
    suspend fun loadOnce(fallback: SimConfig): SimConfig

    /** 持久化保存。 */
    suspend fun save(config: SimConfig)
}
