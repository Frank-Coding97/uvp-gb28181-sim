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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.OsdConfig

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
        SettingsPage.Device -> SettingsSubPage(
            title = "设备",
            onBack = { page = SettingsPage.Index }
        ) { DeviceConfigScreen(state, actions) }
        SettingsPage.Media -> SettingsSubPage(
            title = "音视频",
            onBack = { page = SettingsPage.Index }
        ) { MediaScreen(state, actions) }
        SettingsPage.Osd -> SettingsSubPage(
            title = "OSD 水印",
            onBack = { page = SettingsPage.Index }
        ) { OsdSettingsPage(state, actions) }
        SettingsPage.Network -> SettingsSubPage(
            title = "网络",
            onBack = { page = SettingsPage.Index }
        ) { NetworkSettingsPage(state, actions) }
        SettingsPage.About -> SettingsSubPage(
            title = "关于",
            onBack = { page = SettingsPage.Index }
        ) { AboutScreen() }
    }
}

private enum class SettingsPage { Index, Channel, Device, Media, Osd, Network, About }

@Composable
private fun SettingsIndex(onPick: (SettingsPage) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsEntry(
            icon = Icons.Outlined.DevicesOther,
            title = "设备",
            description = "设备名称 · 注册周期 · 心跳",
            onClick = { onPick(SettingsPage.Device) }
        )
        SettingsEntry(
            icon = Icons.Outlined.PhotoCamera,
            title = "设备通道",
            description = "视频/报警通道 ID",
            onClick = { onPick(SettingsPage.Channel) }
        )
        SettingsEntry(
            icon = Icons.Outlined.MovieFilter,
            title = "音视频",
            description = "画质 · 编码 · 帧率 · 码率 · 采样率",
            onClick = { onPick(SettingsPage.Media) }
        )
        SettingsEntry(
            icon = Icons.Outlined.Layers,
            title = "OSD 水印",
            description = "时间戳 · 通道名 · 自定义水印",
            onClick = { onPick(SettingsPage.Osd) }
        )
        if (isNetworkSelectionSupported) {
            SettingsEntry(
                icon = Icons.Outlined.NetworkCheck,
                title = "网络",
                description = "Wi-Fi / 蜂窝 选择",
                onClick = { onPick(SettingsPage.Network) }
            )
        }
        SettingsEntry(
            icon = Icons.Outlined.Info,
            title = "关于",
            description = "版本 · 开源仓库 · 联系作者",
            onClick = { onPick(SettingsPage.About) }
        )
    }
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(10.dp))
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) UvpColor.PrimaryLight else UvpColor.BorderLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) UvpColor.Primary else UvpColor.TextHint)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) UvpColor.Text else UvpColor.TextHint)
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
    PlatformBackHandler(enabled = true, onBack = onBack)
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

/**
 * OSD 水印独立子页。本地草稿暂存编辑,点"保存"才落 onConfigSave 反映到渲染。
 * (位置已锁死、字段少,改成显式保存比即时热改更符合用户预期。)
 */
@Composable
private fun OsdSettingsPage(state: AppUiState, actions: AppActions) {
    val toast = LocalToastHost.current
    var draft by remember(state.config.osd) { mutableStateOf(state.config.osd) }
    val dirty = draft != state.config.osd

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OsdConfigCard(
            osd = draft,
            enabled = true,
            onChange = { draft = it }
        )
        Button(
            enabled = dirty,
            onClick = {
                actions.onConfigSave(state.config.copy(osd = draft))
                toast.success("OSD 配置已保存")
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UvpColor.Primary,
                disabledContainerColor = UvpColor.Border
            )
        ) {
            Text(
                if (dirty) "保存" else "已保存",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (dirty) Color.White else UvpColor.TextHint,
                letterSpacing = 2.sp
            )
        }
    }
}
