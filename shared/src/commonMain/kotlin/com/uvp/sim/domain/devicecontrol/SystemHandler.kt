package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.DeviceControlActions
import com.uvp.sim.domain.DeviceControlAck
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.SnapShotConfigParser
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
    fun handleDeviceUpgrade(xml: String)
    fun handleFormatSDCard(xml: String)
    fun handleTargetTrack(xml: String)
    fun handleSnapshot(xml: String)
    fun handleSnapShotConfig(xml: String)
}

internal class DefaultSystemHandler(
    private val state: MutableStateFlow<DeviceControlState>,
    private val actions: DeviceControlActions,
    private val scope: CoroutineScope?,
) : SystemHandler {

    override fun handleIFrame(xml: String) {
        val v = ManscdpParser.tagValue(xml, "IFameCmd") ?: return
        if (!v.equals("Send", ignoreCase = true)) return
        actions.requestKeyFrame()
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.IFrameFlash,
                lastCommand = LastDeviceCommand("IFameCmd", v, nowMs())
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
     */
    override fun handleAlarm(xml: String, fromUri: String?): DeviceControlAck {
        val raw = ManscdpParser.tagValue(xml, "AlarmCmd")
            ?: return DeviceControlAck(needSipResponse = true)
        val numeric = raw.toIntOrNull()
        val isReset = numeric == 0 || numeric == 2 || raw.equals("ResetAlarm", ignoreCase = true)
        return if (isReset) {
            state.update {
                it.copy(
                    isAlarming = false,
                    lastCommand = LastDeviceCommand("AlarmCmd", raw, nowMs())
                )
            }
            DeviceControlAck(needSipResponse = true, alarmReset = true, by = fromUri)
        } else {
            // 布防(1)或未知值:记录命令但不切 isAlarming
            state.update {
                it.copy(lastCommand = LastDeviceCommand("AlarmCmd", raw, nowMs()))
            }
            DeviceControlAck(needSipResponse = true, alarmReset = false, by = fromUri)
        }
    }

    override fun handleDeviceConfig(xml: String) {
        val changed = mutableListOf<String>()
        ManscdpParser.tagValue(xml, "Name")?.let { changed += "Name" }
        ManscdpParser.tagValue(xml, "HeartBeatInterval")?.let { changed += "HeartBeatInterval" }
        ManscdpParser.tagValue(xml, "HeartBeatCount")?.let { changed += "HeartBeatCount" }
        ManscdpParser.tagValue(xml, "Expiration")?.let { changed += "Expiration" }
        if (changed.isEmpty()) return
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.ConfigChanged(changed),
                lastCommand = LastDeviceCommand("DeviceConfig", changed.joinToString(","), nowMs())
            )
        }
    }

    /** A.2.3.1.12 DeviceUpgrade — 在线升级,模拟 5s 假进度并推 NOTIFY 给平台. */
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

    /** A.2.3.1.13 FormatSDCard — 格式化 SD 卡(选做最小集). 手机无 SD 卡概念,只为协议合规.
     *
     *  M5 batch3 §4.13 字段补全:GB-2022 标准是 `<FormatSDCard>1</FormatSDCard><DiskNum>N</DiskNum>`,
     *  优先读 `<DiskNum>`(真实卡号),fallback 读 `<FormatSDCard>` 的整数(老格式兼容)。
     */
    override fun handleFormatSDCard(xml: String) {
        val diskNum = ManscdpParser.tagValue(xml, "DiskNum")?.toIntOrNull()
        val card = diskNum ?: ManscdpParser.tagValue(xml, "FormatSDCard")?.toIntOrNull() ?: 0
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.FormatSDCardRequested(card),
                lastCommand = LastDeviceCommand("FormatSDCard", "card $card", nowMs())
            )
        }
    }

    /** A.2.3.1.14 TargetTrack — 目标跟踪. 鱼眼/全景球机专用,sim 白名单识别 mode 不做业务.
     *
     *  M5 batch3 §4.14 字段补全:GB-2022 标准是 `<Mode>Auto|Manual|Stop</Mode>` + 可选
     *  `<ObjectID>...</ObjectID>` + `<Speed>1-255</Speed>`。老格式兼容 `<TargetTrack>Auto</TargetTrack>`。
     *  mode 不在白名单 → warn 不写 lastCommand(平台仍收 200 由外层路由保证)。
     */
    override fun handleTargetTrack(xml: String) {
        val mode = ManscdpParser.tagValue(xml, "Mode")
            ?: ManscdpParser.tagValue(xml, "TargetTrack")
            ?: "Auto"
        if (mode !in setOf("Auto", "Manual", "Stop")) {
            // 不更新 lastCommand,但外层 dispatch 已返回 DeviceControlAck → 仍回 200 OK
            return
        }
        val objectId = ManscdpParser.tagValue(xml, "ObjectID")
        val speed = ManscdpParser.tagValue(xml, "Speed")?.toIntOrNull()
        val detail = buildString {
            append("mode=").append(mode)
            objectId?.let { append(" obj=").append(it) }
            speed?.let { append(" speed=").append(it) }
        }
        state.update {
            it.copy(lastCommand = LastDeviceCommand("TargetTrack", detail, nowMs()))
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
     */
    override fun handleSnapShotConfig(xml: String) {
        val cfg = SnapShotConfigParser.parse(xml) ?: return
        state.update {
            it.copy(
                pendingEffect = DeviceEffect.SnapshotFlash,
                lastCommand = LastDeviceCommand("SnapShotConfig", cfg.sessionId, nowMs())
            )
        }
        scope?.launch { actions.triggerSnapshotConfig(cfg) }
    }
}
