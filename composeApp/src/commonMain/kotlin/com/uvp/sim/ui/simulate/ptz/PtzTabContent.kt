package com.uvp.sim.ui.simulate.ptz

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
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
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.PtzPoseDto
import kotlinx.coroutines.delay

/**
 * 云台 Tab — 一个**云台控制台**:中央方向盘 + 四周参数 + 底部预置位/看守位。
 *
 * 版面(自上而下):
 * ```
 *  ① 水平 +120°        │        俯仰 -5°          读数条
 *  ② ┌──────┐   ┌─────────┐   ┌──────────┐
 *     │ 变焦  │   │  ↑ ↑ ↑  │   │ 光圈 50% │       变焦/光圈/聚焦
 *     │ 1.0× │   │ ← ● →  │   │ [−][+]   │       围绕方向盘
 *     │ −  + │   │  ↓ ↓ ↓  │   │ 聚焦 50% │
 *     └──────┘   └─────────┘   │ [近][远] │
 *  ③ 预置位  [P1]…[P8]
 *  ④ 看守位  ● 已启用 · 指向 P3 · 归位 30s        平台下发
 * ```
 *
 * **可交互 vs 只读**:方向盘(水平/俯仰)、变焦 −/+、光圈 −/+、聚焦 近/远 都是本机可操作的;
 * 读数、预置位、看守位仍是平台侧状态的**只读回显**(spec AC2)。设备端不提供预置位/看守位的
 * 配置入口 —— 两者都只由平台写入,设备被动接受(2026-09-16 决议)。
 *
 * **操作方式**(2026-09-16 按用户反馈重做,见 [PtzConsolePad.rememberRepeatPress]):
 * 按一下走一步 / **按住连续走 / 松手即停**。原来是纯点击式(点一下动一格),
 * 不符合云台操控的通用认知 —— 实体球机和各家客户端都是按住转、松手停。
 * 本机操作期间读数条会同步高亮,跟「平台正在推」共用同一套视觉。
 *
 * **两条驱动路径,汇到同一个 Model**:
 * ```
 *   平台按住 → FI 命令(带速度) → Model.focusSpeed/irisSpeed  ← 速率
 *                                    ↓ 本页 60ms 积分节拍
 *   本机按住 → 长按重复增量 ─────────→ onLocalLensAdjust → Model.focusLevel/irisLevel
 * ```
 * 平台侧只发**一条**带速度的 FI 命令、松手补一条 0x40 停止(标准里 FI 与方向命令同形,
 * 都是"带速度的开始动作",见 `gb28181/PtzCommand` 表 A.6),所以镜头位置得由本页按
 * 「速率 × 时间」自己推 —— 这就是下面那段积分节拍存在的原因。两条路径都写回 Model,
 * 于是切 Tab 再回来位置不丢。方向/变焦的速率积分在 3D 画布那一侧(见 `CameraGlbView`)。
 *
 * 位置演进:本机手操原先是贴在本页底部的一张横条,2026-09-16 上午迁到 3D 画布下方以
 * 避免与只读 HUD 混淆,下午发现会盖住画布右下角的缩略图、改到画布下方独立占一行,
 * 最终收进本页做成方盘 —— 位置固定、不再和画布内任何元素抢空间。
 */
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

    // 平台 FI 速率的积分节拍。
    //
    // 平台按住聚焦/光圈时只下发**一条**带速度的 FI 命令(标准表 A.6:字节5/6 是速度,
    // 低 4 位清零即停),它表达的是"以这个速度开始走";松开时平台补一条 0x40 把速率归零。
    // 所以设备侧的位置只能自己按「速率 × 时间」推出来 —— 就是这一拍。
    //
    // 60ms 与 [rememberRepeatPress] 的长按重复间隔同源,观感一致。
    // LaunchedEffect 以两个速率为 key:速率一变立刻用新速度重开;归零则条件分支直接返回,
    // 循环结束。不要在循环体里读 `state.xxx` —— 那样捕获的是启动时的旧值。
    LaunchedEffect(state.focusSpeed, state.irisSpeed) {
        if (state.focusSpeed == 0f && state.irisSpeed == 0f) return@LaunchedEffect
        val focusRate = state.focusSpeed
        val irisRate = state.irisSpeed
        while (true) {
            delay(LENS_TICK_MS)
            onLocalLensAdjust(focusRate * LENS_TICK_SECONDS, irisRate * LENS_TICK_SECONDS)
        }
    }

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
        Spacer(Modifier.height(8.dp))

        // ③ 预置位(平台下发后设备侧只读回显,不允许在设备上编辑)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("预置位")
            Spacer(Modifier.width(8.dp))
            PresetChipRow(
                presets = state.presets,
                currentIndex = state.currentPresetIndex,
                // 用 weight 而不是靠子项自己 fillMaxWidth:Row 内非 weight 子项拿到的是
                // 「剩余宽度」约束,实测在真机上不会被撑满,chip 只占半行(2026-09-16 真机发现)
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))

        // ④ 看守位(GB/T 28181-2016 控制 / 2022 才补查询)
        HomePositionCard(state)

        // ⑤ 巡航轨迹(若平台设过)
        if (state.cruiseTracks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("巡航")
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.cruiseTracks.entries.sortedBy { it.key }.forEach { entry ->
                        val trackNum = entry.key
                        val points = entry.value
                        val active = state.activeCruiseTrack == trackNum
                        val bg = if (active) UvpColor.Primary else UvpColor.BorderLight
                        val fg = if (active) Color.White else UvpColor.TextSecondary
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(bg)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "T$trackNum·${points.size}",
                                color = fg,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分区小标题(预置位 / 巡航 / 看守位),统一字号与颜色。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp,
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

/** 平台 FI 速率的积分节拍。与 [rememberRepeatPress] 的长按重复间隔同源(60ms),观感一致。 */
private const val LENS_TICK_MS = 60L

/** [LENS_TICK_MS] 的秒表示 —— 积分是「速率(每秒) × 时间(秒)」。 */
private const val LENS_TICK_SECONDS = 0.06f

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
            .padding(horizontal = 10.dp, vertical = 9.dp),
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

@Composable
private fun PresetChipRow(
    presets: Map<Int, PtzPoseDto>,
    currentIndex: Int?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (idx in 1..8) {
            PresetChip(
                idx = idx,
                isSet = presets.containsKey(idx),
                isCurrent = currentIndex == idx,
            )
        }
    }
}

@Composable
private fun RowScope.PresetChip(
    idx: Int,
    isSet: Boolean,
    isCurrent: Boolean,
) {
    val bg = when {
        isCurrent -> UvpColor.Primary
        isSet -> UvpColor.PrimaryLight
        else -> UvpColor.BorderLight
    }
    val fg = when {
        isCurrent -> Color.White
        isSet -> UvpColor.Primary
        else -> UvpColor.TextHint
    }
    val targetScale = if (isCurrent) 1.05f else 1f
    val scale by animateFloatAsState(
        targetScale,
        animationSpec = tween(durationMillis = if (isCurrent) 200 else 300),
        label = "preset-scale-$idx"
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(26.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .let { m ->
                if (isCurrent) m.border(1.dp, UvpColor.Primary, RoundedCornerShape(6.dp))
                else m
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "P$idx",
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (isCurrent || isSet) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
