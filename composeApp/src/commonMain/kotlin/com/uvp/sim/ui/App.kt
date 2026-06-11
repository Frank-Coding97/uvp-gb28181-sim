package com.uvp.sim.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 4 屏 sim app 主框架: 顶栏 + 当前屏 + 底部 Tab。
 * 把所有 SipState / SimEvent / SimConfig 通过 [AppUiState] 一次性传入,
 * 所有用户动作通过 [AppActions] 回传 — commonMain 不依赖任何平台 API。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(state: AppUiState, actions: AppActions) {
    UvpTheme {
        var current by remember { mutableStateOf(AppTab.Home) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("UVP GB28181 Sim") },
                    colors = TopAppBarDefaults.topAppBarColors()
                )
            },
            bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == current,
                            onClick = { current = tab },
                            icon = { Icon(tab.icon(), contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background
            ) {
                when (current) {
                    AppTab.Home -> HomeScreen(state, actions)
                    AppTab.Config -> ConfigScreen(state, actions)
                    AppTab.Channel -> ChannelScreen(state)
                    AppTab.Log -> LogScreen(state)
                }
            }
        }
    }
}

@Composable
private fun AppTab.icon() = when (this) {
    AppTab.Home -> Icons.Outlined.Home
    AppTab.Config -> Icons.Outlined.Settings
    AppTab.Channel -> Icons.Outlined.Build
    AppTab.Log -> Icons.Outlined.Info
}
