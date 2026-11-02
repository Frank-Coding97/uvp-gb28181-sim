package com.uvp.sim.ui.capability

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.gb28181.RecordSchedule
import com.uvp.sim.gb28181.RecordTimeSegment
import com.uvp.sim.gb28181.VideoRecordPlanConfig
import com.uvp.sim.gb28181.VideoRecordPlanState
import com.uvp.sim.recording.LocalClockReading
import com.uvp.sim.recording.LocalWallClock
import com.uvp.sim.recording.RecordPlanEvaluator
import com.uvp.sim.recording.RecordPlanGrid
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.PlatformBackHandler
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.UvpSwitch
import com.uvp.sim.ui.model.RecordSourceDto
import com.uvp.sim.ui.model.VideoRecordPlanViewDto

/**
 * 能力中心 → 录像计划（GB/T 28181-2022 **A.2.1.15 `videoRecordPlanCfgType`**）。
 *
 * ## 这一页在协议里对应什么
 *
 * 平台侧走 A.2.3.2.6「录像计划配置下载」把 `VideoRecordPlan` 下发给设备；设备侧在
 * `ConfigDownload` 查询（A.2.3.2.1）里按同一结构回读。本页编辑的就是**设备这一份**
 * 配置 —— 与「设置 → 前端 OSD」同一条口径：本地菜单与平台面板是**同一个值的两个入口**，
 * 谁最后写就以谁为准。
 *
 * ## 结构严格照 XSD，不做厂商私货
 *
 *  - `RecordEnable` 0/1
 *  - `RecordScheduleSumNum` = 实际天数（**现算**，不单独存，见 `VideoRecordPlanState`）
 *  - `RecordSchedule` ×最多 7（`WeekDayNum` = 1~7 = 周一~周日）
 *  - 每天 `TimeSegment` ×最多 8，起止各三字段（时/分/秒，无日期）
 *  - `StreamNumber` 0=主码流
 *
 * ⛔ 海康那套"每天只有一个时段"的前端口径在这里**不成立**：一天第 2 段之后会被静默丢掉
 * 且看不出来（见 `VideoRecordPlanConfig` 类注释）。所以本页每天支持多段。
 *
 * ⛔ 标准**没有**规定 `Stop < Start` 是跨零点还是非法，本页照实记、照实回读，不替平台解释。
 *
 * ## ✅ 这一页的计划**会真的自动录像**
 *
 * 保存后由 `RecordPlanScheduler`（shared，每 10 秒判定一次）按计划起停录像，录像来源记为
 * [RecordSourceDto.Schedule]（列表里显示「计划」）。三条行为边界写在这里，省得被当成 bug：
 *  - 计划只停**自己起的**那段录像，用户手动录着的时候不抢占、结束时也不去停
 *  - 时段内被手动停止，本时段不再自动恢复（否则界面像"停止按钮失灵"），下个时段照常
 *  - `stop <= start` 的时段不参与调度（不猜"跨零点"，跨零点请拆两段），页面会提示
 *
 * ⚠️ 调度依赖 App 进程在跑（正在录时有前台 Service 拉活）。App 被杀掉后不会像真 IPC 那样
 * 自己开机重录，重新打开 App 会在 10 秒内自愈。
 */
