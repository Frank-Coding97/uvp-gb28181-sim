package com.uvp.sim.ui.simulate.ptz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.simulate.useTickingNow
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.PictureMaskViewDto
import com.uvp.sim.ui.model.UpgradeProgressDto
import com.uvp.sim.ui.model.UpgradeResultDto

/** 图像页：遮挡、镜像、关键帧及抓拍/跟踪反馈。 */
@Composable
internal fun ImageTabContent(state: DeviceControlDto) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ① 画面遮挡区域（用户要求的"首先展示"）—— 见上文第 1 条。
        MaskRegionSection(mask = state.deviceConfig.pictureMask)

        // ② 画面镜像（A.2.1.23）—— 2026-09-20 归位。与遮挡同页的理由见文件头第 6 条。
        MirrorSection(frameMirror = state.deviceConfig.frameMirror)

        KeyFrameRequestCard(isKeyFrameRequestActive(state.lastCommand, useTickingNow(intervalMs = 250L)))
        val cmd = state.lastCommand
        when (cmd?.type) {
            "SnapShotCmd" -> ImageEventRow("抓拍", "已收到平台请求", true)
            "TargetTrack" -> ImageEventRow("目标跟踪", cmd.rawHex, true)
        }
    }
}

/** 遮挡区块里的一行：[label] = 区域编号、[point] = 协议原始坐标 `左x,左y → 右x,右y`。 */
internal data class MaskRegionRow(
    val label: String,
    val point: String,
)

/**
 * 遮挡区块最多**摊开**几块。
 *
 * ⭐ 取 4 是因为这**就是协议上限**（A.2.1.17 `RegionList/Item maxOccurs="4"`）——
 * 与存储卡的 `MAX_VISIBLE_STORAGE_CARDS`（人为砍到 2）不是一回事：这里不是"从看得见的东西里砍",
 * 而是**兜住一份不合规的报文**。真来了 5 块，屏幕也只摊 4 行 + 一行余数，
 * 而不是把 HUD 那个**定高 284dp、不滚动**的内容区撑破 —— 撑破的后果是**最后一行被静默裁掉**。
 */
internal const val MAX_VISIBLE_MASK_REGIONS = 4

/** 遮挡区块的视图态：小标题 + 要罗列的行（行空时只剩小标题说明状态）+ 超限未展示的块数。 */
internal data class MaskSectionState(
    val title: String,
    val rows: List<MaskRegionRow>,
    /** 超过 [MAX_VISIBLE_MASK_REGIONS] 而未展示的块数。正常报文恒为 0。 */
    val hiddenCount: Int = 0,
)

/**
 * 遮挡区块的**展示规则** —— 抽成纯函数是为了能直接单测（范式同 `ptz/` 下其它 `*StateTest`）。
 *
 * ⛔ 四个分支**不能合并**，它们说的是四件不同的事，操作员据此该做的事也不同：
 * | 状态 | 屏幕文案 | 操作员该做什么 |
 * |---|---|---|
 * | 平台没配过 (`configured == false`) | 平台未配置 | 设备出厂就这样，不用管 |
 * | 配过但 `On=0` | 已停用 | 有人刚把它关了 —— 去平台确认是不是有意为之 |
 * | `On=1` 且无区域 | 已启用 · 无区域 | 开关开着但没画区域，等于没挡 |
 * | `On=1` 有区域 | 已启用 · N 个区域 | 正常态，逐块核对坐标 |
 *
 * ⚠️ **停用时不再罗列区域**（虽然协议允许 `On=0` 时保留 `RegionList`）。
 * 列出来会让人以为"这几块正挡着"——小标题写着"已停用"也救不回来，
 * 因为列表本身视觉上是"生效的东西在列队"。
 * ⇒ 要核对停用前的旧坐标，用平台侧面板；设备屏幕只回答"现在挡没挡、挡哪几块"。
 *
 * ⚠️ 按 `seq` 排序：平台下发的 `Seq` 允许稀疏（标准只规定范围 1~4、不要求连续），
 * 屏幕上按编号排才和平台列表对得上 —— ⛔ 但**不要重排编号本身**
 * （重编等于给设备上的区域静默改名，平台回读对账反而可能"一致"）。
 */
