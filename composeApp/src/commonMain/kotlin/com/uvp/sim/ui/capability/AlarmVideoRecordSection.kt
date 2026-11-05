package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.gb28181.VideoAlarmRecordState
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.UvpSwitch
import com.uvp.sim.ui.model.VideoAlarmRecordViewDto

/**
 * 报警子页的「报警录像」配置区（GB/T 28181-2022 A.2.1.16 `VideoAlarmRecord`）。
 *
 * ⭐ 为什么放在报警页而不是能力页单开一张卡：这份配置只有 4 个字段，且语义就是
 * 「报警时怎么录」—— 与「报什么警」是同一件事的两面，用户心智上不该跳两个页面。
 * 能力页的卡片留给"有独立工作流的模块"（目录、录像列表、录像计划）。
 *
 * 草稿用 `remember(view)`：保存后 state 刷新成新值、草稿跟着重置；编辑期间平台下发新值
 * 也会覆盖草稿（配置以最后写入者为准，与 `VideoRecordPlanScreen` 同一条口径）。
 */
@Composable
internal fun AlarmVideoRecordSection(
    view: VideoAlarmRecordViewDto,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (VideoAlarmRecordState?) -> Unit,
) {
    val toast = LocalToastHost.current
    val saved = remember(view) {
        VideoAlarmRecordState(
            recordEnable = if (view.enabled) 1 else 0,
            recordTime = view.recordTimeSeconds,
            preRecordTime = view.preRecordTimeSeconds,
            streamNumber = view.streamNumber,
        )
    }
    var draft by remember(view) { mutableStateOf(saved) }
    val dirty = draft != saved

    CollapsibleHeader(
        icon = Icons.Outlined.Videocam,
        title = "报警录像",
        // 折叠区的 count 用「当前状态」而不是字段数：收起来时也要能看出开没开。
        count = if (view.enabled) 1 else 0,
        expanded = expanded,
        onToggle = onToggle,
    )

    if (expanded) {
        Column(
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 生效状态一句话：现场最常问的是"现在到底录不录"。
            Text(
                when {
                    view.enabled && view.recordTimeSeconds == null ->
                        "已启用：报警后录 30 秒（未配延时，用默认值）"
                    view.enabled ->
                        "已启用：报警后录 ${view.recordTimeSeconds} 秒"
                    view.configured -> "已配置 · 未启用"
                    else -> "出厂默认：不启用"
                },
                fontSize = 11.sp,
                color = if (view.enabled) UvpColor.Success else UvpColor.TextSecondary,
            )

            // 开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UvpColor.Surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("报警时自动录像", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UvpColor.Text)
                    Text("RecordEnable", fontSize = 10.sp, color = UvpColor.TextHint)
                }
                UvpSwitch(
                    checked = draft.recordEnable == 1,
                    onCheckedChange = { on ->
                        draft = draft.copy(recordEnable = if (on) 1 else 0)
                    },
                )
            }

            // 报警后延时（RecordTime）
            FieldLabel("报警后录像时长 RecordTime")
            OptionChips(
                options = RECORD_TIME_OPTIONS,
                selected = draft.recordTime,
                labelOf = { it?.let { s -> "${s}s" } ?: "未设置" },
                onSelect = { draft = draft.copy(recordTime = it) },
            )

            // 报警前预录（PreRecordTime）
            FieldLabel("报警前预录 PreRecordTime")
            OptionChips(
                options = PRE_RECORD_TIME_OPTIONS,
                selected = draft.preRecordTime,
                labelOf = { it?.let { s -> "${s}s" } ?: "未设置" },
                onSelect = { draft = draft.copy(preRecordTime = it) },
            )
            Text(
                "⚠️ 预录只回读、不实际录像：手机端没有环形缓存，报警前的画面确实没录到。",
                fontSize = 10.sp,
                color = UvpColor.TextHint,
                lineHeight = 14.sp,
            )

            // 码流
            FieldLabel("码流编号 StreamNumber")
            OptionChips(
                options = STREAM_NUMBERS,
                selected = draft.streamNumber,
                labelOf = { n -> if (n == 0) "主码流" else "子码流$n" },
                onSelect = { draft = draft.copy(streamNumber = it) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onSave(null)
                        toast.info("已恢复出厂默认（报警不录像）")
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("恢复出厂", fontSize = 12.sp) }

                Button(
                    onClick = {
                        onSave(draft)
                        toast.success(
                            if (draft.recordEnable == 1) {
                                "报警录像已保存：${draft.recordTime ?: 30} 秒 · " +
                                    if (draft.streamNumber == 0) "主码流" else "子码流${draft.streamNumber}"
                            } else {
                                "报警录像已保存（未启用）"
                            }
                        )
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary),
                ) {
                    Text(if (dirty) "保存修改" else "保存", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 单选小方块。`null` 单独占一个选项（"未设置"）—— 它与"配了 0 秒"是两件事，
 * 不能用一个 0 代替（见 `VideoAlarmRecordState` 的注释）。
 */
@Composable
private fun <T> OptionChips(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            val sel = opt == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .background(
                        if (sel) UvpColor.PrimaryLight else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .border(
                        1.dp,
                        if (sel) UvpColor.Primary else UvpColor.Border,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(opt) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labelOf(opt),
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) UvpColor.Primary else UvpColor.TextSecondary,
                )
            }
        }
    }
}

/** `RecordTime` 候选：未设置（null）/ 0（报警即停）/ 常见段长。 */
private val RECORD_TIME_OPTIONS: List<Int?> = listOf(null, 0, 10, 30, 60)

/** `PreRecordTime` 候选：未设置 / 0 / 常见预录窗口。 */
private val PRE_RECORD_TIME_OPTIONS: List<Int?> = listOf(null, 0, 5, 10, 15)

/** `StreamNumber` 候选：与录像计划页面同一组（主码流 + 两路子码流）。 */
private val STREAM_NUMBERS = listOf(0, 1, 2)
