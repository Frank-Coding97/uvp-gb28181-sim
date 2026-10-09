package com.uvp.sim.ui.simulate.ptz

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.CruiseTrackDto
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.PtzPoseDto
import com.uvp.sim.ui.model.ScanGroupDto

/** 云台页只保留本机方向、变焦和镜头操作；平台定位信息见 PositionTabContent。 */
@Composable
internal fun PtzTabContent(
    state: DeviceControlDto,
    onLocalAdjust: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onLocalLensAdjust: (Float, Float) -> Unit = { _, _ -> },
) {
    // 本机(而非平台)正在推镜头 —— 供读数条复用「数值正在变化」的高亮。
    // 演示时能一眼分清这次转动是人在推还是平台在推。
    var localPtzPushing by remember { mutableStateOf(false) }
    var localZoomPushing by remember { mutableStateOf(false) }

    Column {
        // ① 宣读条:水平 / 俯仰(平台速率非 0 或本机正在推时高亮)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(UvpColor.Bg)
                .padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PoseReadout(
                "水平",
                state.panAngle,
                "°",
                isActive = state.panSpeed != 0f || localPtzPushing,
            )
            PoseReadoutDivider()
            PoseReadout(
                "俯仰",
                state.tiltAngle,
                "°",
                isActive = state.tiltSpeed != 0f || localPtzPushing,
            )
        }
        Spacer(Modifier.height(8.dp))

        // ② 中部:变焦(左) | 方向盘(中) | 光圈 + 聚焦(右)
        //    三列同高,以方盘边长为准,视觉上才是一个整体控制台。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PtzConsolePadSide),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZoomCard(
                zoom = state.zoomLevel,
                isActive = state.zoomSpeed != 0f || localZoomPushing,
                onAdjust = onLocalAdjust,
                onLocalActive = { localZoomPushing = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Spacer(Modifier.width(8.dp))
            PtzConsolePad(
                onAdjust = onLocalAdjust,
                onActiveChange = { localPtzPushing = it },
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LensCard(
                    label = "光圈",
                    level = state.irisLevel,
                    isActive = state.irisSpeed != 0f,
                    // 光圈:标准表 A.6 的 bit2 = 放大(开大)、bit3 = 缩小。负方向 = 缩小。
                    downLabel = "−",
                    upLabel = "+",
                    onStep = { sign -> onLocalLensAdjust(0f, sign * LENS_STEP) },
                    onRepeat = { sign -> onLocalLensAdjust(0f, sign * LENS_CONT_STEP) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                LensCard(
                    label = "聚焦",
                    level = state.focusLevel,
                    isActive = state.focusSpeed != 0f,
                    // 聚焦写「近/远」而不是 +/−:国标表 A.6 的 bit1/bit0 本就是"聚焦近/聚焦远",
                    // 而"＋"到底指近还是远在各家客户端里是相反的,直写真值不会被读反。
                    downLabel = "近",
                    upLabel = "远",
                    onStep = { sign -> onLocalLensAdjust(sign * LENS_STEP, 0f) },
                    onRepeat = { sign -> onLocalLensAdjust(sign * LENS_CONT_STEP, 0f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/** 紧凑定位总览：常用配置一屏展示，大量配置仍允许滚动查看。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PositionTabContent(state: DeviceControlDto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("预置位")
            Spacer(Modifier.width(8.dp))
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.presets.isEmpty()) SectionLabel("未配置 · 等待平台下发")
                state.presets.keys.sorted().forEach { index ->
                    val current = index == state.currentPresetIndex
                    Text(
                        "P$index",
                        modifier = Modifier.background(
                            if (current) UvpColor.Primary else UvpColor.PrimaryLight,
                            RoundedCornerShape(6.dp),
                        ).padding(horizontal = 8.dp, vertical = 5.dp),
                        color = if (current) Color.White else UvpColor.Primary,
                        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        HomePositionCard(state)
        SectionLabel("巡航轨迹")
        if (state.cruiseTracks.isEmpty()) SectionLabel("未配置 · 等待平台下发")
        state.cruiseTracks.entries.sortedBy { it.key }.forEach { (num, track) ->
            Row(
                Modifier.fillMaxWidth().background(UvpColor.Bg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#$num${if (state.activeCruiseTrack == num) " ▶" else ""}",
                    color = UvpColor.Primary, fontSize = 11.sp, lineHeight = 14.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.points.joinToString(" → ") { "P$it" }.ifEmpty { "暂无点位" },
                        color = UvpColor.Text, fontSize = 10.sp, lineHeight = 12.sp)
                    Text(cruiseGroupParams(track), color = UvpColor.TextSecondary,
                        fontSize = 9.sp, lineHeight = 12.sp)
                }
            }
        }
        SectionLabel("自动扫描")
        if (state.scanGroups.isEmpty()) SectionLabel("未配置 · 等待平台下发")
        state.scanGroups.entries.sortedBy { it.key }.forEach { (num, group) ->
            ScanGroupChip(num, group, state.activeScanGroup == num)
        }
    }
}

/**
 * 扫描组 chip(GB/T 28181 表 A.10)。
 *
 * ⭐ 它比巡航 chip 更**必须**出现在设备屏幕上:附录 A 里**没有任何查询命令**能回读扫描的
 * 边界与速度(没有 `ScanQuery`;`A.2.6.15` 的 PTZ 精准状态查询只回 Pan/Tilt/Zoom/视场角),
 * 平台上永远只有"指令已下发"。这一枚 chip 是**唯一**能确认"边界真的存下来了、而且是这一段"
 * 的地方 —— 所以边界缺席时**如实写"未设"**,不回落成 0°:0° 是个合法边界值,拿它兜底会让人
 * 以为设过了,而扫描仍会因"跨度不足"原地不动,现象与"边界没设"完全一样却更难查
 * (见 `ScanExecution.scanStepAt` 的四种不动情形)。
 *
 * 文案刻意压到最短(`扫描#0 ▶ -105°↔40°·120`,约 378px):它是与巡航 chip 挤在同一行的第三枚,
 * 宽度直接决定会不会换行、而换行就等于超 HUD 预算(见调用处注释)。所以省掉了"左/右/速度"
 * 这些字 —— `↔` 左边是左边界,直觉可读。缺边界时只报"缺边界",不铺半截数据。
 */
@Composable
private fun ScanGroupChip(groupNum: Int, group: ScanGroupDto?, scanning: Boolean) {
    val sweepable = group?.leftBoundary != null && group?.rightBoundary != null
    val text = when {
        // 「扫描中」与「已启动」必须分开:标准里"开始扫描"只是一条指令,缺边界时设备**停在原地**
        // (见 SimulatorEngine 的告警日志),这时写"扫描中"就是设备替自己编一句没发生的事。
        scanning && !sweepable -> "扫描#$groupNum ▶ 缺边界"
        else -> buildString {
            append("扫描#$groupNum")
            if (scanning) append(" ▶")
            append(" ").append(scanBoundaryText(group?.leftBoundary))
            append("↔").append(scanBoundaryText(group?.rightBoundary))
            append("·").append(group?.speed?.toString() ?: "速度未下发")
        }
    }
    val bg = if (scanning) UvpColor.Primary else UvpColor.BorderLight
    val fg = if (scanning) Color.White else UvpColor.TextSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = fg,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 扫描边界角的显示文案。`null` = 平台没设过这一侧 —— 如实写"未设",**不回落成 0°**:
 * 0° 是个合法边界值,拿它兜底会让人以为边界已经设过,而扫描仍会因"跨度不足"原地不动 ——
 * 现象与"边界没设"完全一样,却更难查(见 `ScanExecution.scanStepAt` 的四种不动情形)。
 */
private fun scanBoundaryText(pose: PtzPoseDto?): String =
    pose?.let { formatPose(it.pan, "°") + "°" } ?: "未设"

/**
 * 组级参数的读法(速度 / 停留时间)。
 *
 * **必须带单位**:`0x86`/`0x87` 的参数都是 12 位裸整数(1-4095),界面上光写 `128`
 * 没人知道是档位还是百分比;停留时间也只有"秒"一种解释。平台侧同款坑已修
 * (看守位的「空闲」字段),这里保持一致。
 *
 * null = 平台从未下发过该项 —— 如实说"未下发",不复用设备默认值冒充(设备查询应答里
 * 回的那个默认值见 DeviceControlSubRouter.DEFAULT_CRUISE_*)。
 */
private fun cruiseGroupParams(track: CruiseTrackDto): String {
    val parts = buildList {
        track.dwellTime?.let { add("每点停留 ${it}s") }
        track.speed?.let { add("速度 $it") }
    }
    return if (parts.isEmpty()) "平台未下发速度与停留时间" else parts.joinToString(" · ")
}

/** 分区标题使用显式行高，避免继承正文的大行框。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        color = UvpColor.TextHint,
        fontWeight = FontWeight.Medium,
    )
}

/** 顶部读数:左标签 + 右数值,平台正在驱动时数值转主题色。 */
@Composable
private fun RowScope.PoseReadout(
    label: String,
    value: Float,
    unit: String,
    isActive: Boolean,
) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, fontSize = 10.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(
            formatPose(value, unit) + unit,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) UvpColor.Primary else UvpColor.Text,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PoseReadoutDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(UvpColor.BorderLight)
    )
}

/** 变焦卡 — 本页第二个(也是最后一个)可交互控件:读数 + 缩小/放大。 */
@Composable
private fun ZoomCard(
    zoom: Float,
    isActive: Boolean,
    onAdjust: (Float, Float, Float) -> Unit,
    onLocalActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Bg)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("变焦", fontSize = 10.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            formatPose(zoom, "×") + "×",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) UvpColor.Primary else UvpColor.Text,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CtrlButton(
                icon = Icons.Outlined.Remove,
                contentDescription = "缩小",
                onPressOnce = { onAdjust(0f, 0f, -ZOOM_STEP) },
                onRepeatStep = { onAdjust(0f, 0f, -ZOOM_CONT_STEP) },
                onLocalActive = onLocalActive,
            )
            CtrlButton(
                icon = Icons.Outlined.Add,
                contentDescription = "放大",
                onPressOnce = { onAdjust(0f, 0f, ZOOM_STEP) },
                onRepeatStep = { onAdjust(0f, 0f, ZOOM_CONT_STEP) },
                onLocalActive = onLocalActive,
            )
        }
    }
}

/** 变焦单击一步的倍率增量。 */
private const val ZOOM_STEP = 0.5f

/** 变焦长按连续时的单步增量(0.15× / 60ms ≈ 2.5×/s,1× 推到 20× 上限约 7.6 秒)。 */
private const val ZOOM_CONT_STEP = 0.15f

/**
 * 光圈 / 聚焦 — 行程 0~1 的读数条 + **本机可长按的** −/+ (或 近/远) 两个键。
 *
 * 这个卡从只读升级为可交互,是 2026-09-16 「光圈 聚焦 看守位 平台+设备联动」需求的一部分:
 * 国标里 FI 族(表 A.6)和云台方向族(表 A.5)**形态完全一样** —— 都是"带速度的开始动作"
 * 指令,不是"走一步"指令。所以设备侧存的是速率,位置由速率积分而来;本机手操则按
 * [LENS_STEP] / [LENS_CONT_STEP] 直接给位置增量,两条路径都落回同一个
 * `DeviceControlModel.focusLevel` / `irisLevel`。
 *
 * 键位用 `Box + pointerInput`(经 [rememberRepeatPress])而非 Material3 `IconButton`:
 * 后者强制 48dp 最小交互尺寸,而这个卡在 128dp 高的三列布局里只有约 61dp 高、宽也就
 * 100dp 上下,排不下。
 *
 * ⛔ 这个卡的高度是**硬预算**,内部每一行都必须显式给 `lineHeight` —— 原因与完整算式
 * 见 [LENS_LINE_HEIGHT]。2026-09-17 用户报的「+ − 近 远 只剩上半截」就是踩了它。
 *
 * [downLabel] / [upLabel] 由调用方给:光圈写 "−"/"+",聚焦写 "近"/"远"。
 * 聚焦不写 +/− 是因为"＋ 到底指近还是远"在各家客户端里是相反的,直写真值不会被读反。
 */
@Composable
private fun LensCard(
    label: String,
    level: Float,
    isActive: Boolean,
    downLabel: String,
    upLabel: String,
    onStep: (Float) -> Unit,
    onRepeat: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clamped = level.coerceIn(0f, 1f)
    val pct = (clamped * 100f).toInt()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Bg)
            .padding(horizontal = 8.dp, vertical = LENS_CARD_PADDING_V),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 10.sp,
                // ⛔ 必须显式给 lineHeight —— 只给 fontSize 不管用,原因见 [LENS_LINE_HEIGHT]。
                lineHeight = LENS_LINE_HEIGHT,
                maxLines = 1,
                softWrap = false,
                color = UvpColor.TextHint,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$pct%",
                fontSize = 11.sp,
                lineHeight = LENS_LINE_HEIGHT,
                maxLines = 1,
                softWrap = false,
                // 平台正在推 或 本机正在推 → 转主题色。isActive 只管平台那一侧,
                // 本机那一侧由按下态自己染色(见下方按键),两者不会互相掩盖。
                color = if (isActive) UvpColor.Primary else UvpColor.Text,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(LENS_GAP))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(99.dp))
                // ⛔ 轨道原来用 BorderLight,而 BorderLight 跟卡片底色 Bg **是同一个值**
                //    (都 #F3F4F6)→ 真机上轨道完全不可见,一个「51%」的条看不出它占的是
                //    什么的总量(2026-09-17 采样 x879 处像素 = (243,244,246),与卡底同色)。
                //    Border(#E5E7EB) 压在 Bg 上才看得见。
                .background(UvpColor.Border),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(UvpColor.Primary)
            )
        }
        Spacer(Modifier.height(LENS_GAP))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // ⛔ 吃掉卡内**剩余**高度,不要写死按键高度(原来是 height(22.dp))。
                //    写死的话,只要上面任何一行比自己以为的高,这个 Box 就被父约束压扁,
                //    而里面的文字仍按自己的行高布局 → 必然被药丸的 clip 切掉。
                //    这正是 2026-09-17 那个「+ − 近 远 只剩上半截」的成因:
                //    标签行按 24sp 行高白吃 24.2dp,按键行只剩 12.2dp,文字却是 24sp 行高。
                //    用 weight 则"剩余多少就是多少",多出来的部分直接变成更大的触控区。
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LensKey(
                text = downLabel,
                contentDescription = "$label $downLabel",
                sign = -1f,
                onStep = onStep,
                onRepeat = onRepeat,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            LensKey(
                text = upLabel,
                contentDescription = "$label $upLabel",
                sign = 1f,
                onStep = onStep,
                onRepeat = onRepeat,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * 光圈/聚焦的单个长按键。
 *
 * [sign] 在按下瞬间就随 [onStep] / [onRepeat] 一起交给调用方 —— 按键自己不认识
 * "近/远/开/关"的语义,只报方向,语义映射留在调用方(光学上哪边是正,和协议表 A.6 的
 * bit 定义绑在一起,放一起才看得住)。
 */
@Composable
private fun LensKey(
    text: String,
    contentDescription: String,
    sign: Float,
    onStep: (Float) -> Unit,
    onRepeat: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = rememberRepeatPress(
        onPressOnce = { onStep(sign) },
        onRepeatStep = { onRepeat(sign) },
    )
    val bg by animateColorAsState(
        if (press.pressed) UvpColor.Primary else UvpColor.Surface,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "lens-key-bg",
    )
    val fg by animateColorAsState(
        if (press.pressed) Color.White else UvpColor.Text,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "lens-key-fg",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(
                1.dp,
                if (press.pressed) UvpColor.Primary else UvpColor.Border,
                RoundedCornerShape(7.dp),
            )
            .then(press.modifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg,
            fontSize = 12.sp,
            // 同上:只给 fontSize 的话行高仍是被继承的 24sp,药丸一矮就把字上下切掉。
            lineHeight = LENS_LINE_HEIGHT,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 光圈/聚焦单击一步的行程增量(0~1 归一化)。 */
private const val LENS_STEP = 0.05f

/**
 * 光圈/聚焦长按连续时的单步增量。
 *
 * 0.02f / 60ms ≈ 0.33/s,走完整条行程(0%→100%)约 3 秒 —— 刻意跟**平台侧**的观感对齐:
 * 平台默认档位 6 按 `LENS_RATE_PER_SPEED = 0.5/255` 换算成 153/255 → 0.3/s ≈ 3.3 秒。
 * 本机推和平台推速度差太多的话,演示时一眼就能看出是两套逻辑。
 */
private const val LENS_CONT_STEP = 0.02f

/**
 * 光圈/聚焦卡里**所有**文字的行高 —— 必须显式给,只给 `fontSize` 是不够的。
 *
 * ⛔ 本仓 `UvpTheme` 只往 `MaterialTheme` 传了 `colorScheme`,**没有覆写 `typography`**,
 * 于是 `Text` 默认继承 Material3 `bodyLarge` 的 `lineHeight = 24.sp`。而
 * `Text(fontSize = 10.sp)` 只覆盖字号,**行高照样是 24.sp** —— 即"10sp 的字实占 24dp 高"。
 *
 * 这个卡在 128dp 高的三列布局里只分到约 61dp(见 [PtzConsolePadSide]),高度是硬预算:
 *   61dp = 上下内边距 6+6 + 标签行 + [LENS_GAP] + 读数条 3 + [LENS_GAP] + 按键行
 * 修复前标签行按 24.2dp 记账(实测,正好是 24.sp),留给按键行只剩 12.2dp,而按键里的
 * 12.sp 文字同样是 24sp 行高 → 被药丸的 `clip` 切掉大半。真机现象就是用户 2026-09-17
 * 报的:光圈卡的「− +」、聚焦卡的「近 远」只剩上半截。
 *
 * 判据:**凡是父级高度固定的小字号 Text,都必须显式给 lineHeight**;
 * "字小"从来不等于"行矮"。全仓同类风险(其它卡/其它页面)另见 topics/frontend-ui。
 */
private val LENS_LINE_HEIGHT = 14.sp

/** 光圈/聚焦卡内边距。纵向与 [ZoomCard] 保持 6dp,三列看起来才是一套。 */
private val LENS_CARD_PADDING_V = 6.dp

/** 光圈/聚焦卡内行距(标签行 → 读数条 → 按键行)。 */
private val LENS_GAP = 3.dp

/**
 * 看守位卡 — 设备侧只读回显平台下发的**配置三件套**。
 *
 * 标准里 `HomePosition` 只有三个字段:`Enabled`(开关) / `ResetTime`(无云台操作多久自动
 * 归位,秒) / `PresetIndex`(回到哪个预置位)。注意两件事:
 *  1. 它**不存坐标** —— 坐标在预置位里,这里只保存"指向哪个预置位";
 *  2. 标准**没有定义"已归位"的上报**,所以这里只能显示"配置已下发",不能显示"现在到位了"。
 *
 * 平台从未下发过时(`homePositionPresetIndex == null`)显示"未配置",而不是拿默认值凑数。
 */
@Composable
private fun HomePositionCard(state: DeviceControlDto) {
    val presetIndex = state.homePositionPresetIndex
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("看守位")
        Spacer(Modifier.width(10.dp))
        if (presetIndex == null) {
            Text(
                "未配置 · 等待平台下发",
                fontSize = 11.sp,
                color = UvpColor.TextHint,
            )
        } else {
            StatusDot(enabled = state.homePositionEnabled)
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.homePositionEnabled) "已启用" else "已关闭",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (state.homePositionEnabled) UvpColor.Primary else UvpColor.TextHint,
            )
            Spacer(Modifier.width(12.dp))
            FieldText("指向", "P$presetIndex")
            Spacer(Modifier.width(12.dp))
            FieldText(
                "自动归位",
                state.homePositionResetTime?.let { "${it}s" } ?: "未下发",
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "平台下发",
            fontSize = 8.sp,
            color = UvpColor.TextHint,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusDot(enabled: Boolean) {
    val color = if (enabled) UvpColor.Primary else UvpColor.TextHint
    Box(
        Modifier
            .size(7.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(color)
    )
}

@Composable
private fun FieldText(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            fontSize = 11.sp,
            color = UvpColor.TextSecondary,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 变焦的 −/+ 小方键。
 *
 * 不用 Material3 `IconButton` 的原因有两个:
 *  1. 它会强制 48dp 最小交互尺寸,窄卡片里直接撑爆(2026-09-16 在旧调试条上踩过);
 *  2. 操作方式要跟方向盘一致 —— 按一下走一步、**按住连续调、松手即停**。
 */
@Composable
private fun CtrlButton(
    icon: ImageVector,
    contentDescription: String,
    onPressOnce: () -> Unit,
    onRepeatStep: () -> Unit,
    onLocalActive: (Boolean) -> Unit = {},
) {
    val press = rememberRepeatPress(onPressOnce, onRepeatStep)

    val bg by animateColorAsState(
        if (press.pressed) UvpColor.Primary else UvpColor.Surface,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "ctrl-btn-bg",
    )
    val borderColor by animateColorAsState(
        if (press.pressed) UvpColor.Primary else UvpColor.Border,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "ctrl-btn-border",
    )
    val fg by animateColorAsState(
        if (press.pressed) Color.White else UvpColor.Text,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "ctrl-btn-fg",
    )

    DisposableEffect(press.pressed) {
        onLocalActive(press.pressed)
        onDispose { if (press.pressed) onLocalActive(false) }
    }

    Box(
        modifier = Modifier
            .size(CTRL_KEY)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .then(press.modifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** −/+ 方键边长。跟方向盘单键同一个量级,看起来才是一套控件。 */
private val CTRL_KEY = 34.dp

/** 位姿数值格式化 — 云台页读数与方盘共用(对 `×` 保留 1 位小数,角度取整带符号). */
internal fun formatPose(value: Float, unit: String): String {
    return if (unit == "×") {
        // KMP 友好的 "%.1f"
        val rounded = kotlin.math.round(value * 10f).toInt()
        val whole = rounded / 10
        val frac = kotlin.math.abs(rounded % 10)
        "$whole.$frac"
    } else {
        val rounded = kotlin.math.round(value).toInt()
        if (rounded > 0) "+$rounded" else "$rounded"
    }
}
