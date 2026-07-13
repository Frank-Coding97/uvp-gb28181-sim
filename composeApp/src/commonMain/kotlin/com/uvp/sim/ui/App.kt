package com.uvp.sim.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.notification.NotificationBell
import com.uvp.sim.ui.notification.NotificationScreen
import com.uvp.sim.ui.notification.rememberNotificationState

/**
 * 跨 Tab 导航跳转能力。主屏报警 tile 长按时,通过它切到「能力」Tab 并
 * 让 CapabilityScreen 直接打开报警子页。null callback = 默认无操作(测试/预览安全)。
 */
class AppNavigator(
    val navigateToAlarm: () -> Unit = {},
    val navigateToSettings: () -> Unit = {},
)

val LocalAppNavigator = staticCompositionLocalOf { AppNavigator() }

/**
 * App shell — 紧凑顶/底栏 + 3 tab + 全局 UvpToast。
 *
 * 自定义顶栏(36dp)和底栏(56dp)代替 Material3 默认的 64/80dp,
 * 给主屏内容腾出关键的 30+ dp 高度,让"注册"按钮回到一眼可见的范围。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(state: AppUiState, actions: AppActions) {
    UvpTheme {
        var currentTab by rememberSaveable { mutableStateOf(AppTab.Home) }
        var alarmTarget by rememberSaveable { mutableStateOf(false) }
        var notificationOpen by rememberSaveable { mutableStateOf(false) }
        val (notificationState, markAllRead) = rememberNotificationState(state.events)
        val navigator = AppNavigator(
            navigateToAlarm = {
                alarmTarget = true
                currentTab = AppTab.Capability
            },
            navigateToSettings = {
                currentTab = AppTab.Settings
            }
        )
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        UvpToastHost {
            CompositionLocalProvider(LocalAppNavigator provides navigator) {
                // 点击输入框以外的空白区域 → 收起键盘 + 清焦点。
                // iOS 系统键盘本身没有隐藏键,靠 app 自己响应。
                // detectTapGestures 只在 pointer event 未被子元素消费时触发 →
                // 输入框 / 按钮 / clickable 区域内的点击照旧,不受影响。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(UvpColor.Bg)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                keyboard?.hide()
                                focus.clearFocus()
                            })
                        }
                ) {
                    if (notificationOpen) {
                        NotificationScreen(
                            state = notificationState,
                            onBack = { notificationOpen = false },
                        )
                    } else {
                        // imePadding: 键盘弹起时收缩内容区(TopBar / weight(1f) Surface /
                        // 可能的 Android docked bottom bar 一起),悬浮 tab bar 在此 Column
                        // 之外(下面 Box.align(BottomCenter)),不受影响 —— 它靠自身的
                        // safeDrawing.only(Bottom) 拿到 ime 高度,自然抬到键盘上方。
                        // 配合 iOS 侧关掉 SwiftUI keyboard avoidance + Compose
                        // OnFocusBehavior.DoNothing,输入体验统一走 Compose 侧管理。
                        Column(modifier = Modifier.fillMaxSize().imePadding()) {
                            CompactTopBar(
                                unreadCount = notificationState.unreadCount,
                                onBellClick = {
                                    notificationOpen = true
                                    markAllRead()
                                }
                            )
                            NetworkUnavailableBanner(
                                runtime = state.networkRuntimeState,
                                onClick = { currentTab = AppTab.Settings }
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                color = UvpColor.Bg
                            ) {
                                // iOS 悬浮 tab bar 的毛玻璃需要内容能滚到 bar 底下
                                // 才有"糊"的效果 —— 不给内容加底部 padding,让 SIP 配置卡
                                // / 能力卡末行能穿过 tab bar,毛玻璃真正显效。
                                // 各个 Screen 里的 LazyColumn/scrollable 用 contentPadding
                                // 保留最后一条的可见性 —— 但主视觉上 tab bar 是"漂浮在
                                // 内容上",跟 Apple Fitness 一致。
                                Box(modifier = Modifier.fillMaxSize()) {
                                    when (currentTab) {
                                        AppTab.Home -> HomeScreen(state, actions)
                                        AppTab.Capability -> com.uvp.sim.ui.capability.CapabilityScreen(
                                            state, actions,
                                            openAlarmTarget = alarmTarget,
                                            onAlarmTargetConsumed = { alarmTarget = false }
                                        )
                                        AppTab.Simulate -> com.uvp.sim.ui.simulate.SimulateScreen(
                                            state = state,
                                            actions = actions,
                                        )
                                        AppTab.Settings -> SettingsScreen(state, actions)
                                        AppTab.Log -> LogScreen(state, actions)
                                    }
                                }
                            }
                            // Docked bottom bar 只在 Android 时占布局空间;
                            // iOS 悬浮 tab bar 由下面的 Box.align(BottomCenter) 渲染。
                            if (!isFloatingBottomBar) {
                                CompactBottomBar(currentTab) { currentTab = it }
                            }
                        }
                        // iOS 26 Liquid Glass 风悬浮 tab bar,不占 Column 布局空间。
                        if (isFloatingBottomBar) {
                            FloatingBottomBar(
                                active = currentTab,
                                onPick = { currentTab = it },
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTopBar(unreadCount: Int = 0, onBellClick: () -> Unit = {}) {
    val toast = LocalToastHost.current
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.Surface)
                .height(40.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { toast.info("扫一扫功能将在 UVP 平台上线后启用") }
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = "扫一扫",
                    tint = UvpColor.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "GB28181 Sim",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text
            )
            Spacer(Modifier.weight(1f))
            NotificationBell(
                unreadCount = unreadCount,
                onClick = onBellClick,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
    }
}

@Composable
private fun CompactBottomBar(active: AppTab, onPick: (AppTab) -> Unit) {
    // 底部 insets 处理(跨平台一致):
    //   Android 手势导航:navigationBars 30dp 由 Android Compose 主动 apply
    //   iOS Home Indicator:34dp 由 SwiftUI ContentView 的 safe area 处理
    //     (iOS 侧 SwiftUI 不 ignoresSafeArea,Compose 天然贴在 safe area 内)
    //
    // navigationBars 在 iOS 上返回 0 是正确的 —— iOS 端 SwiftUI 已经把 tab bar
    // 推到 Home Indicator 上方,Compose 不需要再加 padding。
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)  // 比标准 56dp 高 8dp,容纳半圆上凸
                .background(UvpColor.Surface),
            contentAlignment = Alignment.BottomStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppTab.entries.forEachIndexed { index, tab ->
                    if (index == 2) {
                        // 中间位置(模拟)留 weight 占位,真正的圆按钮浮在 Row 上方
                        Box(modifier = Modifier.weight(1f).fillMaxSize())
                    } else {
                        BottomTabItem(
                            tab = tab,
                            selected = tab == active,
                            onClick = { onPick(tab) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            // 半圆上凸的特色按钮(模拟控制)
            SimulateAccentButton(
                selected = active == AppTab.Simulate,
                onClick = { onPick(AppTab.Simulate) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
            )
        }
    }
}

/**
 * iOS 悬浮胶囊 tab bar(方案 A —— 纯白背景 + 强阴影)。
 *
 * 设计决策:Compose Multiplatform 的 UIKitView 只糊 UIKit 层内容,拿不到
 * Compose 绘制的 4 能力卡,毛玻璃视觉在这套 UI 里出不来。放弃毛玻璃,
 * 走"白色不透明胶囊 + 强阴影"—— 苹果自家 Notes / Reminders / Podcasts
 * 的 tab bar 也是这个风格,视觉一样干净。
 *
 * 特点:
 *   - 悬浮在内容上方,不占 Column 布局空间
 *   - 纯白 Surface 底色 + 20dp 柔和阴影,悬浮感强
 *   - 圆角胶囊 32dp
 *   - 距 Home Indicator 顶(safe area)紧贴,左右各 40dp 内缩
 */
