package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.DeviceCommandCategory
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DeviceControlRenderState
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.domain.deriveRenderState
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import com.uvp.sim.ui.model.DeviceCommandCategoryDto
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

/**
 * PR-A T3.2 实现. enum 用 valueOf(name); data class 字段 1:1.
 *
 * 轨 ④ PR-UI-PROTOCOL-FIX:Mapper 入口改成 `(model, render) → Dto`,业务字段从 [DeviceControlModel] 来,
 * 渲染派生(含 [DeviceCommandCategoryDto])从 [DeviceControlRenderState] 来。UI 不再读
 * `lastCommand.rawHex` 判 Tab,改读 `lastCommandCategory`。
 */

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

fun DeviceCommandCategory.toDto(): DeviceCommandCategoryDto = DeviceCommandCategoryDto.valueOf(name)

/**
 * Mapper 主入口:由业务 [DeviceControlModel](single source of truth)+ 渲染派生
 * [DeviceControlRenderState] 组装出 UI DTO。
 *
 * 渲染派生字段来源:
 *  - [DeviceControlDto.lastCommandCategory] ← `render.lastCommandCategory`
 *  - 其余 25 字段全部来自 [DeviceControlModel]
 */
fun toDeviceControlDto(
    model: DeviceControlModel,
    render: DeviceControlRenderState,
): DeviceControlDto = DeviceControlDto(
    panAngle = model.panAngle,
    tiltAngle = model.tiltAngle,
    zoomLevel = model.zoomLevel,
    irisLevel = model.irisLevel,
    focusLevel = model.focusLevel,
    panSpeed = model.panSpeed,
    tiltSpeed = model.tiltSpeed,
    zoomSpeed = model.zoomSpeed,
    isRecording = model.isRecording,
    isGuarded = model.isGuarded,
    isAlarming = model.isAlarming,
    isRebooting = model.isRebooting,
    dragZoomRect = model.dragZoomRect?.toDto(),
    presets = model.presets.mapValues { it.value.toDto() },
    currentPresetIndex = model.currentPresetIndex,
    homePosition = model.homePosition?.toDto(),
    homePositionEnabled = model.homePositionEnabled,
    cruiseTracks = model.cruiseTracks,
    activeCruiseTrack = model.activeCruiseTrack,
    auxStates = model.auxStates,
    auxTimestamps = model.auxTimestamps,
    lastCommand = model.lastCommand?.toDto(),
    lastPreciseCtrl = model.lastPreciseCtrl?.toDto(),
    upgradeProgress = model.upgradeProgress?.toDto(),
    pendingEffect = model.pendingEffect?.toDto(),
    lastCommandCategory = render.lastCommandCategory?.toDto(),
)

/**
 * 便捷扩展:从 [DeviceControlModel] 直接出 DTO,内部用 [deriveRenderState] 派生渲染层。
 * 业务路径 / Android 壳收 model StateFlow 时用这个,UI 不需要单独 hold RenderState。
 */
fun DeviceControlModel.toDto(): DeviceControlDto =
    toDeviceControlDto(this, deriveRenderState(this))
