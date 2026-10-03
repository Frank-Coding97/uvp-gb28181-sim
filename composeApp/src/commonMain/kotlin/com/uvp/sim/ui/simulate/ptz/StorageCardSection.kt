package com.uvp.sim.ui.simulate.ptz

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.StorageCardDto
import com.uvp.sim.ui.model.StorageCardReadingDto
import com.uvp.sim.ui.model.StorageCardStatusDto

/**
 * 「存储卡」区块 —— GB/T 28181-2022 A.2.4.14(存储卡状态查询)/ A.2.6.16(应答)。
 *
 * **形态变更(2026-09-19)**:它原先是一张浮在 3D 画面右上角的 176dp 卡片。
 * 现在挂在 HUD **「状态」页**那 4 个大状态灯(录像 / 布防 / 报警 / 重启) **下方**
 * ——见 [StatusTabContent]。
 *
 * 为什么该在这里:存储卡和那 4 盏灯是**同一类东西** —— 都是"设备现在处于什么状态",
 * 属于平台下发过的状态的回显。放在画布上只会糊住 3D 画面,而且它与画面的几何位置
 * 没有任何协议关系(不像 OSD / 遮挡块那样本身是"画面内容")。收进状态页之后
 * 「设备状态」集中在一页,画布只留画面。
 *
 * 数据链:平台点「存储卡状态查询」→ 设备收 `SDCardStatus` → [DeviceControlDto.storageCardQueryCount]
 * 自增 → 本区块整体亮一下 → 同时换上本次应答里的读数。区块上的数字与回给平台的报文
 * 出自**同一份读数**(shared 侧 `VirtualStorageCards` 单一真源),所以「屏幕显示 64.0GB /
 * 剩余 32.0GB」和平台侧看到的必然一致。
 *
 * ⛔ 仍然是**只读**(spec AC2:设备 UI 不许成为第二个控制入口) —— 与同页的四盏灯一样,
 * 没有任何 `clickable`。
 *
 * ## 高度预算(硬预算,超了不会滚、直接没了)
 *
 * HUD 内容区是**定高 284dp、不滚动**的 `Box`(见 `PtzHudPanel`),而 `Column` 溢出时
 * 被裁掉的**往往正好是最后一行** —— 也就是本区块,即刚下发的那一项。
 * 这一页**已经被占掉大半**(见 `StatusTabContent` 的那道加法),轮到本区块只剩:
 *
 * ```
 * 284(HUD 内容区)
 *   − 136(两行大状态灯 2 × 68 —— 灯里的 Text 没给 lineHeight,按 24dp 行框记账)
 *   −  16(行间距 2 × 8)
 *   −  56(「请求关键帧」卡片)
 *   =  76dp;再减掉本区块自己那一条 8dp 行距 ⇒ 本区块**只有 68dp**
 *
 * 本区块 = 上下内边距 4+4 + 标题行 14 + 标题与内容间距 3 + 内容行数 × 17(行框 13 + 内边距 2×2)
 *   → 1 行(未查询 / 未安装 / 一张卡) = 42dp
 *   → 2 行(两张卡)                   = 59dp  ← 最坏情况,余 9dp
 * ```
 *
 * ⭐ **上面是纸面值,真机实测比它紧**:1080×2400 @440dpi 实测 —— 状态灯一行 68.4dp(纸面 68)、
 * 关键帧卡 57.5dp(纸面 56)、本区块 **61.5dp**(纸面 59,多出来的是 1dp 描边 + 取整),
 * 于是**区块底距内容区底只剩 13px = 4.7dp**。结论仍然是"没被裁",但余量只有 4.7dp ⇒
 * 这一页**没有任何腾挪空间**了,再想加东西必须先搬走一块(见 `StatusTabContent` 的加法)。
 * ⛔ 纸面算下来是 9dp,别拿纸面值下"还很宽敞"的判断 —— 每块都多一两 dp 就吃掉了。
 *
 * ⇒ 内容行数上限 [STORAGE_CARD_SECTION_MAX_LINES] = 2 = [MAX_VISIBLE_STORAGE_CARDS]。
 * ⛔ **余量提示走标题行右侧**(「另有 N 张未展示」)而**不新开一行** —— 这一页已经塞不下第 3 行。
 * 单测拿**同一组常量**算最坏情况,所以将来有人调大张数上限、或往标题下再塞一行时,
 * 会立刻变红,而不是等到真机上某一行被静默裁掉才发现。
 */

/** 「亮起」从满亮衰减到熄灭的总时长。 */
internal const val STORAGE_CARD_HIGHLIGHT_MS = 1_800L

/**
 * 区块里最多**摊开几张卡的明细** —— 协议允许 8 张(A.2.6.16 的 `Item` 是 `maxOccurs=8`),
 * 全平铺会顶破状态页所剩无几的高度预算。余量走标题行右侧的「另有 N 张未展示」。
 */
