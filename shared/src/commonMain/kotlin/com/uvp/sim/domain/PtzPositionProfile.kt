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

fun DeviceControlModel.adjustLocalPtzPosition(
    panDelta: Float,
    tiltDelta: Float,
    zoomDelta: Float,
): DeviceControlModel {
    val nextPan = (panAngle + panDelta).coerceIn(-180f, 180f)
    val nextTilt = (tiltAngle + tiltDelta).coerceIn(-90f, 90f)
    val nextZoom = (zoomLevel + zoomDelta).coerceIn(1f, 20f)
    if (nextPan == panAngle && nextTilt == tiltAngle && nextZoom == zoomLevel) return this
    val target = PtzPose(nextPan, nextTilt, nextZoom)
    return copy(
        panAngle = nextPan,
        tiltAngle = nextTilt,
        zoomLevel = nextZoom,
        pendingEffect = DeviceEffect.LocalPoseGoto(target),
    )
}

private const val BASE_HORIZONTAL_FIELD_ANGLE = 60.0
private const val BASE_VERTICAL_FIELD_ANGLE = 36.0
private const val BASE_MAX_VIEW_DISTANCE = 300.0
