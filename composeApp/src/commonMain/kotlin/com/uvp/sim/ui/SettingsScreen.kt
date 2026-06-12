package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置 — 二级导航,展示"通道""音视频"两张大卡片,点击进入对应配置子页。
 *
 * 内部用 var page by remember 管理子页栈,避免引入 Navigation Compose
 * 重型依赖。子页内顶部有返回按钮回到入口。
 */
@Composable
fun SettingsScreen(state: AppUiState, actions: AppActions) {
    var page by remember { mutableStateOf(SettingsPage.Index) }
    when (page) {
        SettingsPage.Index -> SettingsIndex(onPick = { page = it })
        SettingsPage.Channel -> SettingsSubPage(
            title = "通道",
            onBack = { page = SettingsPage.Index }
        ) { ChannelScreen(state, actions) }
        SettingsPage.Media -> SettingsSubPage(
            title = "音视频",
            onBack = { page = SettingsPage.Index }
        ) { MediaScreen(state, actions) }
    }
}

private enum class SettingsPage { Index, Channel, Media }

@Composable
private fun SettingsIndex(onPick: (SettingsPage) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsEntry(
            icon = Icons.Outlined.PhotoCamera,
            title = "通道",
            description = "通道列表 · 视频/报警通道ID · 密码 · 心跳",
            onClick = { onPick(SettingsPage.Channel) }
        )
        SettingsEntry(
            icon = Icons.Outlined.MovieFilter,
            title = "音视频",
            description = "画质 · 编码 · 帧率 · 码率 · 采样率",
            onClick = { onPick(SettingsPage.Media) }
        )
    }
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(UvpColor.PrimaryLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null,
                modifier = Modifier.size(20.dp), tint = UvpColor.Primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = UvpColor.Text)
            Spacer(Modifier.height(2.dp))
            Text(description, fontSize = 11.sp, color = UvpColor.TextSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null,
            modifier = Modifier.size(20.dp), tint = UvpColor.TextHint)
    }
}

@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.Surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .padding(8.dp)
            ) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回",
                    modifier = Modifier.size(20.dp), tint = UvpColor.Text)
            }
            Spacer(Modifier.width(4.dp))
            Text(title, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = UvpColor.Text)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
        content()
    }
}
