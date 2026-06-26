package com.uvp.sim.domain

import com.uvp.sim.gb28181.PtzCommand

/**
 * Wave 3 PR-DC-DECOUPLE(2026-06-26)兼容包装层。
 *
 * 历史:本类原是单一 25 字段 data class,业务 + 渲染字段混在一起,导致:
 *   - UI rerender 频繁(任何字段变都触发)
 *   - 单测要 mock rawHex / timestampMs 等纯渲染字段
 *
 * 现在:状态被拆成 [DeviceControlModel](业务)+ [DeviceControlRenderState](渲染派生)。
 * 本类降级为兼容 wrapper,实现:
 *   1. 通过 [model] 持有 single source of truth
 *   2. [render] 字段由 [deriveRenderState] 从 model 派生(纯函数)
 *   3. **属性委托**:暴露原 25 字段访问器 `state.panAngle / state.lastCommand / state.pendingEffect / ...`,
 *      让 UI Mapper / 老代码不改也能编过
 *   4. 兼容构造器接受原 25 命名参数,让既有测试 `DeviceControlState(panAngle = X)` 仍能构造
 *
 * 轨 ③(PR-UI-PROTOCOL-FIX)负责:
 *   - 把 composeApp/.../ui/model/mapper/DeviceControlMapper.kt 切到读 `state.model.xxx / state.render.xxx`
 *   - 删除本兼容 wrapper 的属性委托与命名参数构造器,只保留 `data class(model, render)` 二字段 shape
 *
 * **不要**在新代码中读写本类。新业务路径请直接用 [DeviceControlModel];新 UI 路径请直接用 [DeviceControlRenderState]。
 */
@Deprecated(
    message = "Wave 3 拆分:业务路径用 DeviceControlModel,UI 路径用 DeviceControlRenderState。" +
        "本兼容 wrapper 留待 PR-UI-PROTOCOL-FIX(轨 ③)删除。",
    level = DeprecationLevel.WARNING,
)
class DeviceControlState(
    /** 业务模型 — 全部 25 个原字段都在这里. */
    val model: DeviceControlModel = DeviceControlModel(),
) {
    /** UI 渲染派生 — 从 model 计算得到. */
    val render: DeviceControlRenderState = deriveRenderState(model)

    // 兼容性命名参数构造器:让既有测试 `DeviceControlState(panAngle = ..., pendingEffect = ...)` 仍可构造。
    @Suppress("LongParameterList", "DEPRECATION")
    constructor(
        panAngle: Float = 0f,
        tiltAngle: Float = 0f,
        zoomLevel: Float = 1f,
        irisLevel: Float = 0.5f,
        focusLevel: Float = 0.5f,
        panSpeed: Float = 0f,
        tiltSpeed: Float = 0f,
        zoomSpeed: Float = 0f,
        isRecording: Boolean = false,
        isGuarded: Boolean = false,
        isAlarming: Boolean = false,
        isRebooting: Boolean = false,
        dragZoomRect: DragZoomRect? = null,
        presets: Map<Int, PtzPose> = emptyMap(),
        currentPresetIndex: Int? = null,
        homePosition: PtzPose? = null,
        homePositionEnabled: Boolean = true,
        cruiseTracks: Map<Int, List<Int>> = emptyMap(),
        activeCruiseTrack: Int? = null,
        auxStates: Map<Int, Boolean> = emptyMap(),
        auxTimestamps: Map<Int, Long> = emptyMap(),
        lastCommand: LastDeviceCommand? = null,
        lastPreciseCtrl: PtzPose? = null,
        upgradeProgress: UpgradeProgress? = null,
        pendingEffect: DeviceEffect? = null,
    ) : this(
        DeviceControlModel(
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
            dragZoomRect = dragZoomRect,
            presets = presets,
            currentPresetIndex = currentPresetIndex,
            homePosition = homePosition,
            homePositionEnabled = homePositionEnabled,
            cruiseTracks = cruiseTracks,
            activeCruiseTrack = activeCruiseTrack,
            auxStates = auxStates,
            auxTimestamps = auxTimestamps,
            lastCommand = lastCommand,
            lastPreciseCtrl = lastPreciseCtrl,
            upgradeProgress = upgradeProgress,
            pendingEffect = pendingEffect,
        )
    )

    // ----- 属性委托:让 UI Mapper / 老代码 `state.panAngle` 写法继续工作 -----
    val panAngle: Float get() = model.panAngle
    val tiltAngle: Float get() = model.tiltAngle
    val zoomLevel: Float get() = model.zoomLevel
    val irisLevel: Float get() = model.irisLevel
    val focusLevel: Float get() = model.focusLevel
    val panSpeed: Float get() = model.panSpeed
    val tiltSpeed: Float get() = model.tiltSpeed
    val zoomSpeed: Float get() = model.zoomSpeed
    val isRecording: Boolean get() = model.isRecording
    val isGuarded: Boolean get() = model.isGuarded
    val isAlarming: Boolean get() = model.isAlarming
    val isRebooting: Boolean get() = model.isRebooting
    val dragZoomRect: DragZoomRect? get() = model.dragZoomRect
    val presets: Map<Int, PtzPose> get() = model.presets
    val currentPresetIndex: Int? get() = model.currentPresetIndex
    val homePosition: PtzPose? get() = model.homePosition
    val homePositionEnabled: Boolean get() = model.homePositionEnabled
    val cruiseTracks: Map<Int, List<Int>> get() = model.cruiseTracks
    val activeCruiseTrack: Int? get() = model.activeCruiseTrack
    val auxStates: Map<Int, Boolean> get() = model.auxStates
    val auxTimestamps: Map<Int, Long> get() = model.auxTimestamps
    val lastCommand: LastDeviceCommand? get() = model.lastCommand
    val lastPreciseCtrl: PtzPose? get() = model.lastPreciseCtrl
    val upgradeProgress: UpgradeProgress? get() = model.upgradeProgress
    val pendingEffect: DeviceEffect? get() = model.pendingEffect

    // equals / hashCode / toString 委托给 model(render 是 model 的纯函数派生,无独立身份)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceControlState) return false
        return model == other.model
    }

    override fun hashCode(): Int = model.hashCode()

    override fun toString(): String = "DeviceControlState($model)"
}