@Composable
private fun FloatingBottomBar(
    active: AppTab,
    onPick: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 排除 ime:键盘弹起时 TabBar 保持在 home indicator 上方(不跟随键盘上移),
    // 让键盘从下往上把 TabBar 遮住 —— 符合 iOS 编辑态标准行为
    // (Notes / Reminders 编辑时 tab bar 也是被键盘盖住)。
    val bottomInset = WindowInsets.safeDrawing
        .exclude(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(bottomInset)
            .padding(horizontal = 40.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(elevation = 20.dp, shape = RoundedCornerShape(32.dp), clip = false)
                .clip(RoundedCornerShape(32.dp))
                .background(UvpColor.Surface),
            contentAlignment = Alignment.BottomStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppTab.entries.forEachIndexed { index, tab ->
                    if (index == 2) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize())
                    } else {
                        BottomTabItem(
                            tab = tab,
                            selected = tab == active,
                            onClick = { onPick(tab) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            SimulateAccentButton(
                selected = active == AppTab.Simulate,
                onClick = { onPick(AppTab.Simulate) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
            )
        }
    }
}

/**
 * 半圆上凸的中间特色按钮 — UvpColor.Primary 实心圆,白色图标,
 * conic 渐变流转一圈(2.4s/圈)给"在线感",选中时光环更亮.
 */
@Composable
private fun SimulateAccentButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "simulate-accent")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep"
    )
    // 选中态外层光环更亮 + 微脉动
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = if (selected) 0.55f else 0.28f,
        targetValue = if (selected) 0.85f else 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo"
    )

    Box(
        modifier = modifier
            .size(54.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // 外层 conic 流光环
        Canvas(modifier = Modifier.size(54.dp)) {
            // 半透明背光
            drawCircle(
                color = UvpColor.Primary.copy(alpha = haloAlpha * 0.35f),
                radius = size.minDimension / 2f,
            )
            // conic 旋转扫光(渐隐尾巴 → 头部高亮)
            rotate(degrees = sweepAngle) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            UvpColor.Primary.copy(alpha = 0.0f),
                            UvpColor.Primary.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.95f),
                            UvpColor.Primary.copy(alpha = 0.55f),
                            Color.Transparent,
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        // 内层实心蓝圆
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            UvpColor.Primary,
                            UvpColor.PrimaryDark,
                        )
                    )
                )
                .border(
                    1.5.dp,
                    Color.White.copy(alpha = 0.6f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.ViewInAr,
                contentDescription = "模拟",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun BottomTabItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) UvpColor.Primary else UvpColor.TextHint
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(tab.icon(), contentDescription = tab.label,
            modifier = Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = tint
        )
    }
}

@Composable
private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.Home -> Icons.Outlined.Home
    AppTab.Capability -> Icons.Outlined.Extension
    AppTab.Simulate -> Icons.Outlined.ViewInAr
    AppTab.Log -> Icons.Outlined.Description
    AppTab.Settings -> Icons.Outlined.Settings
}
