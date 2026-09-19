package com.uvp.sim.ui.simulate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.StorageCardDto
import com.uvp.sim.ui.model.StorageCardReadingDto
import com.uvp.sim.ui.model.StorageCardStatusDto

/** 「亮起」从满亮衰减到熄灭的总时长。 */
internal const val STORAGE_CARD_HIGHLIGHT_MS = 1_800L

/** 卡片区最多平铺几张卡 —— 协议允许 8 张,全平铺会把 3D 画面糊掉。 */
internal const val MAX_VISIBLE_STORAGE_CARDS = 2

/** 卡片区固定宽度:盘面右侧留出 176dp,不与右下角 PtzThumbnail 抢位置。 */
private val StorageCardPanelWidth = 176.dp

// ⛔ 小字号必须**显式给行高** —— Material3 `bodyLarge` 的行高是 24.sp,`fontSize` 只改字号不改行框。
// 不覆盖的话这张 OSD 每行都按 24dp 记账(实测),整块会比设计高近 50dp,把 3D 画面糊掉一半。
private val OsdTitleLineHeight = 13.sp
private val OsdBodyLineHeight = 12.sp

/**
 * 「存储卡」卡片的三态。
 *
 * [NotQueried] 与 [NoCard] 必须分开:前者是「平台还没问过」,后者是「问了,设备确实没装」。
 * 两者都表现为 cards 为空,但设备屏幕上的说法完全不同 —— 合并了就会在平台查之前
 * 就摆出一块「未安装存储卡」的空态,那是设备在替平台编答案。
 */
internal sealed interface StorageCardPanelUi {
    data object NotQueried : StorageCardPanelUi
    data object NoCard : StorageCardPanelUi
    data class Cards(val rows: List<StorageCardRowUi>, val overflowCount: Int) : StorageCardPanelUi
}

internal data class StorageCardRowUi(
    val id: Int,
    val name: String,
    /** null = 这张卡没有读数(不该发生,但发生了要显示「无读数」而不是假装 ok)。 */
    val status: StorageCardStatusDto?,
    val capacityText: String,
    val detailText: String,
    val statusText: String,
)

/**
 * 由 Model 侧四个字段算出卡片区要显示什么 —— 纯函数,单测锁行为。
 *
 * 入参刻意用 `List` + `Map` 而不是整个 [DeviceControlDto]:卡片区只该读存储卡相关的
 * 那几个字段,别的字段变了不该让它重组。
 */
internal fun storageCardPanelUi(
    cards: List<StorageCardDto>,
    readings: Map<Int, StorageCardReadingDto>,
    queryCount: Int,
): StorageCardPanelUi {
    // 平台还没查过 —— 一个数字都不给,否则设备屏幕会先于平台"知道"容量。
    if (queryCount <= 0) return StorageCardPanelUi.NotQueried
    if (cards.isEmpty()) return StorageCardPanelUi.NoCard
    return StorageCardPanelUi.Cards(
        rows = cards.take(MAX_VISIBLE_STORAGE_CARDS).map { card ->
            val reading = readings[card.id]
            StorageCardRowUi(
                id = card.id,
                name = card.name,
                status = reading?.status,
                capacityText = formatCapacityMb(card.capacityMb),
                detailText = storageCardDetailText(reading),
                statusText = storageCardStatusText(reading?.status),
            )
        },
        overflowCount = (cards.size - MAX_VISIBLE_STORAGE_CARDS).coerceAtLeast(0),
    )
}

/** MB → 人读容量。1 GiB 起改用 GB(1 位小数),以下保持 MB。 */
internal fun formatCapacityMb(mb: Int): String {
    if (mb <= 0) return "0MB"
    if (mb < 1024) return "${mb}MB"
    val tenths = (mb.toLong() * 10L + 512L) / 1024L // 先取一位小数的十分位,避免浮点
    return "${tenths / 10}.${tenths % 10}GB"
}

private fun storageCardStatusText(status: StorageCardStatusDto?): String = when (status) {
    StorageCardStatusDto.Ok -> "正常"
    StorageCardStatusDto.Formatting -> "格式化中"
    StorageCardStatusDto.Unformatted -> "未格式化"
    StorageCardStatusDto.Idle -> "空闲"
    StorageCardStatusDto.Error -> "读写异常"
    null -> "无读数"
}

private fun storageCardDetailText(reading: StorageCardReadingDto?): String {
    if (reading == null) return "—"
    return when (reading.status) {
        StorageCardStatusDto.Ok -> "剩余 ${formatCapacityMb(reading.freeMb)}"
        // FormatProgress 协议上是 minOccurs=0,只有格式化中才带。缺了就只报状态,
        // 不能补一个 0% 出来 —— 那会让人以为格式化没启动。
        StorageCardStatusDto.Formatting ->
            reading.progress?.let { "格式化中 $it%" } ?: "格式化中"
        StorageCardStatusDto.Unformatted -> "未格式化,需先格式化"
        StorageCardStatusDto.Idle -> "空闲 · 剩余 ${formatCapacityMb(reading.freeMb)}"
        StorageCardStatusDto.Error -> "读写异常"
    }
}

