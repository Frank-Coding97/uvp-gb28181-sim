package com.uvp.sim.ui.capability

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.gb28181.RecordSchedule
import com.uvp.sim.gb28181.RecordTimeSegment
import com.uvp.sim.gb28181.VideoRecordPlanConfig
import com.uvp.sim.recording.RecordPlanGrid
import com.uvp.sim.ui.UvpColor
import kotlin.math.abs

/** 时间条高度。7 行 + 间距 ≈ 190dp，整页仍可一屏内看到网格全貌。 */
private val TRACK_HEIGHT = 22.dp

/** 周几标签列宽。 */
private val DAY_LABEL_WIDTH = 38.dp

/** 每 6 格（= 3 小时）一道刻度线。 */
private const val TICKS_PER_DAY = 8

/**
 * 录像计划的**周时间网格**（`timeGridWeek`）视图。
 *
 * ## 为什么是"时间条"而不是"格子矩阵"
 *
 * 48 格 × 均分到 394dp 的竖屏，每格只有约 7dp——手点不准，画成一个个小方块还会显得稀碎。
 * 行业里（海康 / 大华 NVR 的时间表）实际做法就是**每天一条横向色带**：格子之间不留缝，
 * 连续选中的格子自然连成一条色带，用户关心的是"从几点到几点"，不是"第几格"。
 * 所以这里画的是 7 条时间条，靠 [RecordPlanGrid] 在内部按 30 分钟切格。
 *
 * ## 交互（drag-to-paint）
 *
 * - **按下**：决定这次是"涂"还是"擦"——按在空格上就是涂，按在已选格上就是擦。
 *   这样不需要额外的橡皮工具，和 NVR 的手感一致。
 * - **横拖**：沿手指经过的格子连续施加同一个动作，所以从 8 点拖到 12 点就是一段 4 小时。
 * - **抬起才提交**：拖动过程中只在本地累积，抬手指才转成 `TimeSegment` 交给上层。
 *   中途提交会因"每天最多 8 段"把半成品拦下来，用户会觉得涂到一半自己断了。
 *
 * ## ⛔ 有损表达
 *
 * 平台下发的 `08:07:30` 在网格里只能画成 `08:00`。差异由调用方用
 * [RecordPlanGrid.offGridCount] 检测并提示，本组件不擅自合并或隐藏这个事实。
 */
