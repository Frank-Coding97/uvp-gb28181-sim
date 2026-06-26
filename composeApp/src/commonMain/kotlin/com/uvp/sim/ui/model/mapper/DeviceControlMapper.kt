package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.DragZoomRectDto
import com.uvp.sim.ui.model.FocusDirectionDto
import com.uvp.sim.ui.model.IrisDirectionDto
import com.uvp.sim.ui.model.LastDeviceCommandDto
import com.uvp.sim.ui.model.PanDirectionDto
import com.uvp.sim.ui.model.PtzCommandDto
import com.uvp.sim.ui.model.PtzPoseDto
import com.uvp.sim.ui.model.TiltDirectionDto
import com.uvp.sim.ui.model.UpgradeProgressDto
import com.uvp.sim.ui.model.UpgradeResultDto
import com.uvp.sim.ui.model.ZoomDirectionDto

/** PR-A T3.2 实现. enum 用 valueOf(name); data class 字段 1:1. */

fun PtzPose.toDto(): PtzPoseDto = PtzPoseDto(pan, tilt, zoom)

fun DragZoomRect.toDto(): DragZoomRectDto =
    DragZoomRectDto(midX, midY, lengthX, lengthY)

fun PanDirection.toDto(): PanDirectionDto = PanDirectionDto.valueOf(name)
fun TiltDirection.toDto(): TiltDirectionDto = TiltDirectionDto.valueOf(name)
fun ZoomDirection.toDto(): ZoomDirectionDto = ZoomDirectionDto.valueOf(name)
fun FocusDirection.toDto(): FocusDirectionDto = FocusDirectionDto.valueOf(name)
fun IrisDirection.toDto(): IrisDirectionDto = IrisDirectionDto.valueOf(name)

fun UpgradeResult.toDto(): UpgradeResultDto = UpgradeResultDto.valueOf(name)

fun UpgradeProgress.toDto(): UpgradeProgressDto = UpgradeProgressDto(
    sessionId = sessionId,
    firmware = firmware,
    percent = percent,
    result = result.toDto(),
)

fun PtzCommand.toDto(): PtzCommandDto = PtzCommandDto(
    panDirection = panDirection.toDto(),
    tiltDirection = tiltDirection.toDto(),
    zoomDirection = zoomDirection.toDto(),
    focusDirection = focusDirection.toDto(),
    irisDirection = irisDirection.toDto(),
    panSpeed = panSpeed,
    tiltSpeed = tiltSpeed,
    zoomSpeed = zoomSpeed,
    focusSpeed = focusSpeed,
    irisSpeed = irisSpeed,
)

fun LastDeviceCommand.toDto(): LastDeviceCommandDto = LastDeviceCommandDto(
    type = type,
    rawHex = rawHex,
    timestampMs = timestampMs,
    ptz = ptz?.toDto(),
)

fun DeviceControlState.toDto(): DeviceControlDto = DeviceControlDto(
    panAngle = panAngle,
    tiltAngle = tiltAngle,
    zoomLevel = zoomLevel,
    irisLevel = irisLevel,
    focusLevel = focusLevel,
    panSpeed = panSpeed,
    tiltSpeed = tiltSpeed,
    zoomSpeed = zoomSpeed,
    isRecording = isRecording,
    isGuarded = isGuarded,
    isAlarming = isAlarming,
    isRebooting = isRebooting,
    dragZoomRect = dragZoomRect?.toDto(),
    presets = presets.mapValues { it.value.toDto() },
    currentPresetIndex = currentPresetIndex,
    homePosition = homePosition?.toDto(),
    homePositionEnabled = homePositionEnabled,
    cruiseTracks = cruiseTracks,
    activeCruiseTrack = activeCruiseTrack,
    auxStates = auxStates,
    auxTimestamps = auxTimestamps,
    lastCommand = lastCommand?.toDto(),
    lastPreciseCtrl = lastPreciseCtrl?.toDto(),
    upgradeProgress = upgradeProgress?.toDto(),
    pendingEffect = pendingEffect?.toDto(),
)
