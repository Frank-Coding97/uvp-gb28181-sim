package com.uvp.sim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 主屏 Home — 产品经理视角重构。
 *
 * 核心原则:用户在这一屏完成所有操作,不跳转。
 * - 状态 banner (失败时直接展示原因)
 * - 视频预览
 * - SIP 配置摘要 (内联编辑,7 字段)
 * - 抓拍按钮 (仅 M1 可用的主动业务,不展示 disabled 的)
 * - 注册/注销
 * - 底部"高级设置"折叠(通道ID/用户名/密码/注册参数)
 *
 * 各子组件已拆到 [home/] 子目录,本文件仅保留入口装配。
 */
@Composable
fun HomeScreen(state: AppUiState, actions: AppActions) {
    val scroll = rememberScrollState()
    val toast = LocalToastHost.current
    // iOS 悬浮 tab bar 需要额外底部 padding 让最后一行不被遮:
    //   safe area 34dp + tab bar 64dp + 上下 8dp 呼吸 = ~100dp
    // Android 用 docked tab bar 自己占布局空间,不需要额外 padding。
    val extraBottom = if (isFloatingBottomBar) 100.dp else 0.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + extraBottom),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 顶部 banner:状态 + 注册 CTA 合一,首屏一眼可见
        StatusBanner(
            state = state, actions = actions,
            onFeedback = { msg -> toast.info(msg) }
        )
        BroadcastIndicator(state, actions)
        CameraPreviewBox(state)
        SipConfigCard(state, actions, onFeedback = { msg ->
            toast.success(msg)
        })
        ActionButtons(state, actions, onFeedback = { msg ->
            toast.success(msg)
        })
        // 底部 ConnectButton 已合并进 StatusBanner 右侧,不再单独显示
    }
}
