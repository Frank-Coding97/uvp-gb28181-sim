package com.uvp.sim.device

import platform.UIKit.UIDevice

/**
 * iOS 平台实现 — 获取 identifierForVendor。
 *
 * identifierForVendor 特性：
 * - UUID 格式（e.g., "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"）
 * - 同一 vendor（bundle ID 前两段相同）的 app 共享
 * - 卸载该 vendor 的所有 app 后重置
 * - 可能在 app 后台被杀、设备刚重启时短暂返回 nil
 *
 * @param context 忽略（iOS 不需要 context）
 * @return identifierForVendor UUID 字符串，获取失败返回 null
 */
actual fun getHardwareId(context: Any?): String? {
    return UIDevice.currentDevice.identifierForVendor?.UUIDString
}
