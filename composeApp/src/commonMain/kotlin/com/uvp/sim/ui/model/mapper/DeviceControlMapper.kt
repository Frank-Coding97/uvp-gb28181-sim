package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.PanDirection
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

/** PR-A T3.2 实现. */
fun DeviceControlState.toDto(): DeviceControlDto = TODO("PR-A T3.2")

fun PtzPose.toDto(): PtzPoseDto = TODO("PR-A T3.2")

fun DragZoomRect.toDto(): DragZoomRectDto = TODO("PR-A T3.2")

fun LastDeviceCommand.toDto(): LastDeviceCommandDto = TODO("PR-A T3.2")

fun PtzCommand.toDto(): PtzCommandDto = TODO("PR-A T3.2")

fun UpgradeProgress.toDto(): UpgradeProgressDto = TODO("PR-A T3.2")

fun UpgradeResult.toDto(): UpgradeResultDto = TODO("PR-A T3.2")

fun PanDirection.toDto(): PanDirectionDto = TODO("PR-A T3.2")
fun TiltDirection.toDto(): TiltDirectionDto = TODO("PR-A T3.2")
fun ZoomDirection.toDto(): ZoomDirectionDto = TODO("PR-A T3.2")
fun FocusDirection.toDto(): FocusDirectionDto = TODO("PR-A T3.2")
fun IrisDirection.toDto(): IrisDirectionDto = TODO("PR-A T3.2")
