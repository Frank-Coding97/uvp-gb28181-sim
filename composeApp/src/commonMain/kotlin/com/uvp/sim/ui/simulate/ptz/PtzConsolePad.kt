package com.uvp.sim.ui.simulate.ptz

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import kotlinx.coroutines.delay
import kotlin.math.sign

/**
 * 云台方向盘 — 3×3 方盘,八个方位键 + 中心轴心点。
 *
 * 这是「云台」页里**唯一可交互**的控件(其余参数卡都是只读回显)。
 *
 * 操作方式(2026-09-16 按用户反馈重做):
 *  - **按下即转**:手指落下立刻走一步([PAD_STEP]),单击手感跟原来一致;
 *  - **长按连续转**:按住不放超过 [REPEAT_START_DELAY_MS] 后进入连续步进
 *    (每 [REPEAT_INTERVAL_MS] 走一小步 [PAD_CONT_STEP]),看起来就是云台在匀速转;
 *  - **松手即停**:抬起手指立刻停止 —— 由 [LaunchedEffect] 随按下态取消来保证,
 *    不用手写 tick 清理。
 *
 * 改之前是「点一下走一步」,不符合大众对云台操控的认知(实体球机都是按住转、松手停)。
 *
 * 方向与坐标系约定(跟 PTZ 位姿一致):
 *  - 水平:右为 `+pan`,左为 `-pan`
 *  - 俯仰:上为 `+tilt`,下为 `-tilt`(与 [com.uvp.sim.ui.model.DeviceControlDto.tiltAngle] 同向)
 *  - 四个斜角同时给 pan/tilt,共 8 个方向
 *
 * 实现细节:
 *  - 按键一律用 `Box + pointerInput` 而非 Material3 `IconButton` —— 后者会强制
 *    48dp 最小交互尺寸,9 个键排开会直接把方盘撑爆(2026-09-16 在旧调试条上踩过)。
 *  - 箭头用**字符**而不是 Material 图标 + `Modifier.rotate`:旋转类图标时会创建离屏层,
 *    层的尺寸就是图标节点的尺寸,斜向箭头伸出边界的那一半会被直接裁掉,渲染成半个直角
 *    (2026-09-16 真机踩过)。字符没有这个问题,顺带 iOS / Desktop 也一致。
 */
@Composable
internal fun PtzConsolePad(
    onAdjust: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    onActiveChange: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.Border, RoundedCornerShape(14.dp))
            .padding(PAD_PADDING),
        verticalArrangement = Arrangement.spacedBy(PAD_GAP),
    ) {
        // 九宫格排布,中间那格是轴心装饰点(null)
        PAD_LAYOUT.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
                row.forEach { dir ->
                    if (dir == null) {
                        PadCenter()
                    } else {
                        PadKey(dir, onAdjust, onActiveChange)
                    }
                }
            }
        }
    }
}

/** 单击一步的位移量(度)。跟平台 PTZCmd 的速率不是一个量纲 —— 这里是「按一下走一步」。 */
private const val PAD_STEP = 5f

/**
 * 长按连续时的单步位移(度)。取小值 + 高频触发,累积出来才是「匀速转动」而不是「一格一格跳」。
 * 1° / 60ms ≈ 16.7°/s,水平方向从 0° 转到 90° 约 5.4 秒,演示时肉眼看得清。
 */
private const val PAD_CONT_STEP = 1f

/** 按住多久后从「单击一步」切成「连续转动」。留这个延迟,单击才不会被持续转动干扰。 */
private const val REPEAT_START_DELAY_MS = 320L

/** 连续转动时的步进间隔。 */
private const val REPEAT_INTERVAL_MS = 60L

private val PAD_KEY = 36.dp
private val PAD_GAP = 4.dp
private val PAD_PADDING = 6.dp

/** 方盘整体边长。同排的其它参数卡按这个高度对齐,避免各排高矮不一。 */
internal val PtzConsolePadSide: Dp = PAD_KEY * 3 + PAD_GAP * 2 + PAD_PADDING * 2

/**  一个方位键的参数:位移量 + 显示的箭头字符 + 无障碍描述。 */
private class DirKey(
    val dPan: Float,
    val dTilt: Float,
    val glyph: String,
    val cd: String,
)

/**
 * 九宫格排布 —— 行序 = 上 / 中 / 下,列序 = 左 / 中 / 右。
 * `null` = 中心那个装饰点(不可点)。
 */
private val PAD_LAYOUT: List<List<DirKey?>> = listOf(
    listOf(
        DirKey(-PAD_STEP, PAD_STEP, "↖", "左上"),
        DirKey(0f, PAD_STEP, "↑", "向上"),
        DirKey(PAD_STEP, PAD_STEP, "↗", "右上"),
    ),
    listOf(
        DirKey(-PAD_STEP, 0f, "←", "向左"),
        null,
        DirKey(PAD_STEP, 0f, "→", "向右"),
    ),
    listOf(
        DirKey(-PAD_STEP, -PAD_STEP, "↙", "左下"),
        DirKey(0f, -PAD_STEP, "↓", "向下"),
        DirKey(PAD_STEP, -PAD_STEP, "↘", "右下"),
    ),
)

