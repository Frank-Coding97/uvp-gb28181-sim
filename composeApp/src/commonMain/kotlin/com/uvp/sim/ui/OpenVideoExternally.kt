package com.uvp.sim.ui

/**
 * 用系统应用打开本地录像 mp4 文件。
 *
 * Android: Intent.ACTION_VIEW + FileProvider URI(`@xml/file_paths` 暴露 recordings/)
 * iOS / JVM: 占位 no-op
 */
expect fun openVideoExternally(filePath: String)
