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
import com.uvp.sim.ui.model.TargetTrackModeDto
import kotlinx.coroutines.delay

/**
 * 顶部状态短句 — "现在平台/设备到底在干什么"维度的文案,
 * 跟底部 StatusDot(REC/GUARD/ALARM/REBOOT 持续开关状态) 不重叠.
 *
 * 优先级: 远程重启中 > 开机自检中 > 巡航运行中 > 扫描运行中 > 目标跟踪中 > 预置位调用
 *        > 精确/本地跳转 > PTZ 运动中 > 刚收到平台命令 (3s 内) > 等待中.
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
    // 扫描同样是**设备自主行为**,但它比巡航还"隐形":它驱动的是速率字段(`panSpeed`),
    // 既没有 pendingEffect、也没有逐步的 lastCommand。不加下面这条分支,头条会掉到
    // `hasMotion` 的「PTZ 运动中」—— 操作员点的是「开始扫描」,看到的却像自己按了方向键。
    val scanGroup = state.activeScanGroup
    // 目标跟踪与前两条同类(设备自主行为),但它更"静":既没有 pendingEffect、也不驱动任何速率字段
    // (`panSpeed` 那套是扫描用的),而且**平台侧没有任何回执** —— 9.3.1 d) 把它列为无应答命令
    // (表 1 序号 13 应答栏"（无）"),附录 A 也没有查询命令能读回跟踪态。
    // 不加这条分支,平台点完「手动跟踪」头条会掉到「刚收到 TargetTrack」(3 秒后连这句也没了),
    // 操作员看到的就是"点了没反应";而设备确实收到了。
    val track = state.targetTrack

    val (text, color, dotColor) = when {
        effect is DeviceEffectDto.Reboot -> Triple("远程重启中", UvpColor.Primary, UvpColor.Primary)
        selfTesting -> Triple("开机自检中", UvpColor.Primary, UvpColor.Primary)
        cruiseTrack != null && effect is DeviceEffectDto.PresetRecall ->
            Triple("巡航 #$cruiseTrack 运行中 → P${effect.index}", UvpColor.Primary, UvpColor.Primary)
        // 停留期间没有 pending effect，但巡航仍在跑，不能退回「等待平台下发控制指令」。
        cruiseTrack != null ->
            Triple("巡航 #$cruiseTrack 运行中", UvpColor.Primary, UvpColor.Primary)
        // 「运行中」与「已启动」分开:标准的"开始扫描"只是一条指令,缺边界时设备**停在原地**
        // (见 SimulatorEngine 的告警日志与 PtzTabContent 的同名区分),这时说"运行中"就是
        // 设备替自己编一句没发生的事。
        scanGroup != null -> {
            val group = state.scanGroups[scanGroup]
            val sweepable = group?.leftBoundary != null && group?.rightBoundary != null
            Triple(
                if (sweepable) "扫描 #$scanGroup 运行中" else "扫描 #$scanGroup 已启动 · 缺边界",
                UvpColor.Primary,
                UvpColor.Primary,
            )
        }
        // 目标跟踪 —— 排在扫描之后、`pendingEffect` 之前:它是**持续状态**,一旦被一次性的
        // 预置位/精确跳转盖住,那个跟踪态就再也不会自己冒出来(没有后续事件能把它顶回来)。
        // 用 Warning(橙)与画布上的跟踪框同色,一眼能对上"头条说的就是画面上那个框"。
        track != null -> Triple(
            when (track.mode) {
                // ⛔ `Auto` 必须带"模拟"字样:那个框是模拟器编的(设备 AI 没接真源),
                //    见 `TargetTrackState.SIMULATED_AUTO_BOX`。写成"自动跟踪中"就会被当成
                //    "设备真的自己找到目标了",而这条命令平台收不到任何回执,证伪不了。
                TargetTrackModeDto.Auto -> "目标跟踪中 · 自动（模拟目标）"
                TargetTrackModeDto.Manual ->
                    if (track.box == null) "目标跟踪中 · 手动 · 无框选区域"
                    else "目标跟踪中 · 手动"
            },
            UvpColor.Warning,
            UvpColor.Warning,
        )
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
