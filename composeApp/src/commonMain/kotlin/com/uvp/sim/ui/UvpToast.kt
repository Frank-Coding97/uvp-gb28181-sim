package com.uvp.sim.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 自定义 Toast 组件 — 替代 Material3 Snackbar 的 "黑底撑宽" 默认样式。
 *
 * 视觉:浮在内容上方的圆角胶囊,4 种语义色(成功/警告/错误/中性),
 * 带 icon + 文字。淡入 + 上滑入,1.8s 后淡出 + 上滑出。
 *
 * 用法:
 *   ```
 *   UvpToastHost {
 *       // 你的页面内容
 *   }
 *   val toast = LocalToastHost.current
 *   toast.success("音视频参数已保存")
 *   ```
 */
class ToastHostState {
    private val messageState: MutableState<ToastMessage?> = mutableStateOf(null)
    val message: ToastMessage? get() = messageState.value

    fun show(message: ToastMessage) { messageState.value = message }
    fun success(text: String) = show(ToastMessage(text, ToastKind.SUCCESS))
    fun info(text: String) = show(ToastMessage(text, ToastKind.INFO))
    fun warning(text: String) = show(ToastMessage(text, ToastKind.WARNING))
    fun error(text: String) = show(ToastMessage(text, ToastKind.ERROR))

    internal fun clear() { messageState.value = null }
}

data class ToastMessage(
    val text: String,
    val kind: ToastKind,
    /** Auto-dismiss after this many millis. 0 = manual only. */
    val durationMs: Long = 1800L,
    /** Stable key — change to force re-show even if same text/kind. */
    val key: Long = currentMillis()
)

enum class ToastKind { SUCCESS, INFO, WARNING, ERROR }

val LocalToastHost = compositionLocalOf<ToastHostState> {
    error("UvpToastHost not provided")
}

/**
 * Wrap your screen content. The host renders [content] full-size and overlays
 * the active toast at the top.
 */
@Composable
fun UvpToastHost(
    state: ToastHostState = remember { ToastHostState() },
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalToastHost provides state) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            ToastOverlay(state)
        }
    }
}

@Composable
private fun ToastOverlay(state: ToastHostState) {
    val active = state.message
    LaunchedEffect(active?.key) {
        val msg = active ?: return@LaunchedEffect
        if (msg.durationMs > 0) {
            delay(msg.durationMs)
            // Only clear if still showing the same message
            if (state.message?.key == msg.key) state.clear()
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 88.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = active != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
        ) {
            active?.let { ToastBubble(it) }
        }
    }
}

@Composable
private fun ToastBubble(message: ToastMessage) {
    val spec = message.kind.style()
    Row(
        modifier = Modifier
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(spec.bg)
            .border(1.dp, spec.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(20.dp).clip(CircleShape).background(spec.iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(spec.icon, contentDescription = null,
                modifier = Modifier.size(14.dp), tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Text(message.text, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, color = spec.textColor)
    }
}

private data class ToastStyle(
    val bg: Color, val border: Color,
    val iconBg: Color, val icon: ImageVector,
    val textColor: Color
)

@Composable
private fun ToastKind.style(): ToastStyle = when (this) {
    ToastKind.SUCCESS -> ToastStyle(
        bg = UvpColor.SuccessBg, border = UvpColor.SuccessBorder,
        iconBg = UvpColor.Success, icon = Icons.Outlined.CheckCircle,
        textColor = UvpColor.SuccessText
    )
    ToastKind.INFO -> ToastStyle(
        bg = UvpColor.Surface, border = UvpColor.Border,
        iconBg = UvpColor.Primary, icon = Icons.Outlined.Info,
        textColor = UvpColor.Text
    )
    ToastKind.WARNING -> ToastStyle(
        bg = UvpColor.WarningBg, border = UvpColor.WarningBorder,
        iconBg = UvpColor.Warning, icon = Icons.Outlined.Warning,
        textColor = UvpColor.Warning
    )
    ToastKind.ERROR -> ToastStyle(
        bg = UvpColor.DangerBg, border = UvpColor.DangerBorder,
        iconBg = UvpColor.Danger, icon = Icons.Outlined.Error,
        textColor = UvpColor.DangerText
    )
}

private fun currentMillis(): Long {
    // Compose Multiplatform-friendly: use kotlin time when available,
    // fallback to a monotonic-ish counter.
    return _toastKeyCounter.let { _toastKeyCounter += 1; it }
}

private var _toastKeyCounter: Long = 1
