package com.uvp.sim.ui.model.mapper

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.CruiseTrackState
import com.uvp.sim.domain.DeviceCommandCategory
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DeviceControlRenderState
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.ScanGroupState
import com.uvp.sim.domain.StorageCard
import com.uvp.sim.domain.StorageCardReading
import com.uvp.sim.domain.StorageCardStatus
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.domain.deriveRenderState
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import com.uvp.sim.ui.model.CruiseTrackDto
import com.uvp.sim.ui.model.DeviceCommandCategoryDto
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.DragZoomRectDto
import com.uvp.sim.ui.model.FocusDirectionDto
import com.uvp.sim.ui.model.IrisDirectionDto
import com.uvp.sim.ui.model.LastDeviceCommandDto
import com.uvp.sim.ui.model.PanDirectionDto
import com.uvp.sim.ui.model.PtzCommandDto
import com.uvp.sim.ui.model.PtzPoseDto
import com.uvp.sim.ui.model.ScanGroupDto
import com.uvp.sim.ui.model.TiltDirectionDto
import com.uvp.sim.ui.model.StorageCardDto
import com.uvp.sim.ui.model.StorageCardReadingDto
import com.uvp.sim.ui.model.StorageCardStatusDto
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
    DragZoomRectDto(midX, midY, lengthX, lengthY, frameLength, frameWidth)

fun CruiseTrackState.toDto(): CruiseTrackDto =
    CruiseTrackDto(points = points, speed = speed, dwellTime = dwellTime)

fun ScanGroupState.toDto(): ScanGroupDto =
    ScanGroupDto(
        leftBoundary = leftBoundary?.toDto(),
        rightBoundary = rightBoundary?.toDto(),
        speed = speed,
    )

fun StorageCardStatus.toDto(): StorageCardStatusDto = StorageCardStatusDto.valueOf(name)

fun StorageCard.toDto(): StorageCardDto = StorageCardDto(id = id, name = name, capacityMb = capacityMb)

fun StorageCardReading.toDto(): StorageCardReadingDto = StorageCardReadingDto(
    cardId = cardId,
    status = status.toDto(),
    progress = progress,
    freeMb = freeMb,
)

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
    /**
     * 设备的本机配置。
     *
     * ⛔ **刻意不给默认值**：设备配置族里有几项（前端 OSD 的坐标、遮挡区域的像素基准）
     * 要按**视频帧的像素尺寸**归一化，而那个尺寸只在这里拿得到。给个 `SimConfig()` 兜底
     * 会让"忘传"变成一次**静默的坐标错位**（矩形仍然画出来，只是位置不对）——
     * 交给编译器拦住才是对的。
     */
    config: SimConfig,
): DeviceControlDto = DeviceControlDto(
    panAngle = model.panAngle,
    tiltAngle = model.tiltAngle,
    zoomLevel = model.zoomLevel,
    irisLevel = model.irisLevel,
    focusLevel = model.focusLevel,
    panSpeed = model.panSpeed,
    tiltSpeed = model.tiltSpeed,
    zoomSpeed = model.zoomSpeed,
    focusSpeed = model.focusSpeed,
    irisSpeed = model.irisSpeed,
    isRecording = model.isRecording,
    isGuarded = model.isGuarded,
    isAlarming = model.isAlarming,
    isRebooting = model.isRebooting,
    dragZoomRect = model.dragZoomRect?.toDto(),
    presets = model.presets.mapValues { it.value.toDto() },
    currentPresetIndex = model.currentPresetIndex,
    homePosition = model.homePosition?.toDto(),
    homePositionEnabled = model.homePositionEnabled,
    homePositionPresetIndex = model.homePositionPresetIndex,
    homePositionResetTime = model.homePositionResetTime,
    cruiseTracks = model.cruiseTracks.mapValues { it.value.toDto() },
    activeCruiseTrack = model.activeCruiseTrack,
    scanGroups = model.scanGroups.mapValues { it.value.toDto() },
    activeScanGroup = model.activeScanGroup,
    auxStates = model.auxStates,
    auxTimestamps = model.auxTimestamps,
    lastCommand = model.lastCommand?.toDto(),
    lastPreciseCtrl = model.lastPreciseCtrl?.toDto(),
    upgradeProgress = model.upgradeProgress?.toDto(),
    pendingEffect = model.pendingEffect?.toDto(),
    lastCommandCategory = render.lastCommandCategory?.toDto(),
    storageCards = model.storageCards.map { it.toDto() },
    storageCardReadings = model.storageCardReadings.mapValues { it.value.toDto() },
    storageCardQueriedAtMs = model.storageCardQueriedAtMs,
    storageCardQueryCount = model.storageCardQueryCount,
    deviceConfig = model.toDeviceConfigDto(config),
)

/**
 * 便捷扩展:从 [DeviceControlModel] 直接出 DTO,内部用 [deriveRenderState] 派生渲染层。
 * 业务路径 / Android 壳收 model StateFlow 时用这个,UI 不需要单独 hold RenderState。
 */
fun DeviceControlModel.toDto(config: SimConfig): DeviceControlDto =
    toDeviceControlDto(this, deriveRenderState(this), config)
