package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.DeviceEffectDto
import kotlinx.coroutines.delay

/**
 * 顶部状态短句 — "现在平台/设备到底在干什么"维度的文案,
 * 跟底部 StatusDot(REC/GUARD/ALARM/REBOOT 持续开关状态) 不重叠.
 *
 * 优先级: 远程重启中 > 开机自检中 > 巡航运行中 > 预置位调用 > PTZ 运动中 > 刚收到平台命令 (3s 内) > 等待中.
 */
@Composable
internal fun StatusHeadline(state: DeviceControlDto) {
    val nowMs = useTickingNow(intervalMs = 500L)
    val mountMs = remember { currentTimeMs() }
    val selfTesting = (nowMs - mountMs) in 0..6_500
    val cmd = state.lastCommand
    val recentCmd = cmd != null && (nowMs - cmd.timestampMs) in 0..3_000
    val effect = state.pendingEffect
    // 巡航进行中时每走一个点都会发一次 `PresetRecall`（设备侧就是一次预置位跳转）。不加这条
    // 的话头条会打「预置位 P2 调用中」—— 操作员点的是「开始巡航」，看到的却像平台在单独调
    // 预置位，而且跳点时会一直闪不同编号。设备自主行为要报出自己的身份。
    val cruiseTrack = state.activeCruiseTrack

    val (text, color, dotColor) = when {
        effect is DeviceEffectDto.Reboot -> Triple("远程重启中", UvpColor.Primary, UvpColor.Primary)
        selfTesting -> Triple("开机自检中", UvpColor.Primary, UvpColor.Primary)
        cruiseTrack != null && effect is DeviceEffectDto.PresetRecall ->
            Triple("巡航 #$cruiseTrack 运行中 → P${effect.index}", UvpColor.Primary, UvpColor.Primary)
        // 停留期间没有 pending effect，但巡航仍在跑，不能退回「等待平台下发控制指令」。
        cruiseTrack != null ->
            Triple("巡航 #$cruiseTrack 运行中", UvpColor.Primary, UvpColor.Primary)
        effect is DeviceEffectDto.PresetRecall ->
            Triple("预置位 P${effect.index} 调用中", UvpColor.Primary, UvpColor.Primary)
        effect is DeviceEffectDto.PrecisePoseGoto ->
            Triple("精确控制 → ${formatSignedAngle(effect.targetPose.pan)} / ${formatSignedAngle(effect.targetPose.tilt)}",
                UvpColor.Primary, UvpColor.Primary)
        effect is DeviceEffectDto.LocalPoseGoto ->
            Triple("本地模拟 → ${formatSignedAngle(effect.targetPose.pan)} / ${formatSignedAngle(effect.targetPose.tilt)}",
                UvpColor.Primary, UvpColor.Primary)
        hasMotion(state) -> Triple("PTZ 运动中", UvpColor.Primary, UvpColor.Primary)
        recentCmd -> Triple("刚收到 ${cmd!!.type}", UvpColor.SuccessText, UvpColor.Success)
        else -> Triple("等待平台下发控制指令", UvpColor.TextHint, UvpColor.Border)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text,
            modifier = Modifier.padding(start = 6.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatSignedAngle(value: Float): String {
    val rounded = kotlin.math.round(value).toInt()
    return if (rounded > 0) "+$rounded°" else "$rounded°"
}

/**
 * 周期性返回当前墙上时间(ms),让基于"距离命令多久"的 UI 状态随时间自然失效.
 * 不依赖平台 API,纯 Compose + kotlinx.coroutines.delay.
 */
@Composable
internal fun useTickingNow(intervalMs: Long): Long {
    var now by remember { mutableStateOf(currentTimeMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            now = currentTimeMs()
        }
    }
    return now
}

private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

internal fun hasMotion(state: DeviceControlDto): Boolean {
    return state.panSpeed != 0f || state.tiltSpeed != 0f || state.zoomSpeed != 0f
}
