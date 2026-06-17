package com.uvp.sim.ui

/**
 * 平台能力探针。
 *
 * - Android: true(NetworkController 真做硬绑)
 * - iOS / JVM: false(NetworkController no-op)
 *
 * 用途:SettingsScreen 入口灰显判断 + NetworkSettingsPage 显示说明文案。
 */
expect val isNetworkSelectionSupported: Boolean