internal fun maskSectionState(mask: PictureMaskViewDto): MaskSectionState = when {
    !mask.configured -> MaskSectionState("画面遮挡 · 平台未配置", emptyList())
    !mask.on -> MaskSectionState("画面遮挡 · 已停用", emptyList())
    mask.rects.isEmpty() -> MaskSectionState("画面遮挡 · 已启用 · 无区域", emptyList())
    else -> {
        val ordered = mask.rects.sortedBy { it.seq }
        MaskSectionState(
            // 小标题报**总数**、行只摊前 [MAX_VISIBLE_MASK_REGIONS] 块 —— 数与行对不上时，
            // 余数行会把差额补上（见 [MaskRegionSection]），不会出现"报了 6 块只看见 4 块"。
            title = "画面遮挡 · 已启用 · ${ordered.size} 个区域",
            rows = ordered.take(MAX_VISIBLE_MASK_REGIONS).map {
                MaskRegionRow(label = "区域 ${it.seq}", point = it.displayLabel)
            },
            hiddenCount = (ordered.size - MAX_VISIBLE_MASK_REGIONS).coerceAtLeast(0),
        )
    }
}

/**
 * 「画面遮挡」只读区块 —— 小标题 + 逐区域一行。
 *
 * ⭐ 这是遮挡在设备侧唯一的可见面（它已烧进视频流、画布上不再画黑块），
 * 所以坐标用**协议原始像素**完整给出，让操作员能逐块与平台面板上的数字对照。
 */
@Composable
private fun MaskRegionSection(mask: PictureMaskViewDto) {
    val section = maskSectionState(mask)
    Text(
        section.title,
        fontSize = 10.sp,
        // ⛔ 显式给 lineHeight：不给就按 Material3 的 24.sp 记账（见文件头高度预算）。
        lineHeight = 13.sp,
        color = UvpColor.TextHint,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
    )
    for (row in section.rows) {
        ImageEventRow(label = row.label, value = row.point, highlight = true)
    }
    // 余数行（正常报文恒不出现，见 [MAX_VISIBLE_MASK_REGIONS]）—— 报出来才不会
    // 让人以为"平台只配了 4 块"，而同页小标题上明明写着总数。
    if (section.hiddenCount > 0) {
        ImageEventRow(label = "另有", value = "${section.hiddenCount} 块未展示", highlight = false)
    }
}

// ===== 画面镜像（GB/T 28181-2022 A.2.1.23 `FrameMirror`）=====

/** 镜像四卡片里的一张。[selected] = 平台下发的就是这一项。 */
internal data class MirrorOptionCard(
    val label: String,
    val selected: Boolean,
)

/** 镜像区块的视图态：小标题（含"平台配过没有"）+ 恒为四张的卡。 */
internal data class MirrorSectionState(
    val title: String,
    val cards: List<MirrorOptionCard>,
)

/**
 * 四张卡的短文案，**顺序即 A.2.1.23 的 `0/1/2/3`**。
 *
 * ⛔ 用短词（"左右"/"上下"）而不是 `FrameMirrorState.displayLabel` 那种全称：
 * 一张卡宽约 78dp，全称会被挤成省略号，而完整口径在平台面板上就写着。
 * ⛔ 顺序**不能**照别处的历史版本改 —— 本仓前端 `MIRROR_OPTIONS` 曾按海康 ISP 口径把
 * 1/2 写反（国标是 1=水平、2=上下）。这里与上面对齐。
 */
private val MIRROR_OPTION_LABELS = listOf("不启用", "左右", "上下", "中心")

/**
 * 镜像区块的**展示规则** —— 抽成纯函数以便直接单测（范式同 [maskSectionState]）。
 *
 * ⛔ 三个分支说的是三件事，**不能合并**：
 * | 状态 | 屏幕文案 | 操作员该做什么 |
 * |---|---|---|
 * | 平台没配过 (`null`) | 平台未配置 | 设备出厂就这样，四个卡**一个都不点亮** |
 * | 配过 0~3 | 平台已下发 | 点亮对应那张卡，核对是不是自己要的 |
 * | 越界值 | 取值非法（N） | 协议层本该拒掉，出现即为缺陷 —— 报出来而不是装作没看到 |
 *
 * ⭐ `null` 与 `0` **必须分开**，这是本页唯一能分清「平台没配过」与「平台把它关掉了」的
 * 地方（两者在画面上完全一样）—— 同存储卡「没查过 / 查了但没卡」的规矩。
 * ⇒ 未配置时**四张卡全灰**，不代表任何一张"选中"：设备不能替平台先认领一个答案。
 */
