package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.DeviceControlActions
import com.uvp.sim.domain.DeviceControlAck
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.TargetTrackMode
import com.uvp.sim.domain.TargetTrackState
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.domain.withDeviceConfig
import com.uvp.sim.domain.withVideoParamConfig
import com.uvp.sim.gb28181.AlarmReportConfig
import com.uvp.sim.gb28181.BasicParamConfig
import com.uvp.sim.gb28181.ConfigParse
import com.uvp.sim.gb28181.DeviceConfigBlock
import com.uvp.sim.gb28181.DeviceConfigState
import com.uvp.sim.gb28181.FrameMirrorConfig
import com.uvp.sim.gb28181.FrontOsdConfig
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PictureMaskConfig
import com.uvp.sim.gb28181.SnapShotConfigParser
import com.uvp.sim.gb28181.SnapShotReport
import com.uvp.sim.gb28181.TargetArea
import com.uvp.sim.gb28181.VideoAlarmRecordConfig
import com.uvp.sim.gb28181.VideoParamAttribute
import com.uvp.sim.gb28181.VideoRecordPlanConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 系统级动作 handler — reboot / IDR / snapshot(7.4 旧 + 7.5 新)/ 升级 / 格式化 SD / 目标跟踪 /
 * 录像开关 / 布防 / 报警复位 / 在线参数下发。
 *
 * 所有真正的副作用(reboot / snapshot / requestKeyFrame / startUpgrade / triggerSnapshotConfig)
 * 通过 [DeviceControlActions] 注入,handler 不直连 Engine,便于单测注入 fake。
 */
interface SystemHandler {
    fun handleIFrame(xml: String)
    fun handleTeleBoot(xml: String)
    fun handleRecord(xml: String)
    fun handleGuard(xml: String)
    fun handleAlarm(xml: String, fromUri: String?): DeviceControlAck
    fun handleDeviceConfig(xml: String)
    /**
     * A.2.1.13 `VideoParamAttribute` —— 2022 新增的配置类型,与 `<BasicParam>` **同属
     * `DeviceConfig` 这个 CmdType**,只是块名不同。所以分发必须按**块名**判,不能按 CmdType。
     */
    fun handleVideoParamAttribute(xml: String)
    /** A.2.1.17 `PictureMask` 视频画面遮挡（2022 新增）。 */
    fun handlePictureMask(xml: String)
    /** A.2.1.12 `OSDConfig` 前端 OSD 配置（2022 新增）。 */
    fun handleOsdConfig(xml: String)
    /** A.2.1.23 `FrameMirror` 画面翻转（2022 新增，**simpleType**）。 */
    fun handleFrameMirror(xml: String)
    /** A.2.1.18 `AlarmReport` 报警上报开关（2022 新增）。 */
    fun handleAlarmReport(xml: String)
    /** A.2.1.15 `VideoRecordPlan` 录像计划（2022 新增）。 */
    fun handleVideoRecordPlan(xml: String)
    /** A.2.1.16 `VideoAlarmRecord` 报警录像（2022 新增）。 */
    fun handleVideoAlarmRecord(xml: String)
    fun handleDeviceUpgrade(xml: String)
    fun handleFormatSDCard(xml: String)
    fun handleTargetTrack(xml: String)
    fun handleSnapshot(xml: String)
    fun handleSnapShotConfig(xml: String)
}

