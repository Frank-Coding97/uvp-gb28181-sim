package com.uvp.sim.device

/**
 * 获取设备硬件标识，用于派生唯一的 deviceId。
 *
 * - Android: Settings.Secure.ANDROID_ID（需要 Context）
 * - iOS: UIDevice.currentDevice.identifierForVendor
 * - JVM: null（桌面端无实现）
 *
 * @param context 平台相关上下文（Android 需要 android.content.Context，其他平台忽略）
 * @return 硬件标识字符串，获取失败返回 null
 */
expect fun getHardwareId(context: Any?): String?
