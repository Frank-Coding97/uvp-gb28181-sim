package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.CruiseTrackState
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.ScanGroupState
import com.uvp.sim.gb28181.CruiseOp
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCmdDecoder
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.PtzInstruction
import com.uvp.sim.gb28181.PtzPreciseCtrlParser
import com.uvp.sim.gb28181.ScanOp
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import com.uvp.sim.gb28181.configBlockBody
import com.uvp.sim.gb28181.isFullStop
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.osd.DragZoomBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * PTZ 家族命令 handler:8 方向运动 + zoom + focus + iris + 巡航 + 精确云台 + DragZoom。
 * 预置位 / Aux 单独由 [PresetHandler] / [AuxHandler] 处理。
 */
interface PtzHandler {
    /** PTZCmd 8 字节解码 → 速率写入 state(motion + cruise 子族;preset/aux 走 presetHandler/auxHandler)。 */
    fun handlePtz(xml: String, presetHandler: PresetHandler, auxHandler: AuxHandler)

    /** PTZPreciseCtrl(GB-2022 A.2.3.1.11) — 精确云台目标位姿。 */
    fun handlePtzPrecise(xml: String)

    /** DragZoomIn / DragZoomOut — 平台拉框聚焦,记录矩形。 */
    fun handleDragZoom(xml: String)
}