// 以下五个类型保持原位置(commonMain 域层),被 Model + RenderState + UI Mapper 复用。

/** GB-2022 §9.13 设备升级进度状态. */
data class UpgradeProgress(
    val sessionId: String,
    val firmware: String,
    val percent: Int,
    val result: UpgradeResult,
)

enum class UpgradeResult { InProgress, Success, Failure }

data class PtzPose(val pan: Float, val tilt: Float, val zoom: Float)

data class DragZoomRect(
    val midX: Int,
    val midY: Int,
    val lengthX: Int,
    val lengthY: Int,
)

data class LastDeviceCommand(
    val type: String,
    val rawHex: String,
    val timestampMs: Long,
    val ptz: PtzCommand? = null,
)

sealed class DeviceEffect {
    data object IFrameFlash : DeviceEffect()
    data object Reboot : DeviceEffect()
    data object SnapshotFlash : DeviceEffect()
    data class HomePositionReturn(val targetPose: PtzPose) : DeviceEffect()
    /** 预置位调用 — 跟看守位 [HomePositionReturn] 区分,UI 同时高亮 chip */
    data class PresetRecall(val index: Int, val targetPose: PtzPose) : DeviceEffect()
    /** GB-2022 §9.3.4 PTZPreciseCtrl 触发的精确角度跳转 */
    data class PrecisePoseGoto(val targetPose: PtzPose) : DeviceEffect()
    data class ConfigChanged(val changedFields: List<String>) : DeviceEffect()
    /** GB-2022 §9.3.4 DeviceUpgrade — UI snackbar 提示,不真 OTA */
    data class DeviceUpgradeRequested(val firmware: String) : DeviceEffect()
    /** GB-2022 §9.3.4 FormatSDCard — UI snackbar 提示,不真格式化 */
    data class FormatSDCardRequested(val cardIndex: Int) : DeviceEffect()
}
