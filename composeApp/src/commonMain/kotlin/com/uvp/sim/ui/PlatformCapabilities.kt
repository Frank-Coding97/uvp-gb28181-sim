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
 * 主屏顶部 status banner 是否内联"注册 CTA"。
 *
 * - iOS: true —— iOS HIG "status-as-action" pattern,注册按钮做成 banner 右侧胶囊
 * - Android / JVM: false —— 传统底部独立 ConnectButton
 *
 * 用途:[HomeScreen] 按平台选布局;iOS 首屏空间紧不能被 SIP 配置卡挤下去,
 *   Android 有更多垂直空间,底部按钮更符合 Material 习惯。
 */
expect val isTopStatusCtaInlined: Boolean
