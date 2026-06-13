package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 内嵌录像播放器 Dialog — 跨平台 expect。
 *
 * Android: ExoPlayer + PlayerView,全屏 Dialog,带关闭按钮。
 * iOS / JVM: 占位空盒(M2 不在范围)。
 *
 * @param filePath 录像 mp4 绝对路径。null 时不渲染。
 * @param onDismiss 用户点关闭 / 系统返回时回调。
 */
@Composable
expect fun RecordingPlayerDialog(
    filePath: String?,
    onDismiss: () -> Unit
)
