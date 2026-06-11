package com.uvp.sim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App shell — 1:1 还原 index-v1.html 底栏 5 tab + 顶栏。
 *
 * 底栏:主页 / 配置 / 通道 / 日志 / 工具
 * 顶栏:标题 "GB28181 Sim" + 右侧 🔔(通知,M2) + ⚙(跳转配置)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(state: AppUiState, actions: AppActions) {
    UvpTheme {
        var currentTab by rememberSaveable { mutableStateOf(AppTab.Home) }
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "GB28181 Sim",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = UvpColor.Text
                        )
                    },
                    actions = {
                        IconButton(onClick = { /* notification: M2 */ }) {
                            Icon(Icons.Outlined.Notifications, "通知",
                                tint = UvpColor.TextSecondary)
                        }
                        IconButton(onClick = { currentTab = AppTab.Config }) {
                            Icon(Icons.Outlined.Settings, "设置",
                                tint = UvpColor.TextSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = UvpColor.Surface
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = UvpColor.Surface,
                    tonalElevation = 0.dp
                ) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    tab.icon(),
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = UvpColor.Primary,
                                selectedTextColor = UvpColor.Primary,
                                unselectedIconColor = UvpColor.TextHint,
                                unselectedTextColor = UvpColor.TextHint,
                                indicatorColor = UvpColor.PrimaryLight
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = UvpColor.Bg
            ) {
                when (currentTab) {
                    AppTab.Home -> HomeScreen(state, actions, onNavigate = { currentTab = it })
                    AppTab.Config -> ConfigScreen(state, actions)
                    AppTab.Channel -> ChannelScreen(state)
                    AppTab.Log -> LogScreen(state)
                    AppTab.Tool -> ToolScreen()
                }
            }
        }
    }
}

@Composable
private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.Home -> Icons.Outlined.Home
    AppTab.Config -> Icons.Outlined.Settings
    AppTab.Channel -> Icons.Outlined.Build
    AppTab.Log -> Icons.Outlined.Info
    AppTab.Tool -> Icons.Outlined.Build
}
