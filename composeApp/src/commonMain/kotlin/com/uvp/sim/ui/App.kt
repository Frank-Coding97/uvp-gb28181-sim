package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App shell — 紧凑顶/底栏 + 3 tab + 全局 UvpToast。
 *
 * 自定义顶栏(36dp)和底栏(56dp)代替 Material3 默认的 64/80dp,
 * 给主屏内容腾出关键的 30+ dp 高度,让"注册"按钮回到一眼可见的范围。
 */
@Composable
fun App(state: AppUiState, actions: AppActions) {
    UvpTheme {
        var currentTab by rememberSaveable { mutableStateOf(AppTab.Home) }
        UvpToastHost {
            Column(modifier = Modifier.fillMaxSize().background(UvpColor.Bg)) {
                CompactTopBar()
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = UvpColor.Bg
                ) {
                    when (currentTab) {
                        AppTab.Home -> HomeScreen(state, actions)
                        AppTab.Capability -> com.uvp.sim.ui.capability.CapabilityScreen(state, actions)
                        AppTab.Settings -> SettingsScreen(state, actions)
                        AppTab.Recording -> RecordingScreen(state, actions)
                        AppTab.Log -> LogScreen(state)
                    }
                }
                CompactBottomBar(currentTab) { currentTab = it }
            }
        }
    }
}

@Composable
private fun CompactTopBar() {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.Surface)
                .height(40.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "GB28181 Sim",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { /* notification: M2 */ }
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = "通知",
                    tint = UvpColor.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
    }
}

@Composable
private fun CompactBottomBar(active: AppTab, onPick: (AppTab) -> Unit) {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.Surface)
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
                BottomTabItem(
                    tab = tab,
                    selected = tab == active,
                    onClick = { onPick(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
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
    AppTab.Capability -> Icons.Outlined.Apps
    AppTab.Settings -> Icons.Outlined.Tune
    AppTab.Recording -> Icons.Outlined.Videocam
    AppTab.Log -> Icons.Outlined.Receipt
}
