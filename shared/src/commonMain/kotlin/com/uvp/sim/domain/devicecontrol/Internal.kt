package com.uvp.sim.domain.devicecontrol

import kotlin.time.Clock

/** 共享时间戳工具,handler 内部用。提取自原 DeviceControlDispatcher.nowMs(). */
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
