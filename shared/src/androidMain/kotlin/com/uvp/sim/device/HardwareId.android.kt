package com.uvp.sim.device

import android.content.Context
import android.provider.Settings

/**
 * Android 平台实现 — 获取 ANDROID_ID。
 *
 * ANDROID_ID 特性：
 * - 64 位十六进制字符串
 * - 设备首次启动时生成，恢复出厂设置后重置
 * - 跨 app 共享（同一设备所有 app 看到同一个值）
 * - Android 8.0+ 改为按"app 签名 + 设备"派生，但 sim 单 app 场景无影响
 *
 * @param context 必须是 android.content.Context 实例
 * @return ANDROID_ID 字符串，获取失败返回 null
 */
actual fun getHardwareId(context: Any?): String? {
    return try {
        val ctx = context as? Context ?: return null
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Exception) {
        null
    }
}
