package com.uvp.sim.device

/**
 * JVM 平台实现 — 桌面端无硬件标识。
 *
 * 桌面端（Windows / macOS / Linux）没有统一的硬件标识 API，
 * 且 sim 主要面向移动端，桌面环境用 fallback deviceId 即可。
 *
 * @param context 忽略
 * @return null（桌面端不支持）
 */
actual fun getHardwareId(context: Any?): String? = null