internal class DefaultSystemHandler(
    private val state: MutableStateFlow<DeviceControlModel>,
    private val actions: DeviceControlActions,
    private val scope: CoroutineScope?,
) : SystemHandler {

    override fun handleIFrame(xml: String) {
        val (field, v) = ManscdpParser.tagValue(xml, "IFrameCmd")?.let { "IFrameCmd" to it }
            ?: ManscdpParser.tagValue(xml, "IFameCmd")?.let { "IFameCmd" to it }
            ?: return
        if (!v.equals("Send", ignoreCase = true)) return
        actions.requestKeyFrame()
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.IFrameFlash,
                lastCommand = LastDeviceCommand(field, v, nowMs())
            )
        }
    }

    override fun handleTeleBoot(xml: String) {
        state.update {
            it.copy(
                isRebooting = true,
                pendingEffect = DeviceEffect.Reboot,
                lastCommand = LastDeviceCommand("TeleBoot", "Boot", nowMs())
            )
        }
        scope?.launch { actions.reboot() }
    }

    override fun handleRecord(xml: String) {
        val v = ManscdpParser.tagValue(xml, "RecordCmd") ?: return
        val on = v.equals("Record", ignoreCase = true)
        state.update {
            it.copy(
                isRecording = on,
                lastCommand = LastDeviceCommand("RecordCmd", v, nowMs())
            )
        }
    }

    override fun handleGuard(xml: String) {
        val v = ManscdpParser.tagValue(xml, "GuardCmd") ?: return
        val on = v.equals("SetGuard", ignoreCase = true)
        state.update {
            it.copy(
                isGuarded = on,
                lastCommand = LastDeviceCommand("GuardCmd", v, nowMs())
            )
        }
    }

    /**
     * GB §9.3.4 AlarmCmd 反向控制。取值兼容两种平台风格:
     *   - 数值 0(复位) / 1(布防) / 2(撤防)
     *   - 字符串 "ResetAlarm"(部分平台用文字)
     *
     * 0 / 2 / ResetAlarm → 复位(isAlarming=false + alarmReset ack)
     * 1                  → 布防,仅记录,不切 isAlarming(布防归 GuardCmd,spec 非目标)
     * 其他未知值         → 不切 isAlarming,仍回 200(避免平台重试)
     *
     * ⭐ 2026-09-19 补：解析 `Info{AlarmMethod, AlarmType}`（**两版都写**，
     * 2022 A.2.3.1.6 / 2016 A.2.3 a)）。标准原话「报警复位控制时，扩展此项，
     * 携带报警方式、报警类型」—— 平台可以说"只复位视频报警"而不是全部。
     * 原先这两个字段完全不解析，信息被丢弃。
     *
     * ⚠️ **口径边界（刻意保守）**：本模拟器只有一路报警状态（`isAlarming` 布尔），
     * **做不到按类型分别复位**，所以解析后只**记账并留痕**（日志 + `lastCommand` 详情），
     * 行为仍是"复位全部"。⛔ 不要为了让日志好看而假装按范围复位了 ——
     * 真做分类型复位需要把报警状态做成按 (method, type) 分片的集合，那是另一条链。
     */
    override fun handleAlarm(xml: String, fromUri: String?): DeviceControlAck {
        val raw = ManscdpParser.tagValue(xml, "AlarmCmd")
            ?: return DeviceControlAck(needSipResponse = true)
        // ⛔ 按**字符串**读，不归一成 Int：AlarmType 的取值域随 AlarmMethod 变化
        //   （方式=2 与方式=5 是两张完全不同的表），归一后设备侧就分不清是哪一张了。
        val alarmMethod = ManscdpParser.tagValue(xml, "AlarmMethod")?.trim()?.takeIf { it.isNotEmpty() }
        val alarmType = ManscdpParser.tagValue(xml, "AlarmType")?.trim()?.takeIf { it.isNotEmpty() }
        val scopeLabel = buildString {
            append(raw)
            alarmMethod?.let { append(" method=").append(it) }
            alarmType?.let { append(" type=").append(it) }
        }
        if (alarmMethod != null || alarmType != null) {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台 AlarmCmd 指定复位范围: method=${alarmMethod ?: "(未指定)"} " +
                    "type=${alarmType ?: "(未指定)"}; 本机只有一路报警状态,按全部复位执行",
            )
        }
        val numeric = raw.toIntOrNull()
        val isReset = numeric == 0 || numeric == 2 || raw.equals("ResetAlarm", ignoreCase = true)
        return if (isReset) {
            state.update {
                it.copy(
                    isAlarming = false,
                    lastCommand = LastDeviceCommand("AlarmCmd", scopeLabel, nowMs())
                )
            }
            DeviceControlAck(needSipResponse = true, alarmReset = true, by = fromUri)
        } else {
            // 布防(1)或未知值:记录命令但不切 isAlarming
            state.update {
                it.copy(lastCommand = LastDeviceCommand("AlarmCmd", scopeLabel, nowMs()))
            }
            DeviceControlAck(needSipResponse = true, alarmReset = false, by = fromUri)
        }
    }

    /**
     * A.2.1.19 `BasicParam`（A.2.3.2.2 基本参数配置命令）—— 现在**落盘**了。
     *
     * 原先只写 `lastCommand`、不落盘，于是平台配完再回读拿到的是 `SimConfig` 里的旧值 ——
     * 面板上表现为「刚配好就自己变回去了」，而两侧日志都正常。
     *
     * ⚠️ 口径边界：只记账，心跳节拍 / 注册过期仍按本机 `SimConfig` 跑。见
     * [com.uvp.sim.gb28181.BasicParamState] 的类注释。
     */
    override fun handleDeviceConfig(xml: String) = handleConfigBlock(
        typeLabel = DeviceConfigBlock.BasicParam.configType,
        parsed = BasicParamConfig.parse(xml),
        describe = { param ->
            "名称=${param.name ?: "(未下发)"} 过期=${param.expiration ?: "(未下发)"} " +
                "心跳间隔=${param.heartBeatInterval ?: "(未下发)"} " +
                "超时次数=${param.heartBeatCount ?: "(未下发)"}"
        },
        write = { param -> copy(basicParam = param) },
    )

    override fun handlePictureMask(xml: String) = handleConfigBlock(
        typeLabel = DeviceConfigBlock.PictureMask.configType,
        parsed = PictureMaskConfig.parse(xml),
        describe = { mask ->
            "On=${mask.on} 区域=${mask.sumNum} 个" +
                if (mask.regions.isEmpty()) "" else
                    " " + mask.regions.joinToString(" ") { "S${it.seq}(${it.displayLabel})" }
        },
        write = { mask -> copy(pictureMask = mask) },
    )

    override fun handleOsdConfig(xml: String) = handleConfigBlock(
        typeLabel = DeviceConfigBlock.OsdConfig.configType,
        parsed = FrontOsdConfig.parse(xml),
        describe = { osd ->
            "窗口=${osd.length}×${osd.width} 时间(开=${osd.timeEnable} 位置=${osd.timeX},${osd.timeY}) " +
                "文字(开=${osd.textEnable} 共=${osd.sumNum} 条)"
        },
        write = { osd -> copy(frontOsd = osd) },
    )

    override fun handleFrameMirror(xml: String) = handleConfigBlock(
        typeLabel = DeviceConfigBlock.FrameMirror.configType,
        parsed = FrameMirrorConfig.parse(xml),
        describe = { mirror -> "${mirror.value}(${mirror.displayLabel})" },
        write = { mirror -> copy(frameMirror = mirror) },
    )

    override fun handleAlarmReport(xml: String) = handleConfigBlock(
        typeLabel = DeviceConfigBlock.AlarmReport.configType,
        parsed = AlarmReportConfig.parse(xml),
        describe = { report ->
            "移动侦测=${report.motionDetection} 区域入侵=${report.fieldDetection}"
        },
        write = { report -> copy(alarmReport = report) },
    )

    override fun handleVideoRecordPlan(xml: String) = handleConfigBlock(
        typeLabel = DeviceConfigBlock.VideoRecordPlan.configType,
        parsed = VideoRecordPlanConfig.parse(xml),
        describe = { plan ->
            "启用=${plan.recordEnable} 码流=${plan.streamNumber} 计划=${plan.scheduleSumNum} 天" +
                if (plan.schedules.isEmpty()) "" else
                    " " + plan.schedules.joinToString(" ") { day ->
                        "周${day.weekDayNum}[${day.segments.joinToString(",") { it.displayLabel }}]"
                    }
        },
        write = { plan -> copy(videoRecordPlan = plan) },
    )

    override fun handleVideoAlarmRecord(xml: String) = handleConfigBlock(
        typeLabel = DeviceConfigBlock.VideoAlarmRecord.configType,
        parsed = VideoAlarmRecordConfig.parse(xml),
        describe = { record ->
            "启用=${record.recordEnable} 码流=${record.streamNumber} " +
                "延时=${record.recordTime?.toString() ?: "(未下发)"}s " +
                "预录=${record.preRecordTime?.toString() ?: "(未下发)"}s"
        },
        write = { record -> copy(videoAlarmRecord = record) },
    )

    /**
     * 设备配置族（A.2.3.2）一次下发的**统一落库骨架** —— 6 个类型共用。
     *
     * 为什么要收口（而不是每个 handler 各写一遍 `when`）：
     *  1. 三态处理（缺块 / 拒收 / 收下）必须**处处一致**。散写时最容易漏的是"拒收也要
     *     打 warn" —— 漏掉之后平台发了一条非法报文，设备侧一声不响，现场完全无从下手。
     *  2. 落库、`pendingEffect`、`lastCommand` 必须**同一次 `update`** 写完，
     *     否则 UI 会读到"配置变了但 HUD 还显示上一条命令"的中间态。
     *  3. 日志必须**插真值**（"设备收到了什么"要能回答）—— 由 `describe` 保证，
     *     而不是各 handler 自己拼字符串时忘掉（本仓为"写死的括号值"踩过一次）。
     */
    private fun <S> handleConfigBlock(
        typeLabel: String,
        parsed: ConfigParse<S>,
        describe: (S) -> String,
        write: DeviceConfigState.(S) -> DeviceConfigState,
    ) {
        when (parsed) {
            // 块缺席 = 平台这一次没配这一项。正常路径，**不打日志** ——
            // 否则一次 DeviceConfig 里没带的类型都会刷一条，日志反而看不出重点。
            ConfigParse.Absent -> Unit

            is ConfigParse.Rejected -> SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "平台下发 DeviceConfig $typeLabel → 拒收：${parsed.reason}（设备保持原配置）"
            )

            is ConfigParse.Accepted -> {
                val content = parsed.state
                val detail = describe(content)
                state.update { model ->
                    model.withDeviceConfig { it.write(content) }.copy(
                        pendingEffect = DeviceEffect.ConfigChanged(listOf(typeLabel)),
                        lastCommand = LastDeviceCommand("DeviceConfig", "$typeLabel $detail", nowMs()),
                    )
                }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Network,
                    "平台下发 DeviceConfig $typeLabel → 已记 $detail"
                )
            }
        }
    }

    /**
     * A.2.1.13 `VideoParamAttribute`(2022 新增的配置类型)—— 平台下发视频参数。
     *
     * ⛔ **不按国标版本拦**:`VideoParamAttribute` 是 2022 才有的类型,但被误登记成 2016 的
     * 真 2022 设备必须还有一次"试一下"的机会 —— 真相由**回读对账**暴露,不由档位断言。
     * 版本门禁只关**设备主动声明**那一半（见 `ConfigDownloadResponse.build` 的对应分支）。
     *
     * ⛔ 解析不出任何 `Item` 时**不动 `videoParams`**,只记 `lastCommand`:空配置的语义
     * (清空 vs 平台没写这段)在设备侧无法区分,理由见
     * [com.uvp.sim.domain.withVideoParamConfig]。
     */
    override fun handleVideoParamAttribute(xml: String) {
        val items = VideoParamAttribute.parseItems(xml)
        val now = nowMs()
        state.update { model ->
            if (items.isEmpty()) {
                model.copy(
                    lastCommand = LastDeviceCommand(
                        "DeviceConfig", "VideoParamAttribute (空配置,未改动)", now
                    )
                )
            } else {
                model.withVideoParamConfig(items).copy(
                    pendingEffect = DeviceEffect.ConfigChanged(listOf("VideoParamAttribute")),
                    lastCommand = LastDeviceCommand(
                        "DeviceConfig",
                        "VideoParamAttribute " + items.joinToString("/") { it.streamNumber.toString() },
                        now
                    )
                )
            }
        }
        // ⭐ 平台下发的配置必须留一条**带真值**的日志:现场排障时要能回答
        //    「设备到底收到了什么」——只记"收到了"等于没记(本仓为这类日志踩过一次)。
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            if (items.isEmpty()) {
                "平台下发 DeviceConfig VideoParamAttribute → 空配置,未改动"
            } else {
                "平台下发 DeviceConfig VideoParamAttribute → 已记 ${items.size} 路码流 " +
                    items.joinToString(" ") { item ->
                        "[${item.streamNumber} 格式=${item.videoFormat} 分辨率=${item.resolution} " +
                            "帧率=${item.frameRate} 码率类型=${item.bitRateType} " +
                            "码率=${item.videoBitRate ?: "(未提供)"}]"
                    }
            }
        )
    }

    /**
     * A.2.3.1.12 DeviceUpgrade — 在线升级。
     *
     * ⛔ 2026-09-19 改正 KDoc（原文写「模拟 5s 假进度并**推 NOTIFY 给平台**」，那是错的）：
     * 本函数**不发任何报文**。它只把进度写进本机状态并回调 [actions]；
     * 对外唯一的报文是整条流程结束后 `ManscdpRouterImpl` 发的**一条** `DeviceUpgradeResult`
     * （§9.13.1 a)，见 [com.uvp.sim.sip.DeviceUpgradeResultNotify]）。
     */
    override fun handleDeviceUpgrade(xml: String) {
        val firmware = ManscdpParser.tagValue(xml, "Firmware") ?: "(unknown)"
        val sessionId = ManscdpParser.tagValue(xml, "SessionID") ?: "auto-${nowMs()}"
        val fileUrl = ManscdpParser.tagValue(xml, "FileURL") ?: ""
        state.update {
            it.copy(
                upgradeProgress = UpgradeProgress(
                    sessionId = sessionId,
                    firmware = firmware,
                    percent = 0,
                    result = UpgradeResult.InProgress,
                ),
                pendingEffect = DeviceEffect.DeviceUpgradeRequested(firmware),
                lastCommand = LastDeviceCommand("DeviceUpgrade", firmware, nowMs())
            )
        }
        actions.startUpgrade(sessionId, firmware, fileUrl)
    }

    /** A.2.3.1.13 存储卡格式化控制命令 —— 平台点「格式化」时下发。
     *
     * ⛔⛔ **2026-09-20 按标准改正两处语义**(此前两处都是错的):
     *
     * **① 元素值本身就是卡号,标准里没有 `DiskNum`。**
     * A.2.3.1.13 只有这一个元素:
     * ```
     * <FormatSDCard>N</FormatSDCard>   SD 卡编号,从1开始编号。该值 0 时,对所有存储卡进行格式化
     * ```
     * 类型是 `simpleType / restriction base="integer"` + `<minInclusive value="0"/>`。
     * 这里原先写的是「优先读 `<DiskNum>`(真实卡号),fallback 读 `<FormatSDCard>` 的整数」——
     * 而 **`DiskNum` 在 2022 全文 / 2022 附录 A / 2016 附录 A 三处都是 0 命中**,是自造元素名。
     * 之前"看起来能跑对",只是因为平台发的就是 `<FormatSDCard>N</FormatSDCard>`,`DiskNum`
     * 恒解析成 null 后由 fallback 命中 —— 但优先级是反的:真有客户端带上 `DiskNum` 时卡号会取错。
     *
     * **② 解析失败一律不下发,决不回落成 0。**
     * `0` 在本命令里的语义是**格式化全部卡**(标准注释原文)。"读不懂 → 用 0 兜底"等于
     * **把一条读不懂的报文升级成破坏性最大的一种操作**。现在解析不出整数只记 Warning 返回,
     * `lastCommand` 仍照记(设备确实收到了这条命令)。
     *
     * ⛔ 这是**无应答命令**(§9.3.1 d:设备不回 Response)。设备侧做没做、平台怎么确认,
     *    只能靠平台事后再查一次 `SDCardStatus` —— 所以本函数把执行落到
     *    [DeviceControlActions.formatStorageCard](真的改设备侧读数),而不是只更新 UI 提示。
     */
    override fun handleFormatSDCard(xml: String) {
        val raw = ManscdpParser.tagValue(xml, "FormatSDCard")
        val card = raw?.trim()?.toIntOrNull()
        if (card == null) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "FormatSDCard 未携带可解析的卡号(原始值=${raw ?: "(缺失)"}),按不下发处理 —— " +
                    "⛔ 不回落成 0:0 的语义是「格式化全部卡」,读不懂的报文不该触发最重的破坏性操作"
            )
            state.update {
                it.copy(
                    lastCommand = LastDeviceCommand(
                        "FormatSDCard", "ignored(raw=${raw ?: "-"})", nowMs()
                    )
                )
            }
            return
        }
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.FormatSDCardRequested(card),
                lastCommand = LastDeviceCommand("FormatSDCard", "card $card", nowMs())
            )
        }
        actions.formatStorageCard(card)
    }

    /**
     * A.2.3.1.14 目标跟踪控制命令 — 鱼眼/全景球机专用,sim 白名单识别 mode 不做业务。
     *
     * ⛔ **2026-09-19 按标准改正字段集**（原先解析的是标准里不存在的元素）:
     *
     * 标准（A.2.3.1.14，标准页 77-78）只有三样东西：
     * ```
     * <TargetTrack> Auto | Manual | Stop </TargetTrack>   目标跟踪命令（可选）
     * <DeviceID2>…</DeviceID2>                            全景相机中的全景通道ID（可选）
     * <TargetArea>…</TargetArea>                          全景图片大小 + 框选区域（手动跟踪时需要）
     * ```
     * 原先解析 `<ObjectID>` + `<Speed>` —— 这两个名字在 2022 全书与 2016 附录 A
     * **都是 0 命中**（`ObjectID` 实为把 `TargetTrack` 的取值 `Auto/Manual/Stop`
     * 误读成了"对象标识"）。于是平台按标准下发的**框选坐标全部被丢弃**。
     *
     * mode 不在白名单 → warn 不写 lastCommand(平台仍收 200 由外层路由保证)。
     *
     * ⭐ **2026-09-21 起同时写"设备屏幕可见的跟踪态"**（[DeviceControlModel.targetTrack]）。
     * 在此之前本函数只写一条 `lastCommand` —— 而 `lastCommand` 的语义是"最近一条命令"
     * （UI 只认 3 秒内），于是平台点下「手动跟踪」之后：平台**收不到回执**（9.3.1 d)
     * 把它列为无应答命令，表 1 序号 13 应答栏为"（无）"）、附录 A 又**没有**任何查询命令
     * 能把跟踪态读回去 ⇒ 这条命令在设备侧**查无实据**，屏幕上什么都不亮。
     * 现在落一份持续态，`Stop` 置 `null`（"没有停止中的跟踪态"）。
     *
     * ⛔ 白名单校验刻意**区分大小写**（与标准给的 `Auto`/`Manual`/`Stop` 原样比对），
     * 所以下面的模式映射也按这三个字面量走，不再 lower-case —— 两处口径必须一致，
     * 否则会出现"过了白名单却映射不出模式"的空档。
     */
    override fun handleTargetTrack(xml: String) {
        val mode = ManscdpParser.tagValue(xml, "Mode")
            ?: ManscdpParser.tagValue(xml, "TargetTrack")
            ?: "Auto"
        if (mode !in setOf("Auto", "Manual", "Stop")) {
            // 不更新 lastCommand,但外层 dispatch 已返回 DeviceControlAck → 仍回 200 OK
            return
        }
        val deviceId2 = ManscdpParser.tagValue(xml, "DeviceID2")?.takeIf { it.isNotBlank() }
        val area = TargetArea.parse(xml)
        if (mode.equals("Manual", ignoreCase = true) && area == null) {
            // 标准说手动跟踪时 TargetArea 需要。缺了要**留痕** —— 否则"平台框选没生效"
            // 在设备侧完全不可观测（现象只有"球机没动"，而设备也没记下收到过框选）。
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "目标跟踪 mode=Manual 但未携带 <TargetArea> 框选坐标（A.2.3.1.14）",
            )
        }
        val detail = buildString {
            append("mode=").append(mode)
            deviceId2?.let { append(" pano=").append(it) }
            area?.let { append(" area=").append(it.describe()) }
        }
        // `Stop` ⇒ null；`Auto` / `Manual` ⇒ 一份新的跟踪态。`nowMs()` 只取一次：
        // 跟踪态与命令留痕是**同一个事实**的两个面，分别取时会让 `startedAtMs` 与
        // `lastCommand.timestampMs` 差出几毫秒，UI 上"已跟踪时长"与"刚收到命令"的
        // 计时起点对不上。
        val atMs = nowMs()
        val trackState = when (mode) {
            "Auto" -> TargetTrackState.of(TargetTrackMode.Auto, area, deviceId2, atMs)
            "Manual" -> TargetTrackState.of(TargetTrackMode.Manual, area, deviceId2, atMs)
            else -> null
        }
        state.update {
            it.copy(
                targetTrack = trackState,
                lastCommand = LastDeviceCommand("TargetTrack", detail, atMs),
            )
        }
    }

    /** SnapShotCmd 7.4 旧路径:engine 端走 reportSnapshot 流程. */
    override fun handleSnapshot(xml: String) {
        val v = ManscdpParser.tagValue(xml, "SnapShotCmd") ?: return
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.SnapshotFlash,
                lastCommand = LastDeviceCommand("SnapShotCmd", v, nowMs())
            )
        }
        scope?.launch { actions.snapshot() }
    }

    /**
     * SnapShotConfig (GB-2022 §9.5 7.5 新路径) — 解析平台下发的图像抓拍配置,
     * 委托 SnapshotUploadEngine 异步执行序列。解析失败仍回 200 OK(不让平台重试),
     * 但不触发 actions。
     *
     * ⭐ 2026-09-19 起**同时落盘**（`deviceConfigs.snapShot`），于是 A.2.6.9 的回读
     * 有东西可回（回读元素名是 `SnapShot`，见 [SnapShotReport]）。落的是
     * **执行侧同一份值** —— 设备报的就是"我实际会按什么配置抓拍"。
     */
    override fun handleSnapShotConfig(xml: String) {
        val cfg = SnapShotConfigParser.parse(xml)
        if (cfg == null) {
            // ⛔ 解析失败**要留痕**。原先这里静默 return，于是"平台下发了但我们没执行"
            //    在设备侧完全不可观测 —— 平台只会看到抓拍没发生，而排障没有落脚点。
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "平台下发 SnapShotConfig → 拒收（缺 SessionID / UploadURL 非法），未执行抓拍"
            )
        }
        if (cfg == null) return
        state.update {
            it.withDeviceConfig { configs ->
                configs.copy(snapShot = SnapShotReport.snapshotOf(cfg))
            }.copy(
                pendingEffect = DeviceEffect.SnapshotFlash,
                lastCommand = LastDeviceCommand("SnapShotConfig", cfg.sessionId, nowMs())
            )
        }
        scope?.launch { actions.triggerSnapshotConfig(cfg) }
    }
}
