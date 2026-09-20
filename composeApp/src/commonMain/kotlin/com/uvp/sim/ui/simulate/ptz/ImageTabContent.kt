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
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.PictureMaskViewDto
import com.uvp.sim.ui.model.UpgradeProgressDto
import com.uvp.sim.ui.model.UpgradeResultDto

/**
 * 图像 Tab — 平台下发的**图像类配置与一次性事件**。
 *
 * ## 2026-09-19 重排（用户：「图像标签下的内容现在太乱了，需要整理一下」）
 *
 * 1. ⭐ **画面遮挡区域列表放最前** —— 设备屏幕上要能回答「平台把哪几块挡上了、坐标是多少」。
 *    遮挡本身已经从 Compose 画布搬去**烧进真实视频流**（Android `MaskPass` /
 *    iOS `IosFrameProcessor`），所以这里是它在设备侧**唯一**的可见面，必须完整可读
 *    （区域编号 + 四个坐标数字，不是"已配 2 项"这种统计）。
 * 2. **删掉「设备配置族」区块**：「统计行 + 视频参数属性 + 各项摘要 + 另有 N 项」这一摞
 *    挤在一个**定高 284dp、不滚动**的 Box 里，既挤掉了真正该看的东西，又只提供
 *    "平台已配 N 项"这一句话级别的信息。要看全 9 项请用平台侧面板。
 * 3. **删掉「拉框聚焦」行**：画布上的 `DragZoomOverlay` 线框已经直接看得到效果，
 *    这里再报一遍坐标是重复信息。
 * 4. **「请求关键帧」搬到「状态」页**（见 [StatusTabContent]）：它是"设备此刻在做什么"的
 *    一次性指示灯，与状态页那四盏灯同类，不是一条配置。
 * 5. **「最近命令」占位行删掉**（原来是永远存在的「暂无图像类指令」），
 *    改成**只在真有相关命令时出现** —— 空态占位行是"看起来热闹、实际什么都没说"的典型。
 *
 * ## 2026-09-20：「画面镜像」四卡片归位（A.2.1.23）
 *
 * 6. ⭐ 加回**「画面镜像」区块**（四张卡 + 点亮选中态）。三件事值得记下来：
 *
 *    - **这是归位而不是新增**：第 2 条删掉的「设备配置族」区块里就含镜像摘要行。
 *      当时 `MonitoringStage` 的注释还写着"翻转判据以 HUD 图像页的摘要行为准" ——
 *      那句注释指了半个月的空（现改写为：**镜像的判据是平台播放器上的画面**，
 *      本机 3D 画布不参与，2026-09-20 已把画布那处变换移除）。状态回显由本区块承担。
 *    - **必须在这一页**：`FrameMirror` 走 `DeviceConfig`，`deriveCommandCategory` 把它归到
 *      `Image` ⇒ 平台上点镜像，HUD **自动切到本页**。状态不在这页，操作员看到的是一次空白入场。
 *      （「状态」页真机只剩 ~5dp 余量，且语义是"设备在做什么"，两类。）
 *    - **不能点**：HUD 是平台指令的只读回放（唯一例外是云台页那份本机方盘）。
 *      镜像在标准里只有平台下发这一条写入通道，设备本地能改会让回读对账失真。
 *
 * ## 高度预算（HUD 内容区**定高 284dp、不滚动**，超出直接裁掉）
 * ```
 *   区块小标题            13dp（显式 lineHeight = 13.sp）
 *   遮挡区域行 4 × 26 = 104dp   ← 由 MAX_VISIBLE_MASK_REGIONS 封顶
 *   遮挡余数行（不合规报文才有）  26dp
 *   镜像小标题            13dp
 *   镜像四卡片行          28dp（6 + 14 行框 + 6 + 2 描边）
 *   升级进度条（罕见）    ≈ 53dp
 *   最近命令行            ≈ 26dp（与升级互斥：升级在跑时不报命令）
 *   行间距 7 × 6         = 42dp
 *   ──────────────────────────────
 *   最坏（5 块不合规报文⇒4 行 + 余数行 + 升级中）≈ 279dp  ✓ 余 5dp
 * ```
 * ⛔ 上面这个"最坏"已经是**能构造出来的上界**：遮挡行数由常量封顶、升级与最近命令互斥，
 * 所以再加内容前只需重算这道加法，不必担心"平台多配几块就溢出"。
 * ⛔ 行高来自 `ImageEventRow` **显式给了 `lineHeight = 14.sp`**：不显式给的话
 * `UvpTheme` 没覆写 `typography`，11.sp 的文字行框仍按 Material3 的 **24sp** 记账，
 * 一行就是 44dp，4 块遮挡 + 命令直接冲到 250dp 以上 —— 这类"字小不等于行矮"的坑见技能 §2。
 * ⚠️ **余量只剩 5dp**：本页再加任何一块，都必须先把加法重算一遍（`ImageEventRow`
 * 的上下内边距 2026-09-20 已从 10dp 收到 6dp 腾过一轮，那是唯一还可以再让的 8dp/行）。
 */
@Composable
internal fun ImageTabContent(state: DeviceControlDto) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ① 画面遮挡区域（用户要求的"首先展示"）—— 见上文第 1 条。
        MaskRegionSection(mask = state.deviceConfig.pictureMask)

        // ② 画面镜像（A.2.1.23）—— 2026-09-20 归位。与遮挡同页的理由见文件头第 6 条。
        MirrorSection(frameMirror = state.deviceConfig.frameMirror)

        // ③ GB-2022 §9.13 在线升级进度条（罕见的一次性事件，跑完自己消失）
        val upgrade = state.upgradeProgress
        if (upgrade != null) {
            UpgradeProgressRow(upgrade)
        }

        // ④ 最近一条图像类命令 —— 只在真有的时候出现（见上文第 5 条）
        if (upgrade == null) {
            val cmd = state.lastCommand
            val cmdLabel = when (cmd?.type) {
                "SnapShotCmd" -> "抓拍" to "已下发"
                "DeviceUpgrade" -> "设备升级" to "v${cmd.rawHex}"
                "FormatSDCard" -> "格式化 SD" to cmd.rawHex
                "TargetTrack" -> "目标跟踪" to cmd.rawHex
                else -> null
            }
            if (cmdLabel != null) {
                ImageEventRow(label = cmdLabel.first, value = cmdLabel.second, highlight = true)
            }
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
private fun UpgradeProgressRow(upgrade: UpgradeProgressDto) {
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
private fun ImageEventRow(
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
