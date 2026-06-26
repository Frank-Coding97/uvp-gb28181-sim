package com.uvp.sim.domain.coord

import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.gb28181.AlarmPayload
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MANSCDP 路由器(纯路由 + 主动报文发起)。
 *
 * 接管的 SIP 流程(plan 第 2.5 节):
 * - MESSAGE(非主路径)→ 解析 MANSCDP CmdType 派发
 *   (DeviceControl / Catalog / RecordInfo / Alarm / DeviceInfo / DeviceStatus / ConfigDownload)
 * - INFO(不含 MANSRTSP body)→ MANSCDP
 * - SUBSCRIBE → SubscriptionRegistry
 *
 * 主动发起(给 UI / shell 用):
 * - reportAlarm / localResetAlarm / reportSnapshot / triggerMediaStatusAbnormal
 * - attachSnapshotPipeline(由 Android 壳 / iOS 壳启动期注入)
 *
 * 来自 SimulatorEngine 的方法迁移清单:handleMessage / handleInfo(非 MANSRTSP)/
 * handleDeviceControl / handleBroadcast(改为调 BroadcastInvoker)/ handleRecordInfoQuery /
 * handleSubscribe / sendAlarmNotify / sendDeviceControlResponse / pushAlarmResetNotify /
 * sendMediaStatusNotifyToSubscriber / sendSnapshotNotify / reportAlarm / localResetAlarm /
 * reportSnapshot / triggerMediaStatusAbnormal / attachSnapshotPipeline
 */
internal interface ManscdpRouter : Coordinator {
    val events: SharedFlow<ManscdpEvent>

    /** 5.13 / M2 §F.3 设备控制运行时状态(UI 3D 渲染层订阅)。 */
    val deviceControlState: StateFlow<DeviceControlState>

    /** 主动发起 — 报警上报。 */
    suspend fun reportAlarm(payload: AlarmPayload)

    /** 主动发起 — 本地复位报警(对所有订阅者发 NOTIFY)。 */
    suspend fun localResetAlarm()

    /** 主动发起 — 抓拍上报(媒体上传通知)。 */
    suspend fun reportSnapshot()

    /** 主动发起 — 媒体状态异常通知(GB-2022 §9.4.7)。 */
    suspend fun triggerMediaStatusAbnormal(notifyType: Int)

    /** Android/iOS 壳启动期挂抓拍管线。 */
    fun attachSnapshotPipeline(
        capture: com.uvp.sim.snapshot.SnapshotCapture,
        cache: com.uvp.sim.snapshot.JpegLocalCache,
        httpClient: HttpClient,
    )
}

internal sealed class ManscdpEvent {
    data class DeviceControlReceived(val cmd: String, val from: String?) : ManscdpEvent()
    data class AlarmSent(val sn: String, val payload: AlarmPayload) : ManscdpEvent()
    data class CatalogQueryHandled(val sn: String) : ManscdpEvent()
    data class SubscribeHandled(val sn: String, val kind: String) : ManscdpEvent()
}
