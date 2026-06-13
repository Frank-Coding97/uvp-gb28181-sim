package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 录像卡片缩略图 — 跨平台 expect。
 *
 * Android: 用 `BitmapFactory.decodeFile(filePath)` 读 JPEG → asImageBitmap 渲染。
 * iOS / JVM: 占位空盒(commonMain 调用方包了 placeholder Box)。
 *
 * @param filePath 缩略图绝对路径(jpg)。null 或文件不存在显示 [onMissing]。
 * @param onMissing 失败回退 — 调用方传一个占位图。
 */
@Composable
expect fun RecordingThumbnail(
    filePath: String?,
    modifier: Modifier = Modifier,
    onMissing: @Composable () -> Unit
)