@Composable
fun VideoRecordPlanScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    val toast = LocalToastHost.current
    val view = state.deviceControl.deviceConfig.videoRecordPlan

    // ⛔ 草稿用 `remember(view)` 而不是 `remember(Unit)`：保存后 state 会刷新成新值，
    // 草稿必须跟着重置；平台在编辑期间下发新计划时也应当覆盖草稿（配置以最后写入者为准）。
    val saved = remember(view) { view.toPlanState() }
    var draft by remember(view) { mutableStateOf(saved) }

    val dirty = draft != saved

    fun setDay(day: Int, segments: List<RecordTimeSegment>) {
        val others = draft.schedules.filterNot { it.weekDayNum == day }
        draft = draft.copy(
            // 空天不留在草稿里：`TimeSegment` 是 minOccurs=1，留一条 0 段的 `RecordSchedule`
            // 就是一条结构非法的报文（标准「如当天无录像计划可缺少」）。
            schedules = (if (segments.isEmpty()) others
            else others + RecordSchedule(weekDayNum = day, segments = segments))
                .sortedBy { it.weekDayNum },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(UvpColor.Bg)) {
        CapabilitySubPageHeader(title = "录像计划", onBack = onBack)

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            SummaryCard(view = view, draft = draft)
            RuntimeStatusCard(
                state = state,
                saved = saved,
                dirty = dirty,
                draftEnabled = draft.recordEnable == 1,
            )
            SwitchCard(
                enabled = draft.recordEnable == 1,
                streamNumber = draft.streamNumber,
                onEnabledChange = { on ->
                    draft = draft.copy(recordEnable = if (on) 1 else 0)
                },
                onStreamChange = { number -> draft = draft.copy(streamNumber = number) },
            )

            RecordPlanGridCard(
                schedules = draft.schedules,
                onDaySegmentsChange = { day, segments -> setDay(day, segments) },
                onLimitReached = {
                    toast.error("每天最多 ${VideoRecordPlanConfig.MAX_SEGMENTS_PER_DAY} 个时段")
                },
            )

            // 精确时段：网格是 30 分钟粒度，平台下发的秒级时段在网格上会被对齐显示。
            // 这一块是"看得见原件"的入口，默认收起；存在无法被网格精确表达的段时自动展开，
            // 否则用户看到网格和时间对不上会以为改了数据。
            val offGrid = draft.schedules.sumOf { RecordPlanGrid.offGridCount(it.segments) }
            var showPrecise by remember(view) { mutableStateOf(offGrid > 0) }

            PrecisionToggle(
                expanded = showPrecise,
                offGridCount = offGrid,
                onToggle = { showPrecise = !showPrecise },
            )

            if (showPrecise) {
                // ⛔ 7 天全列，不能只列已有段的周几：网格是 30 分钟粒度，想直接给某个空白天
                // 加一条 08:07:30 这类非整段时，它在这份草稿里根本还不存在。早前按
                // `draft.schedules` 遍历，空白天在精确区里连入口都没有，等于把"改到秒"这条
                // 能力锁在"先在网格上涂一格"之后。
                for (day in 1..7) {
                    val daySegments = draft.schedules
                        .firstOrNull { it.weekDayNum == day }?.segments.orEmpty()
                    DayCard(
                        weekDayNum = day,
                        segments = daySegments,
                        onSegmentsChange = { segments -> setDay(day, segments) },
                        onLimitReached = {
                            toast.error("每天最多 ${VideoRecordPlanConfig.MAX_SEGMENTS_PER_DAY} 个时段")
                        },
                    )
                }
            }

            // 常驻规则说明已去掉（页面不再解释标准条款与调度行为，出处见代码注释与 CHANGELOG）；
            // 只留这条条件告警：真有无效时段时它必须出现，否则用户以为配上了却永远不录。
            val inert = RecordPlanEvaluator.inertSegmentCount(saved)
            if (inert > 0) {
                Text(
                    "⚠️ 有 $inert 个时段结束时间不晚于开始时间，不会生效（跨零点请拆成两段，如 " +
                        "22:00:00-23:59:59 + 00:00:00-06:00:00）。",
                    color = UvpColor.Warning, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        actions.onVideoRecordPlanSave(null)
                        toast.success("已恢复出厂默认（不启用计划录像）")
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UvpColor.Surface,
                        contentColor = UvpColor.TextSecondary,
                    ),
                ) { Text("恢复出厂", fontSize = 13.sp) }

                Button(
                    onClick = {
                        val cleaned = VideoRecordPlanConfig.normalizedForSave(draft)
                        actions.onVideoRecordPlanSave(cleaned)
                        toast.success(
                            if (cleaned.recordEnable == 1) {
                                "录像计划已保存：${cleaned.scheduleSumNum} 天 · " +
                                    "${cleaned.schedules.sumOf { it.segments.size }} 段"
                            } else {
                                "录像计划已保存（计划录像未启用）"
                            }
                        )
                    },
                    enabled = dirty,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UvpColor.Primary,
                        disabledContainerColor = UvpColor.Border,
                    ),
                ) {
                    Text(
                        if (dirty) "保存" else "已保存",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (dirty) Color.White else UvpColor.TextHint,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 顶部汇总：这份配置是不是出厂默认、一共几天几段。 */
@Composable
private fun SummaryCard(view: VideoRecordPlanViewDto, draft: VideoRecordPlanState) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (view.configured) "已配置" else "出厂默认（平台尚未下发过）",
                    color = if (view.configured) UvpColor.Text else UvpColor.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text("平台可回读", color = UvpColor.TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("${draft.scheduleSumNum} 天 · ${draft.schedules.sumOf { it.segments.size }} 个时段 · ")
                    append(if (draft.streamNumber == 0) "主码流" else "子码流${draft.streamNumber}")
                },
                color = UvpColor.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (view.configured) {
                    "本机保存后平台 ConfigDownload 会读到这一份"
                } else {
                    "设备上电后的物理状态：不启用、无计划、主码流"
                },
                color = UvpColor.TextHint,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 运行状态：**这一份计划现在到底有没有在录**。
 *
 * ⛔ 判定用 [saved]（已保存生效的那份），不是用户手里的草稿 —— 自动录像只认已保存的计划，
 * 但只写「按已保存的计划」不够：把开关拨开那一刻，卡上标题还是「计划录像未启用」，
 * 小字又缩在右上角，读起来像「拨了没反应」。所以草稿与已保存不一致（[dirty]）时，
 * 右上角换成橙色的「未保存的修改」—— 标题描述的仍是设备真实状态，但读者立刻明白
 * 手上这份改动还没生效，不是坏了。
 *
 * 判定逻辑调的是 shared 里**同一个** [RecordPlanEvaluator]，与调度器共用一套规则：
 * 页面说"正在录"而设备没录这种事，只会发生在两侧各算一遍的时候。
 *
 * 每 20 秒刷新一次本机时间：页面开着时"距结束还有多久"要能走字，到点起录也能在 20 秒内反映出来。
 * 20 秒而不是 1 秒，是因为这一屏不承担秒表职责（录像 tab 有专门的计时器）。
 */
@Composable
private fun RuntimeStatusCard(
    state: AppUiState,
    saved: VideoRecordPlanState,
    dirty: Boolean,
    draftEnabled: Boolean,
) {
    var now: LocalClockReading by remember { mutableStateOf(LocalWallClock.now()) }
    LaunchedEffect(saved) {
        while (true) {
            now = LocalWallClock.now()
            kotlinx.coroutines.delay(20_000)
        }
    }

    val slot = RecordPlanEvaluator.activeSlot(saved, now)
    val recording = state.recording
    // 「计划自己起的」与「别人起的」必须分开：前者会随计划停，后者计划不插手。
    val planRecording = recording.isRecording && recording.source == RecordSourceDto.Schedule
    val otherRecording = recording.isRecording && recording.source != RecordSourceDto.Schedule

    val tone: Color
    val title: String
    val detail: String
    when {
        saved.recordEnable != 1 -> {
            // 标题说的是设备真实状态（已保存那份未启用），所以不能因为草稿打开了开关就改口；
            // 但 detail 要跟着草稿走，否则用户已经拨开开关，还被提示"去打开开关"。
            tone = if (dirty) UvpColor.Warning else UvpColor.TextHint
            title = "计划录像未启用"
            detail = when {
                dirty && draftEnabled -> "开关已打开但还没保存 —— 保存后才会到点自动起录。"
                dirty -> "下面的改动还没保存，设备仍按原计划运行。"
                else -> "打开下面的开关并保存后，到点会自动起录、到点自动停。"
            }
        }
        planRecording -> {
            tone = UvpColor.Danger
            title = "正在按计划录像"
            detail = slot?.let {
                "本段 ${it.segment.displayLabel} · 距结束 ${formatGap(it.stopSeconds - now.secondsOfDay)}"
            } ?: "本段已结束，正在写文件尾"
        }
        otherRecording -> {
            tone = UvpColor.Warning
            title = "录像进行中（${otherSourceLabel(recording.source)}）"
            detail = slot?.let {
                "本时段 ${it.segment.displayLabel} 的计划先让给这段录像；它停了之后计划会接管。"
            } ?: "本时段计划让给这段录像。"
        }
        slot != null -> {
            tone = UvpColor.Warning
            title = "本时段未在录像"
            detail = "被手动停止过？本时段不会自动恢复，下个时段照常起录。"
        }
        else -> {
            val next = RecordPlanEvaluator.nextStart(saved, now)
            tone = UvpColor.TextHint
            if (next == null) {
                title = "本周没有可执行的时段"
                detail = "检查计划，或确认每个时段的结束时间都晚于开始时间。"
            } else {
                title = "待机"
                detail = "下次 ${dayOffsetLabel(next.dayOffset)} ${formatHms(next.secondsOfDay)} · " +
                    "还差 ${formatGap(next.dayOffset * 86400 + next.secondsOfDay - now.secondsOfDay)}"
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .background(tone, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = UvpColor.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, color = UvpColor.TextHint, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (dirty) "未保存的修改" else "按已保存的计划",
                color = if (dirty) UvpColor.Warning else UvpColor.TextHint,
                fontSize = 10.sp,
                fontWeight = if (dirty) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

private fun otherSourceLabel(source: RecordSourceDto?): String = when (source) {
    RecordSourceDto.PlatformCmd -> "平台指令"
    RecordSourceDto.Schedule -> "计划"
    RecordSourceDto.Manual, null -> "手动"
}

/** 当天第几秒 → `HH:mm:ss`。 */
private fun formatHms(secondsOfDay: Int): String {
    val s = secondsOfDay.coerceIn(0, 86_399)
    return "${pad2(s / 3600)}:${pad2((s % 3600) / 60)}:${pad2(s % 60)}"
}

/** 剩余时长的人读串（不显示秒：这一屏不是秒表）。 */
private fun formatGap(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    if (s < 60) return "不到 1 分钟"
    val days = s / 86_400
    val hours = (s % 86_400) / 3600
    val minutes = (s % 3600) / 60
    return when {
        days > 0 -> "$days 天 $hours 小时"
        hours > 0 -> "$hours 小时 $minutes 分"
        else -> "$minutes 分"
    }
}

private fun dayOffsetLabel(dayOffset: Int): String = when (dayOffset) {
    0 -> "今天"
    1 -> "明天"
    else -> "$dayOffset 天后"
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/** 启用开关 + 码流编号（`RecordEnable` / `StreamNumber`）。 */
@Composable
private fun SwitchCard(
    enabled: Boolean,
    streamNumber: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStreamChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("计划录像", color = UvpColor.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("RecordEnable 0/1", color = UvpColor.TextHint, fontSize = 11.sp)
                }
                UvpSwitch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text("码流编号 StreamNumber", color = UvpColor.TextSecondary, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STREAM_LABELS.forEachIndexed { number, label ->
                    StreamChip(
                        label = label,
                        selected = streamNumber == number,
                        onClick = { onStreamChange(number) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .background(
                if (selected) UvpColor.Primary else UvpColor.Surface,
                RoundedCornerShape(6.dp),
            )
            .border(
                1.dp,
                if (selected) UvpColor.Primary else UvpColor.Border,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else UvpColor.Text,
        )
    }
}

/**
 * 「精确时段」折叠入口。
 *
 * 网格只有 30 分钟粒度，平台下发的秒级时段（如 `08:07:30-12:33:15`）在网格上会被
 * 向外吸附显示。这一块用来把**原件**露出来：用户看到网格和时间对不上时能查到原因，
 * 也能在这里改到秒。存在这种段时标题右侧用橙色标出数量，并默认展开。
 */
@Composable
private fun PrecisionToggle(expanded: Boolean, offGridCount: Int, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("精确时段", color = UvpColor.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                if (offGridCount > 0) "$offGridCount 段不在 30 分钟边界" else "可改到秒",
                color = if (offGridCount > 0) UvpColor.Warning else UvpColor.TextHint,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = UvpColor.TextHint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 某一天的卡片：标题 + 已有时间段 + 添加按钮。 */
@Composable
private fun DayCard(
    weekDayNum: Int,
    segments: List<RecordTimeSegment>,
    onSegmentsChange: (List<RecordTimeSegment>) -> Unit,
    onLimitReached: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    weekdayLabel(weekDayNum),
                    color = UvpColor.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (segments.isEmpty()) "无计划" else "${segments.size} 段",
                    color = UvpColor.TextHint,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (segments.size >= VideoRecordPlanConfig.MAX_SEGMENTS_PER_DAY) {
                            onLimitReached()
                        } else {
                            onSegmentsChange(segments + nextSegment(segments))
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 6.dp, vertical = 2.dp
                    ),
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = UvpColor.Primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("添加时段", color = UvpColor.Primary, fontSize = 12.sp)
                }
            }
            segments.forEachIndexed { index, segment ->
                TimeSegmentEditor(
                    index = index,
                    segment = segment,
                    onChange = { updated ->
                        onSegmentsChange(segments.toMutableList().also { it[index] = updated })
                    },
                    onDelete = {
                        onSegmentsChange(segments.filterIndexed { i, _ -> i != index })
                    },
                )
            }
        }
    }
}

/** 一个时段的六个字段（起止各 时/分/秒）。 */
@Composable
private fun TimeSegmentEditor(
    index: Int,
    segment: RecordTimeSegment,
    onChange: (RecordTimeSegment) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${index + 1}", color = UvpColor.TextHint, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            // 人读回显：与平台侧看到的字面量同形（`08:00:00-12:00:00`）
            Text(segment.displayLabel, color = UvpColor.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除该时段",
                    tint = UvpColor.TextHint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        TimeInputRow(
            label = "开始",
            hour = segment.startHour,
            minute = segment.startMin,
            second = segment.startSec,
        ) { h, m, s -> onChange(segment.copy(startHour = h, startMin = m, startSec = s)) }
        TimeInputRow(
            label = "结束",
            hour = segment.stopHour,
            minute = segment.stopMin,
            second = segment.stopSec,
        ) { h, m, s -> onChange(segment.copy(stopHour = h, stopMin = m, stopSec = s)) }
    }
}

@Composable
private fun TimeInputRow(
    label: String,
    hour: Int,
    minute: Int,
    second: Int,
    onChange: (Int, Int, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = UvpColor.TextHint, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        NumberBox(hour, 23) { onChange(it, minute, second) }
        Text("时", color = UvpColor.TextHint, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
        NumberBox(minute, 59) { onChange(hour, it, second) }
        Text("分", color = UvpColor.TextHint, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
        NumberBox(second, 59) { onChange(hour, minute, it) }
        Text("秒", color = UvpColor.TextHint, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * 两位数字框（时 0~23 / 分秒 0~59）。
 *
 * ⛔ 用 `takeLast(2)` 而不是 `take(2)`：值从 `00` 起输 `12` 时序列是
 * `"00"→"001"→"01"→"012"→"12"`，取末两位才符合「继续输入就是追加」的直觉；
 * 取前两位会把用户第二次按键吃掉（`"00"→"0"→"01"→"0"`），表现为"输不进去"。
 */
@Composable
private fun NumberBox(value: Int, max: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString().padStart(2, '0')) }
    BasicTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.takeLast(2)
            text = digits
            onChange((digits.toIntOrNull() ?: 0).coerceIn(0, max))
        },
        modifier = Modifier
            .width(38.dp)
            .background(UvpColor.Bg, RoundedCornerShape(5.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(5.dp))
            .padding(vertical = 6.dp),
        textStyle = TextStyle(
            fontSize = 13.sp,
            color = UvpColor.Text,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(UvpColor.Primary),
    )
}

private val STREAM_LABELS = listOf("主码流", "子码流1", "子码流2", "子码流3")

internal fun weekdayLabel(weekDayNum: Int): String = when (weekDayNum) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "周?($weekDayNum)"
}

private fun VideoRecordPlanViewDto.toPlanState() = VideoRecordPlanState(
    recordEnable = if (enabled) 1 else 0,
    schedules = schedules,
    streamNumber = streamNumber,
)

/**
 * 新增时段的默认值：接在当天最后一段结束之后，长度 1 小时；当天没有段则给 08:00-12:00。
 *
 * 不用 `00:00:00` 起：那是最不容易被察觉的"看起来没配"的值（`RecordEnable=0` 之外，
 * 全 0 时段在平台面板上也像空计划）。
 */
private fun nextSegment(segments: List<RecordTimeSegment>): RecordTimeSegment {
    val last = segments.lastOrNull() ?: return RecordTimeSegment(8, 0, 0, 12, 0, 0)
    val fromSec = last.stopHour * 3600 + last.stopMin * 60 + last.stopSec
    val toSec = (fromSec + 3600).coerceAtMost(23 * 3600 + 59 * 60 + 59)
    return RecordTimeSegment(
        startHour = fromSec / 3600, startMin = (fromSec % 3600) / 60, startSec = fromSec % 60,
        stopHour = toSec / 3600, stopMin = (toSec % 3600) / 60, stopSec = toSec % 60,
    )
}