internal fun mirrorSectionState(frameMirror: Int?): MirrorSectionState {
    val allOff = MIRROR_OPTION_LABELS.map { MirrorOptionCard(label = it, selected = false) }
    if (frameMirror == null) {
        return MirrorSectionState(title = "画面镜像 · 平台未配置", cards = allOff)
    }
    if (frameMirror !in MIRROR_OPTION_LABELS.indices) {
        return MirrorSectionState(title = "画面镜像 · 取值非法（$frameMirror）", cards = allOff)
    }
    return MirrorSectionState(
        title = "画面镜像 · 平台已下发",
        cards = MIRROR_OPTION_LABELS.mapIndexed { index, label ->
            MirrorOptionCard(label = label, selected = index == frameMirror)
        },
    )
}

/**
 * 「画面镜像」只读区块 —— 小标题 + 四张卡横排，选中态蓝底蓝边。
 *
 * ⛔ **卡片不可点**：HUD 是平台指令的只读回放（唯一的例外是云台页那份本机方盘）。
 * 加 `clickable` 会让"设备本地也能改镜像"成为可能 —— 而标准里镜像只有平台下发
 * 这一条写入通道，设备本地能改就会让回读对账失真。判据是"有没有可交互修饰符"。
 */
@Composable
private fun MirrorSection(frameMirror: Int?) {
    val section = mirrorSectionState(frameMirror)
    Text(
        section.title,
        fontSize = 10.sp,
        // ⛔ 显式给 lineHeight：不给就按 Material3 的 24.sp 记账（见文件头高度预算）。
        lineHeight = 13.sp,
        color = UvpColor.TextHint,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (card in section.cards) {
            MirrorOptionChip(card = card, modifier = Modifier.weight(1f))
        }
    }
}

/** 四张卡里的一张。高度 28dp（6+14+6 行框 + 2 描边），与 [ImageEventRow] 的 26dp 接近。 */
@Composable
private fun MirrorOptionChip(card: MirrorOptionCard, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (card.selected) UvpColor.PrimaryLight else UvpColor.Bg)
            .border(
                1.dp,
                if (card.selected) UvpColor.Primary else UvpColor.BorderLight,
                RoundedCornerShape(6.dp),
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            card.label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = if (card.selected) UvpColor.Primary else UvpColor.TextHint,
            fontWeight = if (card.selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** GB-2022 §9.13 在线升级进度条 — 平台 DeviceUpgrade 触发,5s 假进度 0/30/60/100. */
@Composable
internal fun UpgradeProgressRow(upgrade: UpgradeProgressDto) {
    val statusText = when (upgrade.result) {
        UpgradeResultDto.InProgress -> "升级中"
        UpgradeResultDto.Success -> "升级成功"
        UpgradeResultDto.Failure -> "升级失败"
    }
    val statusColor = when (upgrade.result) {
        UpgradeResultDto.InProgress -> UvpColor.Primary
        UpgradeResultDto.Success -> UvpColor.Success
        UpgradeResultDto.Failure -> UvpColor.Danger
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.PrimaryLight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "在线升级 v${upgrade.firmware}",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = UvpColor.Primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                statusText,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = statusColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${upgrade.percent}%",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(UvpColor.BorderLight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(upgrade.percent / 100f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(statusColor)
            )
        }
    }
}

/**
 * 一行「标签 → 值」。**行高 26dp**（`6 + 14 + 6`）。
 *
 * ⛔ 两个 `Text` 都**必须显式给 `lineHeight`**：`UvpTheme` 只往 `MaterialTheme` 传了
 * `colorScheme`、没有覆写 `typography`，所以 `fontSize = 11.sp` 的实际行框仍是
 * Material3 `bodyLarge` 的 **24sp** ——「字小」不等于「行矮」，一行会白占 44dp，
 * 4 块遮挡就能把定高 284dp 的 HUD 内容区撑爆（见文件头高度预算）。
 *
 * ⚠️ 2026-09-20：上下内边距由 10dp 收到 6dp（行高 34 → 26），腾出的 4×8dp 用来给
 * 「画面镜像」区块 —— **不是**为了紧凑而紧凑，是那一块的加法（见文件头）唯一的出处。
 */
@Composable
internal fun ImageEventRow(
    label: String,
    value: String,
    highlight: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) UvpColor.PrimaryLight else UvpColor.Bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = if (highlight) UvpColor.Primary else UvpColor.TextSecondary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = if (highlight) UvpColor.PrimaryDark else UvpColor.TextHint,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
