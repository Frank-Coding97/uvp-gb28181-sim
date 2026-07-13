package com.uvp.sim.ui

/**
 * 用系统浏览器打开外链(About 页 GitHub / Gitee 链接用)。
 *
 * - Android:Intent.ACTION_VIEW → 走默认浏览器
 * - iOS:UIApplication.openURL — 只支持 https:// / mailto: 等安全 scheme
 *
 * 无法打开时静默失败(仅 SystemLogger 记一笔),不影响 UI 主流程。
 */
expect fun openUrl(url: String)