@Composable
internal fun RecordPlanGridCard(
    schedules: List<RecordSchedule>,
    onDaySegmentsChange: (weekDayNum: Int, segments: List<RecordTimeSegment>) -> Unit,
    onLimitReached: () -> Unit,
) {
    // 拖动中的那一行（day to 累积结果）。只可能有一行，所以不需要 map。
    val dragging = remember { mutableStateOf<Pair<Int, BooleanArray>?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每周计划", color = UvpColor.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("每格 30 分钟", color = UvpColor.TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))

            // 小时刻度：0 / 3 / 6 … 24，与下面的 8 等分对齐。
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(DAY_LABEL_WIDTH))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (tick in 0..TICKS_PER_DAY) {
                        Text(
                            "${tick * 24 / TICKS_PER_DAY}",
                            color = UvpColor.TextHint,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (day in 1..7) {
                    val segments = schedules.firstOrNull { it.weekDayNum == day }?.segments.orEmpty()
                    // ⛔ key 用 segments（data class，equals 可靠）：拖动过程中上层不更新，
                    // 引用稳定 ⇒ pointerInput 不会重启打断手势；提交后变了重启也无妨（手已抬起）。
                    val baseSlots = remember(day, segments) { RecordPlanGrid.slotsOf(segments) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            weekdayLabel(day),
                            color = UvpColor.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.width(DAY_LABEL_WIDTH),
                        )
                        DayTrack(
                            day = day,
                            baseSlots = baseSlots,
                            dragging = dragging,
                            verticalAlignment = Alignment.CenterVertically,
                            onCommit = { day, slots ->
                                val next = RecordPlanGrid.segmentsOf(slots)
                                if (next.size > VideoRecordPlanConfig.MAX_SEGMENTS_PER_DAY) {
                                    onLimitReached()
                                } else {
                                    onDaySegmentsChange(day, next)
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(UvpColor.Primary, "录像")
                Spacer(Modifier.width(12.dp))
                LegendDot(UvpColor.Bg, "不录")
                Spacer(Modifier.weight(1f))
                Text(
                    "按住横向拖动涂抹",
                    color = UvpColor.TextHint,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) {
            drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx()))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = UvpColor.TextHint, fontSize = 10.sp)
    }
}

/**
 * 一天的横向时间条 + 拖涂手势。
 *
 * 渲染优先用 [dragging] 里这一行的累积值：拖动中上层还没收到提交，用基准值画会出现
 * "手指划过去了但颜色没跟上"。手指抬起后 [dragging] 归 null，自然回落到上层下发的新值。
 */
@Composable
private fun DayTrack(
    day: Int,
    baseSlots: BooleanArray,
    dragging: MutableState<Pair<Int, BooleanArray>?>,
    verticalAlignment: Alignment.Vertical,
    onCommit: (Int, BooleanArray) -> Unit,
) {
    val shown = dragging.value?.takeIf { it.first == day }?.second ?: baseSlots

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .pointerInput(day, baseSlots) {
                awaitEachGesture {
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startSlot = slotAt(down.position.x, width)
                    // 按在空格上 = 涂；按在已选格上 = 擦。一次手势内方向不变，
                    // 否则手抖一下就会把刚涂的又擦掉。
                    val paint = !baseSlots[startSlot]
                    val working = baseSlots.copyOf()
                    applyRange(working, startSlot, startSlot, paint)
                    var lastSlot = startSlot
                    var painting = false

                    // ⛔ 先判方向再动手。早前按下即涂、且 consume 了全部事件，于是手指落在
                    // 时间条上想翻页时，页面不滚、反而凭空多出一格录像（实测把 3 天·3 段
                    // 刷成了"顺手一划就改配置"）。纵向滑动必须原样让给外层 verticalScroll，
                    // 只有横向拖动才算涂抹。
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val slop = viewConfiguration.touchSlop
                        if (!painting) {
                            if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                                return@awaitEachGesture
                            }
                            if (abs(dx) <= slop) continue
                            painting = true
                        }
                        val slot = slotAt(change.position.x, width)
                        if (slot != lastSlot) {
                            applyRange(working, lastSlot, slot, paint)
                            lastSlot = slot
                            // ⛔ 必须 copyOf：同一个数组改内容不会触发重组，
                            // 屏幕上会停在第一次按下的那一格。
                            dragging.value = day to working.copyOf()
                        }
                        change.consume()
                    }

                    // 点一下（没过滑动阈值）与横向拖完，走同一条提交路径。
                    onCommit(day, working)
                    dragging.value = null
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val radius = CornerRadius(3.dp.toPx())

        drawRoundRect(UvpColor.Bg, size = Size(w, h), cornerRadius = radius)

        // ⛔ 刻度必须画在色带**之前**（先底纹、后涂色）。反过来画过一次，淡灰竖线压在
        // 蓝色色带上会把一整条连续色带切成若干独立小块 —— 08:00-12:30 看着像"录了 3 段"，
        // 与实际的 1 段自相矛盾。刻度只用来读时间，不该盖在已选区域上。
        for (tick in 1 until TICKS_PER_DAY) {
            val x = w * tick / TICKS_PER_DAY
            drawLine(
                color = UvpColor.Border,
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 1f,
            )
        }

        var index = 0
        while (index < shown.size) {
            if (!shown[index]) {
                index += 1
                continue
            }
            var end = index
            while (end + 1 < shown.size && shown[end + 1]) end += 1
            val left = w * index / shown.size
            val right = w * (end + 1) / shown.size
            drawRoundRect(
                UvpColor.Primary,
                topLeft = Offset(left, 0f),
                size = Size(right - left, h),
                cornerRadius = radius,
            )
            index = end + 1
        }
    }
}

/** 屏幕横坐标 → 格子下标。 */
private fun slotAt(x: Float, width: Float): Int {
    val raw = (x / width * RecordPlanGrid.SLOTS_PER_DAY).toInt()
    return raw.coerceIn(0, RecordPlanGrid.SLOTS_PER_DAY - 1)
}

/** 把 [from]~[to] 之间（含两端）的格子统一置为 [paint]。 */
private fun applyRange(slots: BooleanArray, from: Int, to: Int, paint: Boolean) {
    val lo = minOf(from, to).coerceIn(0, slots.lastIndex)
    val hi = maxOf(from, to).coerceIn(0, slots.lastIndex)
    for (i in lo..hi) slots[i] = paint
}