private fun storageCardStatusColor(status: StorageCardStatusDto?): Color = when (status) {
    StorageCardStatusDto.Ok -> UvpColor.SuccessText
    StorageCardStatusDto.Formatting -> UvpColor.Primary
    StorageCardStatusDto.Unformatted -> UvpColor.Warning
    StorageCardStatusDto.Idle -> UvpColor.TextSecondary
    StorageCardStatusDto.Error -> UvpColor.DangerText
    null -> UvpColor.TextHint
}

/**
 * 模拟中心画面上的「存储卡」OSD 卡片(GB/T 28181-2022 A.2.4.14 / A.2.6.16)。
 *
 * 平台点「存储卡状态查询」→ 设备收到 SDCardStatus 请求 → [DeviceControlDto.storageCardQueryCount]
 * 自增 → 这张卡片整体亮一下再回落,同时把容量/剩余/状态换成这次应答里的读数。
 * 卡片上的数字与设备回给平台的报文出自**同一份读数**(shared 侧 `VirtualStorageCards` 单一真源),
 * 所以「屏幕显示 64.0GB / 剩余 32.0GB」和平台上看到的必然一致。
 *
 * 只读 OSD,不接任何点击 —— 与本层「只读装饰」约定一致。
 */
@Composable
internal fun StorageCardPanel(state: DeviceControlDto, modifier: Modifier = Modifier) {
    val ui = storageCardPanelUi(
        cards = state.storageCards,
        readings = state.storageCardReadings,
        queryCount = state.storageCardQueryCount,
    )

    // 亮起:每次查询计数 +1 → 快照到 1,再 1.8s 衰减到 0。
    // key 用**计数**而不是时间戳 —— 同一毫秒内的两次查询时间戳相同,key 不变,
    // 动效不会重播(device 侧 `withStorageCardQuery` 里对此有注释)。
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(state.storageCardQueryCount) {
        if (state.storageCardQueryCount <= 0) return@LaunchedEffect
        highlight.snapTo(1f)
        highlight.animateTo(0f, animationSpec = tween(durationMillis = STORAGE_CARD_HIGHLIGHT_MS.toInt()))
    }
    val h = highlight.value

    val trailing = when {
        state.storageCardQueryCount <= 0 -> "未查询"
        h > 0.01f -> "已收到查询"
        else -> "第 ${state.storageCardQueryCount} 次"
    }

    Column(
        modifier = modifier
            .width(StorageCardPanelWidth)
            .clip(RoundedCornerShape(10.dp))
            // 底卡沿用 PtzThumbnail 的白玻璃质感(3D 之上叠 OSD),亮起时向企业蓝偏。
            .background(lerp(Color.White.copy(alpha = 0.88f), UvpColor.PrimaryLight, h))
            .border(
                width = (1f + h).dp,
                color = lerp(UvpColor.BorderLight, UvpColor.Primary, h),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "存储卡",
                modifier = Modifier.weight(1f),
                fontSize = 10.sp,
                lineHeight = OsdTitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = if (h > 0.01f) UvpColor.Primary else UvpColor.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                trailing,
                fontSize = 9.sp,
                lineHeight = OsdBodyLineHeight,
                fontWeight = FontWeight.Medium,
                color = if (h > 0.01f) UvpColor.PrimaryDark else UvpColor.TextHint,
                maxLines = 1,
            )
        }

        when (ui) {
            StorageCardPanelUi.NotQueried ->
                StorageCardHint("等待平台下发存储卡查询")
            StorageCardPanelUi.NoCard ->
                StorageCardHint("未安装存储卡 (SumNum=0)")
            is StorageCardPanelUi.Cards -> {
                ui.rows.forEach { StorageCardRow(it) }
                if (ui.overflowCount > 0) {
                    StorageCardHint("另有 ${ui.overflowCount} 张未展示")
                }
            }
        }
    }
}

@Composable
private fun StorageCardHint(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        fontSize = 9.sp,
        lineHeight = OsdBodyLineHeight,
        fontWeight = FontWeight.Medium,
        color = UvpColor.TextHint,
        maxLines = 2,
    )
}

@Composable
private fun StorageCardRow(row: StorageCardRowUi) {
    val statusColor = storageCardStatusColor(row.status)
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                row.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 5.dp),
                fontSize = 10.sp,
                lineHeight = OsdTitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.capacityText,
                fontSize = 10.sp,
                lineHeight = OsdTitleLineHeight,
                fontWeight = FontWeight.Medium,
                color = UvpColor.TextSecondary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.detailText,
                modifier = Modifier.weight(1f),
                fontSize = 9.sp,
                lineHeight = OsdBodyLineHeight,
                color = UvpColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.statusText,
                fontSize = 9.sp,
                lineHeight = OsdBodyLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                maxLines = 1,
            )
        }
    }
}
