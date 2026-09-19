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
import com.uvp.sim.gb28181.FrontOsdConfig

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
        ) { AboutScreen(onOpenLicenses = { page = SettingsPage.Licenses }) }
        SettingsPage.Licenses -> SettingsSubPage(
            title = "开源许可",
            onBack = { page = SettingsPage.About }
        ) { OpenSourceLicensesScreen() }
    }
}

private enum class SettingsPage { Index, Channel, Device, Media, Osd, Network, About, Licenses }

@Composable
private fun SettingsIndex(onPick: (SettingsPage) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                // 悬浮 tab bar 底部预留(iOS 130dp / 其他 0dp)
                bottom = 12.dp + floatingBottomBarReservedBottom
            ),
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
    // SubPageContainer 提供 tab bar 自动隐藏 + iOS 左边缘 swipe-back 手势。
    SubPageContainer(onBack = onBack) {
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
}

/**
 * OSD 水印独立子页 —— **一份配置、两个入口**。
 *
 * 上面那张卡是「本机预览叠加」（行业 IPC 口径的三层），下面那张是「GB/T 28181 前端 OSD」
 * （协议的绝对像素口径）。真机上这两者本就是同一份配置的两张脸，所以这里让它们**互相联动**：
 * 改一边，另一边立刻按对方的换算规则跟随。
 *
 * 保存时按**最后编辑的那一侧**决定写哪一份（见 [OsdEditSource]）：
 *  - 改本机三层 → 写 `config.osd` + **清掉平台值**：设备当前的 OSD 改由本地方案决定，
 *    回读走 `defaultFor` 派生。不清的话平台会一直读到一份"自己配过的旧值"，
 *    而屏幕上显示的已经是本地新值 —— 两边对不上且**看起来都正常**；
 *  - 改国标区块 → 写 `frontOsd`（回读/回显以它为准）+ 把投影结果写进 `config.osd`，
 *    让本机预览跟着变。
 *
 * ⚠️ 国标 → 本机是**有损**的（多条目 / 绝对坐标 / 本机没有字号颜色），
 * 损失清单见 `FrontOsdConfig.toLocalOsd` 的 KDoc —— 那是模型不同构造成的，不是实现偷懒。
 */
@Composable
private fun OsdSettingsPage(state: AppUiState, actions: AppActions) {
    val toast = LocalToastHost.current
    val config = state.config
    val platformFrontOsd = state.deviceControl.deviceConfig.frontOsd
    // 生效的国标值：平台下发过就用平台的，否则按本机三层派生。
    // ⛔ 走 FrontOsdConfig.effective 这**一个**入口，别在这里自己写 `?: defaultFor(...)` ——
    //    两处各判一次，改一处就有一处还是老行为，而两边都显示得出东西。
    val effectiveFrontOsd = remember(config, platformFrontOsd) {
        FrontOsdConfig.effective(config, platformFrontOsd)
    }

    // ⭐ 本机三层那张卡的**基线**：平台配过 OSD 时，设备当前的前端 OSD 以平台值为准，
    //    所以本机卡要显示的是"投影后的样子"（那才是屏幕上真的在画的东西）。
    //    ⛔ 平台没配过时**不能**也走一遍投影：`toLocalOsd(defaultFor(config), config.osd)`
    //    与 `config.osd` 并不相等（正反两个方向本就是有损的一对），
    //    会把页面一打开就标成"已修改"，按下保存还会顺手清掉平台值。
    val localBaseline = remember(config.osd, effectiveFrontOsd, platformFrontOsd) {
        if (platformFrontOsd == null) config.osd else FrontOsdConfig.toLocalOsd(effectiveFrontOsd, config.osd)
    }

    var draft by remember(localBaseline) { mutableStateOf(localBaseline) }
    var gbDraft by remember(effectiveFrontOsd) { mutableStateOf(effectiveFrontOsd) }
    var lastEdited by remember { mutableStateOf(OsdEditSource.None) }

    val dirty = draft != localBaseline || gbDraft != effectiveFrontOsd

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OsdConfigCard(
            osd = draft,
            enabled = true,
            onChange = {
                draft = it
                // 改本机三层 → 国标区块按**正向**换算跟随（与回读应答同一条规则 defaultFor）。
                gbDraft = FrontOsdConfig.defaultFor(config.copy(osd = it))
                lastEdited = OsdEditSource.Local
            }
        )
        FrontOsdCard(
            state = gbDraft,
            fromPlatform = platformFrontOsd != null,
            enabled = true,
            onChange = {
                gbDraft = it
                // 改国标 → 本机三层按**反向投影**跟随（有损，见 FrontOsdConfig.toLocalOsd）。
                draft = FrontOsdConfig.toLocalOsd(it, draft)
                lastEdited = OsdEditSource.Gb
            }
        )
        Button(
            enabled = dirty,
            onClick = {
                val fromGb = lastEdited == OsdEditSource.Gb
                actions.onConfigSave(config.copy(osd = draft))
                // 国标面编辑过 → 以它为准；否则清除平台值、回到出厂派生。
                actions.onFrontOsdSave(if (fromGb) gbDraft else null)
                toast.success(
                    if (fromGb) "前端 OSD 已保存（平台可回读）" else "OSD 配置已保存（本机）"
                )
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

/**
 * 「最后编辑的是哪一侧」—— 决定保存时哪份数据成为权威。
 *
 * 两侧同页且互相联动，所以"同时改两边"实际等价于"最后一次改的那边说了算"：
 * 联动已经让另一侧跟随过了，不必再让它赢。
 */
private enum class OsdEditSource { None, Local, Gb }
