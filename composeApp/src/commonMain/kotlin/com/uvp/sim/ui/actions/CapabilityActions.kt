package com.uvp.sim.ui.actions

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.ui.AlarmFireMode

/**
 * GB/T 28181 业务能力动作 — slice 2/4(PR-B)。
 *
 * 范围:抓拍 / 报警 6 子动作 / 语音广播 2 子动作 / 目录 2 子动作 /
 * 媒体状态异常模拟 / PTZ 渲染回写。
 *
 * UI 调用点:
 *   - HomeScreen — snapshot / alarmFireDefault / alarmReset / broadcastStop / broadcastToggleSpeaker
 *   - AlarmManagementScreen — 6 报警方法 + 2 mediaStatusAbnormal
 *   - CatalogManagementScreen — onCatalogTreeSave / onToggleChannelStatus
 *   - SimulateScreen — onPoseTick(~6Hz 渲染线程回写)
 */
interface CapabilityActions {
    // ---- 抓拍 ----
    fun onSnapshot()

    // ---- 报警(6 方法)----

    /**
     * 主屏 tile 一键 / 能力页子页详细编辑后发送报警。
     * engine 走 reportAlarm:MESSAGE 给注册中心 + NOTIFY 给 Alarm 订阅人。
     */
    fun onAlarmFire(payload: AlarmPayload)

    /**
     * 用户本地复位报警(主屏报警中 tile 点击确认 / 子页复位按钮)。
     * 仅翻 isAlarming=false + emit AlarmReset(local),**不走 SIP**(spec S4)。
     */
    fun onAlarmReset()

    /**
     * 主页报警 tile「一点即发」(spec G1)。按当前 alarmFireMode 发:
     * Random → 抽模板;Fixed → 发 fixedAlarmTemplate(空则退化随机)。
     */
    fun onAlarmFireDefault()

    /** 设置报警发送模式(能力页报警卡)。 */
    fun onSetAlarmFireMode(mode: AlarmFireMode)

    /** 保存固定报警单(指定模式),主页一点即发此单。 */
    fun onSaveFixedAlarm(payload: AlarmPayload)

    /**
     * M5 batch1 §C3 — 报警子页"高级模拟"折叠区:演示触发 MediaStatus 122/123。
     * notifyType ∈ {122 录像异常, 123 存储满}。engine 走 triggerMediaStatusAbnormal:
     * 注册中心 MESSAGE + Alarm 订阅人 NOTIFY 同 fan-out。
     */
    fun onSimulateMediaStatusAbnormal(notifyType: Int)

    // ---- 语音广播(2 方法)----

    /** M3 用户停止语音广播(主屏「对讲中」标签 ✕)。engine.stopBroadcast(Local)。 */
    fun onBroadcastStop()

    /** M3 切换对讲扬声器开关(🔊/🔇)。on=true 放音,false 静音。 */
    fun onBroadcastToggleSpeaker(on: Boolean)

    // ---- 目录管理(2 方法)----

    /**
     * 用户在目录管理界面点保存,把新树写回 engine + 持久化。
     * 若有活跃 Catalog 订阅,engine 会立即推一次完整 NOTIFY(spec Q6)。
     *
     * 返回值:null 表示成功;非 null 是校验失败的错误消息(用 \n 分隔多行)。
     */
    fun onCatalogTreeSave(tree: List<CatalogNode>): String?

    /**
     * M5 batch2 §7.10 — 切换通道在线状态(模拟离线 / 恢复在线)。
     * engine 走 toggleChannelStatus:更新 fields["Status"] + 给 Catalog 订阅
     * fan-out 简化 NOTIFY(只含 DeviceID + Event + Status)。
     */
    fun onToggleChannelStatus(channelId: String, online: Boolean)

    // ---- PTZ 渲染回写 ----

    /**
     * 渲染层(GlbSceneState)节流回写当前 PTZ 姿态到 AppEngine.deviceControlState。
     * 由 Filament 渲染线程每 ~10 帧调用一次(166ms),
     * 让 SetPreset 入库的是真实当前姿态而不是 0/0/1。
     */
    fun onPoseTick(pan: Float, tilt: Float, zoom: Float)
}
