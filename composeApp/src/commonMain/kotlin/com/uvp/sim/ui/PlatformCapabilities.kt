package com.uvp.sim.ui

import androidx.compose.ui.unit.Dp

/**
 * 平台能力探针。
 *
 * - Android: true(NetworkController 真做硬绑)
 * - iOS / JVM: false(NetworkController no-op)
 *
 * 用途:SettingsScreen 入口灰显判断 + NetworkSettingsPage 显示说明文案。
 */
expect val isNetworkSelectionSupported: Boolean

/**
 * 底部导航栏布局风格。
 *
 * - Android / JVM: false —— 传统 docked tab bar,贴屏幕底,占布局空间
 * - iOS: true —— iOS 26 Liquid Glass 风悬浮 tab bar,不占布局空间,
 *   内容可以从下方滑过,tab bar 有圆角 + 阴影 + 水平内边距。
 *
 * 用途:[com.uvp.sim.ui.App] 选择 Column 布局(docked)vs Box 布局(floating)。
 */
expect val isFloatingBottomBar: Boolean

/**
 * 悬浮 tab bar 需要各 Screen 底部预留的高度。
 *
 * - iOS: 130dp = home indicator safe area 34dp + tab bar 高度 64dp + 32dp 视觉呼吸
 * - Android / JVM: 0dp(docked tab bar 自己占布局空间,内容天然不重叠)
 *
 * 用途:
 * - 固定布局屏(Simulate / Capability / Settings) → 外层 Column 加 padding(bottom = 此值)
 * - 可滚动屏(Home 的 verticalScroll,Log 的 LazyColumn) → 内容/contentPadding 底部加此值
 *   让最后一条能完整滚出 tab bar,而非被永久遮住
 * - 视觉上 tab bar 依旧"悬浮"在 iOS 内容上方(通过 Box.align(BottomCenter) 渲染),
 *   本常量只保证滚动后不永久盖住内容,而非把 tab bar 顶起来
 */
expect val floatingBottomBarReservedBottom: Dp

/**
 * 主屏顶部 status banner 是否内联"注册 CTA"。
 *
 * - iOS: true —— iOS HIG "status-as-action" pattern,注册按钮做成 banner 右侧胶囊
 * - Android / JVM: false —— 传统底部独立 ConnectButton
 *
 * 用途:[HomeScreen] 按平台选布局;iOS 首屏空间紧不能被 SIP 配置卡挤下去,
 *   Android 有更多垂直空间,底部按钮更符合 Material 习惯。
 */
expect val isTopStatusCtaInlined: Boolean

/**
 * 应用构建信息 —— About 页展示 versionName / versionCode。
 *
 * - Android:PackageManager 读 versionName / longVersionCode(定义在
 *   androidApp/build.gradle.kts 的 defaultConfig)
 * - iOS:NSBundle.mainBundle.infoDictionary 读 CFBundleShortVersionString /
 *   CFBundleVersion(定义在 iosApp/project.yml → Info.plist)
 */
expect object PlatformBuildInfo {
    val versionName: String
    val versionCode: String
}
