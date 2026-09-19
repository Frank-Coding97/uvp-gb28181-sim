package com.uvp.sim.domain

import com.uvp.sim.gb28181.PtzPositionSnapshot

/** 将共享实际姿态转换为 GB/T 28181-2022 A.2.6.15 六字段快照。 */
fun DeviceControlModel.toPtzPositionSnapshot(): PtzPositionSnapshot {
    val effectiveZoom = zoomLevel.coerceAtLeast(1f).toDouble()
    return PtzPositionSnapshot(
        pan = panAngle.toDouble(),
        tilt = tiltAngle.toDouble(),
        zoom = zoomLevel.toDouble(),
        horizontalFieldAngle = BASE_HORIZONTAL_FIELD_ANGLE / effectiveZoom,
        verticalFieldAngle = BASE_VERTICAL_FIELD_ANGLE / effectiveZoom,
        maxViewDistance = BASE_MAX_VIEW_DISTANCE * effectiveZoom,
    )
}

/**
 * 用指定的三轴姿态覆盖快照，**光学三量按同一公式重算**。
 *
 * 用途：`PTZPosition` 查询应答（A.2.6.15）要求六字段齐全，而设备"当前姿态"有两个来源 ——
 * 平台下发过精确控制时是 [DeviceControlModel.lastPreciseCtrl]，否则是 UI 积分出来的
 * `panAngle/tiltAngle/zoomLevel`。后者直接走 [toPtzPositionSnapshot]；前者需要把
 * 三轴换掉，但 `HorizontalFieldAngle` / `VerticalFieldAngle` / `MaxViewDistance`
 * **必须跟着新的变焦倍数重算** —— 它们是变焦的函数，copy 旧值会让应答自相矛盾
 * （报 3 倍变焦却带 1 倍的视场角），而平台侧据此算的视场范围会整体偏掉。
 *
 * ⛔ 光学公式只在这里和 [toPtzPositionSnapshot] 各写一次是**刻意**的：两处都从同一组
 * `BASE_*` 常量出发，但取值来源不同（一个是 model 字段、一个是入参 pose），
 * 抽成一个函数反而要传两套参数。
 */
fun PtzPositionSnapshot.withPose(pose: PtzPose): PtzPositionSnapshot {
    val effectiveZoom = pose.zoom.coerceAtLeast(1f).toDouble()
    return copy(
        pan = pose.pan.toDouble(),
        tilt = pose.tilt.toDouble(),
        zoom = pose.zoom.toDouble(),
        horizontalFieldAngle = BASE_HORIZONTAL_FIELD_ANGLE / effectiveZoom,
        verticalFieldAngle = BASE_VERTICAL_FIELD_ANGLE / effectiveZoom,
        maxViewDistance = BASE_MAX_VIEW_DISTANCE * effectiveZoom,
    )
}

/**
 * 本机手操把云台姿态按**增量**推进(UI 长按反复调用)。
 *
 * 2026-09-16 扩了两个可选增量给 FI 族(聚焦/光圈):平台下发 FI 命令时只发"带速度的开始
 * 动作"(见 [DeviceControlModel.focusSpeed]),设备侧位置得靠速率×时间推出来,而
 * 积分节拍在 UI(与方向盘长按同一套 [com.uvp.sim.ui.simulate.ptz.rememberRepeatPress] 观感),
 * 算出的增量仍旧落到这里、写回 Model —— 保持「Model 是唯一真相源」,切 Tab 再回来位置不丢。
 */
fun DeviceControlModel.adjustLocalPtzPosition(
    panDelta: Float,
    tiltDelta: Float,
    zoomDelta: Float,
    focusDelta: Float = 0f,
    irisDelta: Float = 0f,
): DeviceControlModel {
    val nextPan = (panAngle + panDelta).coerceIn(-180f, 180f)
    val nextTilt = (tiltAngle + tiltDelta).coerceIn(-90f, 90f)
    val nextZoom = (zoomLevel + zoomDelta).coerceIn(1f, 20f)
    // FI 族的行程是归一化 0~1(进度条口径),不像变焦有物理倍率口径。
    val nextFocus = (focusLevel + focusDelta).coerceIn(0f, 1f)
    val nextIris = (irisLevel + irisDelta).coerceIn(0f, 1f)
    if (nextPan == panAngle && nextTilt == tiltAngle && nextZoom == zoomLevel &&
        nextFocus == focusLevel && nextIris == irisLevel
    ) {
        // 五个轴都没动就原样返回(引用相等),让上层 StateFlow 不发出无意义的新值 ——
        // 镜头积分节拍每秒会调这里几次,常态必须是无操作。
        return this
    }
    val target = PtzPose(nextPan, nextTilt, nextZoom)
    return copy(
        panAngle = nextPan,
        tiltAngle = nextTilt,
        zoomLevel = nextZoom,
        focusLevel = nextFocus,
        irisLevel = nextIris,
        // 镜头两轴没有三维表现,不进 LocalPoseGoto —— 否则相机每节拍都要"缓动到新位姿",
        // 表现为画面持续抖动(M6 引入 LocalPoseGoto 时踩过同类问题)。
        pendingEffect = if (panDelta != 0f || tiltDelta != 0f || zoomDelta != 0f) {
            DeviceEffect.LocalPoseGoto(target)
        } else {
            pendingEffect
        },
    )
}

private const val BASE_HORIZONTAL_FIELD_ANGLE = 60.0
private const val BASE_VERTICAL_FIELD_ANGLE = 36.0
private const val BASE_MAX_VIEW_DISTANCE = 300.0
