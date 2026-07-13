package com.uvp.sim.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 子页导航栈,用于全局广播"当前是否处于子页"以便 App shell 隐藏悬浮 tab bar。
 * 值 = 当前打开的子页数量;0 表示回到顶层 tab。
 */
class SubPageStack {
    val depth: MutableState<Int> = mutableIntStateOf(0)
    val isInSubPage: Boolean get() = depth.value > 0
}

val LocalSubPageStack = staticCompositionLocalOf { SubPageStack() }

/**
 * 子页容器 —— iOS 风返回体验一站式落地:
 *
 * 1. **Tab bar 自动隐藏** —— 挂载时给 [LocalSubPageStack] depth++,卸载时 --,
 *    [App] 里的 FloatingBottomBar 通过 AnimatedVisibility 消失/回归。
 * 2. **左/右边缘 swipe-back 手势** —— iOS UINavigationController 的
 *    interactivePopGestureRecognizer 只支持左边缘;考虑到国产 Android 用户
 *    普遍习惯双边返回,这里做**双边支持**:屏幕左边缘 24dp 内向右拖,或右
 *    边缘 24dp 内向左拖,横向位移超过 30% 屏宽,松手动画滑出后调用 [onBack]。
 * 3. **跟手位移** —— 拖动期间内容 translationX 跟随手指(带符号:左边缘起手
 *    向右滑为正,右边缘起手向左滑为负),抬起判定通过则 Animatable 补间到
 *    对应方向屏幕外;否则弹回 0。
 *
 * 手势冲突处理:down 事件先落到 pointerInput,若纵向位移先超过 slop 判定为纵向
 * 滚动意图,放弃手势,让子内容(LazyColumn / verticalScroll)接管;若横向先过 slop
 * 才 consume,阻止子内容看到后续 delta。
 */
@Composable
fun SubPageContainer(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val stack = LocalSubPageStack.current
    DisposableEffect(stack) {
        stack.depth.value += 1
        onDispose { stack.depth.value -= 1 }
    }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val edgeThresholdPx = with(density) { 24.dp.toPx() }
        val slopPx = with(density) { 8.dp.toPx() }
        val popThresholdPx = screenWidthPx * 0.3f

        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(UvpColor.Bg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        val fromLeft = down.position.x < edgeThresholdPx
                        val fromRight = down.position.x > screenWidthPx - edgeThresholdPx
                        if (!fromLeft && !fromRight) return@awaitEachGesture
                        // dir = +1 表示左边缘起手,向右拖为正;-1 表示右边缘,向左拖为负
                        val dir = if (fromLeft) 1f else -1f

                        var claimed = false
                        var totalDx = 0f
                        var totalDy = 0f

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y

                            if (!claimed) {
                                // 纵向先过 slop → 判为滚动,退出手势
                                if (abs(totalDy) > slopPx && abs(totalDy) > abs(totalDx)) break
                                // 横向位移必须跟起手方向一致才认领 —— 左边缘 dx>0,右边缘 dx<0
                                if (totalDx * dir > slopPx) claimed = true
                            }

                            if (claimed) {
                                change.consume()
                                // 允许朝返回方向拖(带符号累计),回拽超过 0 时钳位到 0,
                                // 避免用户往反方向拖导致内容偏离原位
                                val next = dragOffset.value + delta.x
                                val clamped = if (fromLeft) next.coerceAtLeast(0f)
                                              else next.coerceAtMost(0f)
                                scope.launch { dragOffset.snapTo(clamped) }
                            }

                            if (!change.pressed) {
                                if (claimed) {
                                    scope.launch {
                                        if (abs(dragOffset.value) > popThresholdPx) {
                                            dragOffset.animateTo(
                                                screenWidthPx * dir,
                                                animationSpec = tween(220, easing = FastOutSlowInEasing)
                                            )
                                            onBack()
                                        } else {
                                            dragOffset.animateTo(
                                                0f,
                                                animationSpec = tween(180, easing = FastOutSlowInEasing)
                                            )
                                        }
                                    }
                                }
                                break
                            }
                        }
                    }
                }
                .graphicsLayer { translationX = dragOffset.value }
        ) {
            content()
        }
    }
}