internal const val MAX_VISIBLE_STORAGE_CARDS = 2

/**
 * 本区块允许的最大内容行数 —— 见文件头那道加法。
 *
 * 与 [MAX_VISIBLE_STORAGE_CARDS] **必须相等**:摊开的卡一行一张,余量提示不占行。
 * 两者不等就说明"行数从哪来的"这条不变量被破坏了(多半是有人加回了余量行)。
 */
internal const val STORAGE_CARD_SECTION_MAX_LINES = 2

private val SectionShape = RoundedCornerShape(8.dp)

/**
 * 内容行的行距 = 行框 13dp + 上下内边距 2+2 = **17dp**。
 * 改这里要同步文件头那道加法(它决定本区块会不会把最后一行挤出 HUD)。
 */
private val RowPaddingV = 2.dp

// ⛔ 小字号必须**显式给行高** —— Material3 `bodyLarge` 的行高是 24.sp,`fontSize` 只改字号
// 不改行框。不覆盖的话这一行按 24dp 记账,上面那道加法整个失效 —— 而超出的部分是被**静默裁掉**的
// (同页的 BigStatusLamp 就是没给 lineHeight 的样本:11.sp 的字占 24dp 行框)。
private val TitleLineHeight = 14.sp
private val BodyLineHeight = 13.sp

/**
 * 固定宽的那几列 —— 两张卡时数字才对得齐。
 *
 * ⛔ 读数列**刻意不固定**:它是唯一会出现长文案的地方("未格式化,需先格式化"、"空闲 · 剩余
 * 128.0GB"),所以让它用 `weight(1f)` 吃掉名称列让出来的余量;名称列反而取固定宽 ——
 * 卡名是设备自己生成的(`SD Card N`),宽度有界,而且截断它无害(读数截断才有害)。
 */
private val NameColumnWidth = 110.dp
private val CapacityColumnWidth = 58.dp
private val StatusColumnWidth = 44.dp

/**
 * 「存储卡」区块的三态。
 *
 * [NotQueried] 与 [NoCard] 必须分开:前者是「平台还没问过」,后者是「问了,设备确实没装」。
 * 两者都表现为 cards 为空,但设备屏幕上的说法完全不同 —— 合并了就会在平台查之前
 * 就摆出一块「未安装存储卡」的空态,那是设备在替平台编答案。
 */