internal class DefaultPtzHandler(
    private val state: MutableStateFlow<DeviceControlModel>,
) : PtzHandler {

    override fun handlePtz(xml: String, presetHandler: PresetHandler, auxHandler: AuxHandler) {
        val hex = ManscdpParser.tagValue(xml, "PTZCmd") ?: return
        // ⭐ A.2.3.1.2 `PTZCmdParams`（**2022 新增**）：平台给预置位 / 巡航轨迹起的名字。
        //    标准原文：「预置位名称（PTZCmd 设置预置位命令时可选）」「巡航轨迹名称
        //    （最长32字节，PTZCmd 巡航指令命令时可选）」。⛔ 原先完全不解析 ⇒
        //    平台配的名字被静默丢弃，回读时设备只能回自己编的 `Preset N` / `巡航 N`，
        //    平台看到"自己配的名字没保存"。
        //    ⛔ 先取 `PTZCmdParams` **块**再在块内找：名字在报文别处不会出现，
        //    但按块解析才能挡住"名字出现在无关位置"的畸形报文。
        //    超长（>32 字节）不裁剪 —— 那是平台侧的问题，设备原样存、回读原样带回。
        val params = configBlockBody(xml, "PTZCmdParams")
        val presetName = params?.let { ManscdpParser.tagValue(it, "PresetName")?.trim() }
            ?.takeIf { it.isNotEmpty() }
        val cruiseTrackName = params?.let { ManscdpParser.tagValue(it, "CruiseTrackName")?.trim() }
            ?.takeIf { it.isNotEmpty() }
        when (val ins = PtzCmdDecoder.decodeInstruction(hex)) {
            is PtzInstruction.Motion -> handlePtzMotion(ins.cmd, hex)
            is PtzInstruction.Lens -> handlePtzLens(ins, hex)
            is PtzInstruction.Preset -> presetHandler.handlePtzPreset(ins, hex, presetName)
            is PtzInstruction.Aux -> auxHandler.handlePtzAux(ins, hex)
            is PtzInstruction.Cruise -> handlePtzCruise(ins, hex, cruiseTrackName)
            is PtzInstruction.Scan -> handlePtzScan(ins, hex)
            // 校验失败 / 标准未定义该字节4(null)→ 不猜语义,200 OK 由 ack 兜底
            null -> Unit
        }
    }

    /**
     * PTZ 族(表 A.5):只动云台三轴。
     *
     * ⚠️ 这里**不再**累计聚焦/光圈。本族字节4 的 bit7/bit6 恒为 0;原实现却在这里按
     * bit7/bit6 判 Focus Near/Far、又把字节6 的低 4 位当聚焦速度,于是 0x44/0x48
     * (光圈放大/缩小)落进来被拆成「聚焦远 + 下/上」—— 现象就是**平台一调光圈、
     * 云台跟着仰俯**。聚焦/光圈已归 FI 族,见 [handlePtzLens]。
     */
    private fun handlePtzMotion(ptz: PtzCommand, hex: String) {
        // ⛔ 全零帧 = 停止指令，而它**同时是巡航的停止码**：标准里没有第二条件"停巡航"的指令，
        //    平台点「停止巡航」发出来的就是这一帧（`PTZActionCruiseStop`，其余字段留零）。
        //    不在这里清 `activeCruiseTrack` 的话，云台会被这条帧停住、但设备仍然标着"运行中"，
        //    而且巡航执行协程下一拍会接着往下一个点转过去 —— 现场表现是**"停不掉"**。
        val stoppedCruise = ptz.isFullStop && state.value.activeCruiseTrack != null
        // 扫描与巡航共用同一条停止帧(标准里两者都没有专用停止码,见 `isFullStop`)。
        val stoppedScan = ptz.isFullStop && state.value.activeScanGroup != null
        state.update {
            it.copy(
                panSpeed = mapPanSpeed(ptz),
                tiltSpeed = mapTiltSpeed(ptz),
                zoomSpeed = mapZoomSpeed(ptz),
                activeCruiseTrack = if (stoppedCruise) null else it.activeCruiseTrack,
                activeScanGroup = if (stoppedScan) null else it.activeScanGroup,
                lastCommand = LastDeviceCommand("PTZCmd", hex, nowMs(), ptz)
            )
        }
        if (stoppedCruise) {
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "巡航停止：收到全零停止指令")
        }
        if (stoppedScan) {
            // 这条同时是对账线索:扫描是个**设备自主行为**,平台侧只能看到自己发过停止帧,
            // 没有这条日志就无从判断设备到底停没停(与巡航停止同一取舍)。
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "扫描停止：收到全零停止指令")
        }
    }

    /**
     * FI 族(GB/T 28181-2022 表 A.6):聚焦 / 光圈。
     *
     * 写进去的是**速率**而不是位置 —— 标准里 FI 命令与方向命令同形:带速度的"开始动作"
     * 指令,字节4 低 4 位清零(0x40)才停。所以设备侧存速度、位置由 UI 的积分节拍
     * (见 `PtzTabContent`)按「速率 × 时间」推出来;松手时平台补一条 0x40 → 速率归零 → 停。
     *
     * 两轴可以同时有效(表下注 1 允许组合下发,如 0x46 = 光圈放大 + 聚焦近),各自独立推进。
     * 速度字节也是各占一个:字节5 = 聚焦速度、字节6 = 光圈速度(不对称是标准本身如此)。
     *
     * **不动云台三轴**:FI 命令不涉及 pan/tilt/zoom,那三轴该按自己的速率继续走,
     * 由各自的停止指令负责收尾 —— 在 FI 里顺手把它们清零会造出标准没定义的行为。
     */
    private fun handlePtzLens(lens: PtzInstruction.Lens, hex: String) {
        state.update {
            it.copy(
                focusSpeed = when (lens.focus) {
                    FocusDirection.NEAR -> -lens.focusSpeed * LENS_RATE_PER_SPEED
                    FocusDirection.FAR -> lens.focusSpeed * LENS_RATE_PER_SPEED
                    FocusDirection.NONE -> 0f
                },
                irisSpeed = when (lens.iris) {
                    IrisDirection.OPEN -> lens.irisSpeed * LENS_RATE_PER_SPEED
                    IrisDirection.CLOSE -> -lens.irisSpeed * LENS_RATE_PER_SPEED
                    IrisDirection.NONE -> 0f
                },
                lastCommand = LastDeviceCommand("PTZCmd", hex, nowMs(), lens.toPtzCommand()),
            )
        }
    }

    /** 巡航 CRUD (GB-2022 附录 A.3.5,字节4(bytes[3])=0x84-0x88). */
    private fun handlePtzCruise(p: PtzInstruction.Cruise, hex: String, cruiseTrackName: String?) {
        val now = nowMs()
        when (p.op) {
            CruiseOp.SET_POINT -> {
                // 把预置位 p.param 加入巡航轨迹 p.trackNum
                state.update { s ->
                    val track = s.cruiseTracks[p.trackNum] ?: CruiseTrackState()
                    // 同一个预置位重复加入只保留一次,但**不重排**已有点位 ——
                    // 巡航顺序由加入的先后决定,去重不能把它打乱。
                    val withPoint = if (p.param in track.points) track.copy()
                        else track.copy(points = track.points + p.param)
                    // ⭐ A.2.3.1.2 `CruiseTrackName`（2022 新增）：平台给这条轨迹起的名字。
                    //    这次没给就**保持原值** —— 重复加点是常态，抹掉名字等于平台看到
                    //    "自己配的名字莫名丢了"。
                    val updated = if (cruiseTrackName != null) withPoint.copy(name = cruiseTrackName)
                        else withPoint
                    s.copy(
                        cruiseTracks = s.cruiseTracks + (p.trackNum to updated),
                        lastCommand = LastDeviceCommand(
                            "PTZCmd",
                            "巡航 #${p.trackNum} 添加 P${p.param}" +
                                if (cruiseTrackName != null) " name=$cruiseTrackName" else "",
                            now
                        )
                    )
                }
            }
            CruiseOp.DEL_POINT -> {
                state.update { s ->
                    val track = s.cruiseTracks[p.trackNum] ?: CruiseTrackState()
                    val updated = track.copy(points = track.points - p.param)
                    s.copy(
                        // 点位删空就整条轨迹消失;速度/停留时间跟着走,不留下"空壳轨迹"
                        cruiseTracks = if (updated.points.isEmpty()) s.cruiseTracks - p.trackNum
                            else s.cruiseTracks + (p.trackNum to updated),
                        lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 删除 P${p.param}", now)
                    )
                }
            }
            CruiseOp.SET_SPEED -> {
                // ⛔ 必须落库。原来这里只写 lastCommand,于是 §9.5.3 的查询应答只能回一个
                //    硬编码速度 —— 平台设完再查回来数字对不上,看起来就是"这功能坏了"。
                state.update { s ->
                    val track = s.cruiseTracks[p.trackNum] ?: CruiseTrackState()
                    s.copy(
                        cruiseTracks = s.cruiseTracks + (p.trackNum to track.copy(speed = p.param)),
                        lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 速度=${p.param}", now)
                    )
                }
            }
            CruiseOp.SET_DWELL_TIME -> {
                state.update { s ->
                    val track = s.cruiseTracks[p.trackNum] ?: CruiseTrackState()
                    s.copy(
                        cruiseTracks = s.cruiseTracks + (p.trackNum to track.copy(dwellTime = p.param)),
                        lastCommand = LastDeviceCommand("PTZCmd", "巡航 #${p.trackNum} 停留=${p.param}s", now)
                    )
                }
            }
            CruiseOp.START -> {
                state.update {
                    it.copy(
                        activeCruiseTrack = if (p.trackNum == 0) null else p.trackNum,
                        // ⛔ 与扫描互斥(反向的那一半在 handlePtzScan):一台设备的云台只有一套,
                        //    巡航和扫描同时驱动会互相抢 panSpeed / 姿态。开始巡航即停掉扫描。
                        activeScanGroup = null,
                        lastCommand = LastDeviceCommand(
                            "PTZCmd",
                            if (p.trackNum == 0) "巡航停止" else "巡航 #${p.trackNum} 启动",
                            now
                        )
                    )
                }
            }
        }
    }

    /**
     * 自动扫描 (GB/T 28181 表 A.10) —— 四个子动作。
     *
     * 4 个子动作里**只有"开始"会让球机动起来**,而且真正让它动的不是这里 —— 是
     * [com.uvp.sim.domain.SimulatorEngine] 的扫描节拍(设备自主行为,标准没有任何执行进度上报)。
     * 这里只落状态:设边界 = 把此刻姿态记下来(本身不动镜头)、设速度 = 存 12 位原值。
     *
     * ⛔ 设边界**必须用设备自己的 `panAngle`**:标准的设边界指令没有数值入参(表 A.10 序号 2/3
     * 的字节6 只是子动作码 01H/02H),设备只能快照自己当前朝向 —— 平台也没法从外面写一个角度进来。
     */
    private fun handlePtzScan(scan: PtzInstruction.Scan, hex: String) {
        val now = nowMs()
        when (scan.op) {
            ScanOp.SET_LEFT_BOUNDARY -> state.update { s ->
                val group = s.scanGroups[scan.groupNum] ?: ScanGroupState()
                val pose = PtzPose(s.panAngle, s.tiltAngle, s.zoomLevel)
                s.copy(
                    scanGroups = s.scanGroups + (scan.groupNum to group.copy(leftBoundary = pose)),
                    // 只记边界,不发 pendingEffect —— 设边界不该让镜头动一下(真机也不动)。
                    lastCommand = LastDeviceCommand(
                        "PTZCmd",
                        "扫描 #${scan.groupNum} 设左边界 pan=${pose.pan}",
                        now,
                    ),
                )
            }
            ScanOp.SET_RIGHT_BOUNDARY -> state.update { s ->
                val group = s.scanGroups[scan.groupNum] ?: ScanGroupState()
                val pose = PtzPose(s.panAngle, s.tiltAngle, s.zoomLevel)
                s.copy(
                    scanGroups = s.scanGroups + (scan.groupNum to group.copy(rightBoundary = pose)),
                    lastCommand = LastDeviceCommand(
                        "PTZCmd",
                        "扫描 #${scan.groupNum} 设右边界 pan=${pose.pan}",
                        now,
                    ),
                )
            }
            ScanOp.SET_SPEED -> state.update { s ->
                val group = s.scanGroups[scan.groupNum] ?: ScanGroupState()
                s.copy(
                    scanGroups = s.scanGroups + (scan.groupNum to group.copy(speed = scan.param)),
                    lastCommand = LastDeviceCommand("PTZCmd", "扫描 #${scan.groupNum} 速度=${scan.param}", now),
                )
            }
            ScanOp.START -> state.update { s ->
                s.copy(
                    // ⛔ 扫描与巡航互斥:云台只有一套机械。两个"设备自主行为"同时驱动会互相抢
                    //    panSpeed / 姿态(巡航每步 ease 到预置位、扫描逐帧积分水平速度),
                    //    画面变成抽搐。开始扫描即停掉巡航;反方向在 handlePtzCruise 的 START 里。
                    activeScanGroup = scan.groupNum,
                    activeCruiseTrack = null,
                    lastCommand = LastDeviceCommand("PTZCmd", "扫描 #${scan.groupNum} 启动", now),
                )
            }
        }
    }

    /** GB28181 速度字节 0-255 → ±90°/s 线性映射;方向 NONE → 0. */
    private fun mapPanSpeed(p: PtzCommand): Float = when (p.panDirection) {
        PanDirection.LEFT -> -p.panSpeed * 90f / 255f
        PanDirection.RIGHT -> p.panSpeed * 90f / 255f
        PanDirection.NONE -> 0f
    }

    private fun mapTiltSpeed(p: PtzCommand): Float = when (p.tiltDirection) {
        TiltDirection.UP -> p.tiltSpeed * 90f / 255f
        TiltDirection.DOWN -> -p.tiltSpeed * 90f / 255f
        TiltDirection.NONE -> 0f
    }

    /** Zoom 速度 0-15 → ±1x/s. */
    private fun mapZoomSpeed(p: PtzCommand): Float = when (p.zoomDirection) {
        ZoomDirection.IN -> p.zoomSpeed / 15f
        ZoomDirection.OUT -> -p.zoomSpeed / 15f
        ZoomDirection.NONE -> 0f
    }

    override fun handlePtzPrecise(xml: String) {
        val p = PtzPreciseCtrlParser.parse(xml) ?: return
        val target = PtzPose(p.pan, p.tilt, p.zoom)
        state.update {
            it.copy(
                lastPreciseCtrl = target,
                pendingEffect = DeviceEffect.PrecisePoseGoto(target),
                lastCommand = LastDeviceCommand(
                    "PTZPreciseCtrl",
                    "${p.pan},${p.tilt},${p.zoom}x",
                    nowMs()
                )
            )
        }
    }

    /**
     * 拉框放大 / 缩小（A.2.3.1.8 / A.2.3.1.9）。
     *
     * 标准原文（附录 A 的注，印刷页 75~76）：「拉框放大命令将播放窗口选定框内的图像放大到
     * 整个播放窗口；拉框缩小命令将整个播放窗口的图像缩小到播放窗口选定框内；
     * **命令中的坐标系以播放窗口的左上角原点，各坐标取值以像素单位**。」
     *
     * ⛔ 三个容易做错、且**都不会报错**的点（2026-09-20 修）：
     *  1. **六个字段整组收下**。`Length`/`Width` 是**播放窗口**的尺寸像素，即比例换算的两把尺子 ——
     *     只收 4 个框字段就永远算不出归一化坐标（原实现正是如此，于是画布上只能按一个错的口径画）。
     *  2. **必须先取 `<DragZoomIn>` / `<DragZoomOut>` 块**再在块内找标签：这 6 个元素名与
     *     `TargetArea`（A.2.3.1.14 目标跟踪）**一字不差完全相同**，整篇 `tagValue` 在两条命令
     *     并存时会静默串值（同 `TargetArea.kt` 记录的坑）。
     *  3. **累积**：新视窗从**当前视窗**推出来（见 `VideoDragZoomViewport`），不是"每次回原始画面重裁" ——
     *     后者会让平台连点两次放大的结果与用户框的区域系统性错开，而画面上完全看不出是错的。
     */
    override fun handleDragZoom(xml: String) {
        val zoomIn = xml.contains("<DragZoomIn>")
        val type = if (zoomIn) "DragZoomIn" else "DragZoomOut"
        val body = configBlockBody(xml, type) ?: return
        fun int(tag: String): Int? = ManscdpParser.tagValue(body, tag)?.toIntOrNull()
        val midX = int("MidPointX")
        val midY = int("MidPointY")
        val lengthX = int("LengthX")
        val lengthY = int("LengthY")
        val frameLength = int("Length")
        val frameWidth = int("Width")
        if (midX == null || midY == null || lengthX == null || lengthY == null ||
            frameLength == null || frameWidth == null
        ) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "DRAG_ZOOM_REJECTED $type 报文缺字段")
            return
        }
        val rect = DragZoomRect(midX, midY, lengthX, lengthY, frameLength, frameWidth)
        val box = DragZoomBox.of(rect)
        // 坐标不可用的两种情形（尺子非正 / 框退化或整块在画面外）都只留痕、**不动视窗**：
        // 拿一个退化框去裁一次画面会把画面锁死在一个角上，而标准里没有任何复位命令能退回来。
        val next = if (box == null) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "DRAG_ZOOM_REJECTED $type 坐标不可用(播放窗口尺寸非正或框退化)",
            )
            null
        } else {
            val current = state.value.dragZoomViewport
            val applied = if (zoomIn) current.zoomIn(box) else current.zoomOut(box)
            if (applied == null) {
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Media,
                    "DRAG_ZOOM_REJECTED $type 已达最小视窗",
                )
            }
            applied
        }
        // ⭐ **成功路径也必须留痕**：标准里没有任何回读手段（A.2.4 查询闭集 1~14 无此项、
        //    A.2.6 无应答、A.3 全是指令）⇒ 平台只能显示「已下发」，现场看到的画面对不对
        //    没法从平台侧反推。设备侧这条日志是**唯一**能确认"这次真裁了、裁到画面哪一块"的地方。
        //    ⛔ 别以为是冗余日志删掉：删了之后"平台点了放大但画面没变"就只剩肉眼一条路。
        next?.let {
            SystemLogger.emit(
                LogLevel.Info,
                LogTag.Media,
                "DRAG_ZOOM_APPLIED $type 视窗 x=${it.left} y=${it.top} w=${it.width} h=${it.height}",
            )
        }
        state.update {
            it.copy(
                dragZoomRect = rect,
                dragZoomViewport = next ?: it.dragZoomViewport,
                lastCommand = LastDeviceCommand(type, "($midX,$midY) ${lengthX}x$lengthY", nowMs()),
            )
        }
    }
}

/**
 * FI 速度(标准口径 00H~FFH)→ 归一化行程速率(每秒)。
 *
 * `255 × (0.5/255) = 0.5/s`,即满速推完一整条行程(0%~100%)约 2 秒。
 * 平台默认档位 6 换算过来是 153,约 3.3 秒走完 —— 跟方向盘满速转一圈同一量级,
 * 演示时既看得出在动、又不会一按就到头。
 */
private const val LENS_RATE_PER_SPEED = 0.5f / 255f
