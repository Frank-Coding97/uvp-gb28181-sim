package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全屏二维码取景 + 解码(plan §5.1)。
 *
 * 照 [PlatformCameraPreview] 的顶层 expect composable 惯例:两端各自 actual,
 * 无 jvm actual —— `composeApp` 没有 `jvm()` target。
 *
 * 契约:
 * - [onResult] 在解码出**第一个**结果时回调一次;实现方必须在成功后停止分析,
 *   不允许对着同一张码重复回调(用例 8.5)
 * - [onError] 用于权限被拒 / 相机打不开这类不可恢复的情况,传人类可读文案
 * - 组件退出 composition 时必须释放相机(用例 8.6),不复用任何进程级单例 —
 *   扫码是短时页面,跟预览的长生命周期单例模型不同
 */
@Composable
expect fun ScanQrCode(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)