/** [rememberRepeatPress] 的产物:可直接挂到节点上的修饰符 + 当前是否处于按下态。 */
internal class RepeatPress(val modifier: Modifier, val pressed: Boolean)

/**
 * 「按下触发一次 + 长按持续重复」的手势修饰符。
 *
 * 用 `pointerInput` 的 down / up 生命周期驱动,而不是 `clickable` —— 后者只有单击语义,
 * 拿不到「按住期间」这个状态。松开(或手势被取消)时按下态落回 false,[LaunchedEffect]
 * 随之取消,这就是「松手即停」的实现。
 *
 * 两个回调都过 [rememberUpdatedState] 转发:`pointerInput(Unit)` 的手势节点在重组时
 * 不会重启,闭包会停留在首次组合那一版,直接捕获 lambda 会读到过期的实现。
 */
@Composable
internal fun rememberRepeatPress(
    onPressOnce: () -> Unit,
    onRepeatStep: () -> Unit,
): RepeatPress {
    var pressed by remember { mutableStateOf(false) }
    val pressOnce = rememberUpdatedState(onPressOnce)
    val repeatStep = rememberUpdatedState(onRepeatStep)

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(REPEAT_START_DELAY_MS)
        while (true) {
            repeatStep.value()
            delay(REPEAT_INTERVAL_MS)
        }
    }

    // ⚠️ 必须 remember:指针事件节点比的是 block 实例,每次重组新建一个 lambda 会让元素
    // 判定为「变了」→ update 里 reset 掉正在跑的手势。表现就是「按住期间只要有一帧重组
    // 就松手了」,而按住时颜色动画每帧都在重组 —— 长按连续会直接失效。
    // 缓存成只构建一次后,闭包捕获的是跨组合稳定的 MutableState 实例,取最新回调靠
    // rememberUpdatedState。
    val modifier = remember {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                pressOnce.value()
                waitForUpOrCancellation()
                pressed = false
            }
        }
    }
    return RepeatPress(modifier, pressed)
}

@Composable
private fun PadKey(
    dir: DirKey,
    onAdjust: (Float, Float, Float) -> Unit,
    onActiveChange: (Boolean) -> Unit,
) {
    val press = rememberRepeatPress(
        onPressOnce = { onAdjust(dir.dPan, dir.dTilt, 0f) },
        // 连续转动只按**方向**小步走,不按原步长 —— 用 kotlin.math.sign 取符号,
        // 单轴键(如「向上」)那一路的 0 会保持 0,不会被带偏(自定义 `if (x < 0) -1 else 1`
        // 会把 0 判成 +1,是个静默的方向错误).
        onRepeatStep = {
            onAdjust(
                dir.dPan.sign * PAD_CONT_STEP,
                dir.dTilt.sign * PAD_CONT_STEP,
                0f,
            )
        },
    )

    // 按下态用主题蓝铺满整键,跟常态的浅灰底拉开距离 —— 长按时一眼能看出哪个键在生效.
    val bg by animateColorAsState(
        if (press.pressed) UvpColor.Primary else UvpColor.Bg,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "pad-key-bg",
    )
    val borderColor by animateColorAsState(
        if (press.pressed) UvpColor.Primary else UvpColor.Border,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "pad-key-border",
    )
    val fg by animateColorAsState(
        if (press.pressed) Color.White else UvpColor.Text,
        animationSpec = tween(if (press.pressed) 60 else 140),
        label = "pad-key-fg",
    )

    // 把按下态往上报,让上面的位姿读数条同步高亮(「本机正在推」和「平台在推」用同一套视觉).
    // 用 DisposableEffect 而不是 LaunchedEffect:万一按住时组件被移出组合(例如自动切 Tab),
    // onDispose 也要把状态落回 false,否则读数条会一直亮着.
    DisposableEffect(press.pressed) {
        onActiveChange(press.pressed)
        onDispose { if (press.pressed) onActiveChange(false) }
    }

    Box(
        modifier = Modifier
            .size(PAD_KEY)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .then(press.modifier)
            .semantics { contentDescription = dir.cd },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dir.glyph,
            color = fg,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 中心轴心点 — 不可点,纯装饰,让九宫格看起来是个整体方盘。 */
@Composable
private fun PadCenter() {
    Box(
        modifier = Modifier.size(PAD_KEY),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(UvpColor.PrimaryLight)
        )
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(UvpColor.Primary.copy(alpha = 0.65f))
        )
    }
}