internal sealed interface StorageCardSectionUi {
    data object NotQueried : StorageCardSectionUi
    data object NoCard : StorageCardSectionUi
    data class Cards(val rows: List<StorageCardRowUi>, val overflowCount: Int) : StorageCardSectionUi
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
 * 由 Model 侧四个字段算出区块要显示什么 —— 纯函数,单测锁行为。
 *
 * 入参刻意用 `List` + `Map` 而不是整个 [DeviceControlDto]:区块只该读存储卡相关的
 * 那几个字段,别的字段变了不该让它重组。
 */
internal fun storageCardSectionUi(
    cards: List<StorageCardDto>,
    readings: Map<Int, StorageCardReadingDto>,
    queryCount: Int,
): StorageCardSectionUi {
    // 平台还没查过 —— 一个数字都不给,否则设备屏幕会先于平台"知道"容量。
    if (queryCount <= 0) return StorageCardSectionUi.NotQueried
    if (cards.isEmpty()) return StorageCardSectionUi.NoCard
    return StorageCardSectionUi.Cards(
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

/**
 * 本区块**实际要占几行** —— 供高度预算单测使用,不参与渲染。
 *
 * ⛔ 余量提示**不算行**:它在标题行右侧。这个函数的口径必须与 [StorageCardSection] 的
 * 渲染结构一致,否则高度预算的单测就是自己跟自己印证。
 */
internal fun storageCardSectionLineCount(ui: StorageCardSectionUi): Int = when (ui) {
    StorageCardSectionUi.NotQueried, StorageCardSectionUi.NoCard -> 1
    is StorageCardSectionUi.Cards -> ui.rows.size
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

/**
 * 读数列的文案 —— **只放读数 / 额外事实,状态词交给右边那一列(带色)**。
 *
 * ⛔ 这条分工是收成单行表格之后才立的:卡片时代"读数"与"状态"是**上下两行**,
 * 两行都写"读写异常"并不刺眼;收进一行之后会读成 `读写异常 读写异常`。
 * 所以这里不再重复状态列说的话,只回答"还剩多少 / 进度多少",没有数字就是 `—`。
 */
private fun storageCardDetailText(reading: StorageCardReadingDto?): String {
    val status = reading?.status ?: return "—"
    return when (status) {
        StorageCardStatusDto.Ok, StorageCardStatusDto.Idle -> "剩余 ${formatCapacityMb(reading.freeMb)}"
        // FormatProgress 协议上是 minOccurs=0,只有格式化中才带。缺了就只报 `—`,
        // 不能补一个 0% 出来 —— 那会让人以为格式化没启动。
        StorageCardStatusDto.Formatting -> reading.progress?.let { "进度 $it%" } ?: "—"
        // 未格式化 / 读写异常 / 无读数:没有"读数"可报,状态列已经说清楚了。
        StorageCardStatusDto.Unformatted, StorageCardStatusDto.Error -> "—"
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
 * 状态页里的「存储卡」区块 —— 大状态灯下面那块,由 [StatusTabContent] 摆放。
 *
 * 亮起:每次查询计数 +1 → 快照到 1,再 1.8s 衰减到 0;底色 / 描边一起向企业蓝偏,
 * 右上角换成「已收到查询」。这是"平台刚刚问过存储卡"这件事在设备屏幕上**唯一**的可见痕迹。
 * ⛔ `LaunchedEffect` 的 key 用**计数**不用时间戳 —— 同一毫秒内两次查询时间戳相同,
 * key 不变会让动效不重播(连点两次只亮一次)。
 */
@Composable
internal fun StorageCardSection(state: DeviceControlDto, modifier: Modifier = Modifier) {
    val ui = storageCardSectionUi(
        cards = state.storageCards,
        readings = state.storageCardReadings,
        queryCount = state.storageCardQueryCount,
    )

    val highlight = remember { Animatable(0f) }
    LaunchedEffect(state.storageCardQueryCount) {
        if (state.storageCardQueryCount <= 0) return@LaunchedEffect
        highlight.snapTo(1f)
        highlight.animateTo(0f, animationSpec = tween(durationMillis = STORAGE_CARD_HIGHLIGHT_MS.toInt()))
    }
    val h = highlight.value
    val lit = h > 0.01f

    // 标题行右侧那一格:先说"平台问过没有",再让位给"还有几张没摊开"(协议允许 8 张,
    // 而这里只放得下 MAX_VISIBLE_STORAGE_CARDS 行 —— 余量提示不新开行,见文件头预算)。
    val overflow = (ui as? StorageCardSectionUi.Cards)?.overflowCount ?: 0
    val trailing = when {
        state.storageCardQueryCount <= 0 -> "未查询"
        lit -> "已收到查询"
        overflow > 0 -> "另有 $overflow 张未展示"
        else -> "第 ${state.storageCardQueryCount} 次"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SectionShape)
            // 底卡沿用同页四盏灯的观感(浅灰底 + 细描边),亮起时向企业蓝偏。
            .background(lerp(UvpColor.Bg, UvpColor.PrimaryLight, h))
            .border(
                width = (1f + h).dp,
                color = lerp(UvpColor.BorderLight, UvpColor.Primary, h),
                shape = SectionShape,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "存储卡",
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                lineHeight = TitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = if (lit) UvpColor.Primary else UvpColor.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                trailing,
                fontSize = 9.sp,
                lineHeight = BodyLineHeight,
                fontWeight = FontWeight.Medium,
                color = if (lit) UvpColor.PrimaryDark else UvpColor.TextHint,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(3.dp))

        when (ui) {
            StorageCardSectionUi.NotQueried -> SectionHint("等待平台下发存储卡查询")
            StorageCardSectionUi.NoCard -> SectionHint("未安装存储卡 (SumNum=0)")
            is StorageCardSectionUi.Cards -> ui.rows.forEach { StorageCardRow(it) }
        }
    }
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RowPaddingV),
        fontSize = 9.sp,
        lineHeight = BodyLineHeight,
        fontWeight = FontWeight.Medium,
        color = UvpColor.TextHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StorageCardRow(row: StorageCardRowUi) {
    val statusColor = storageCardStatusColor(row.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Text(
            row.name,
            modifier = Modifier
                .width(NameColumnWidth)
                .padding(start = 6.dp),
            fontSize = 10.sp,
            lineHeight = BodyLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            row.capacityText,
            modifier = Modifier.width(CapacityColumnWidth),
            fontSize = 10.sp,
            lineHeight = BodyLineHeight,
            fontWeight = FontWeight.Medium,
            color = UvpColor.TextSecondary,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Text(
            row.detailText,
            // 读数列吃掉余量(见上面 NameColumnWidth 的注释),内缩 8dp 与容量列分开。
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            fontSize = 9.sp,
            lineHeight = BodyLineHeight,
            color = UvpColor.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            row.statusText,
            modifier = Modifier.width(StatusColumnWidth),
            fontSize = 9.sp,
            lineHeight = BodyLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = statusColor,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}
