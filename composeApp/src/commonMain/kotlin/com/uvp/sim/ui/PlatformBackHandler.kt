package com.uvp.sim.ui

import androidx.compose.runtime.Composable

/**
 * 拦截系统/平台级返回手势(Android 硬件 / 手势返回键)。
 *
 * - Android: 代理 androidx.activity.compose.BackHandler
 * - iOS / JVM: no-op(无硬件返回键概念)
 *
 * 用法:在二级页 composable 顶部调用,enabled 为 true 时按返回键执行 onBack 而非退出 Activity。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
