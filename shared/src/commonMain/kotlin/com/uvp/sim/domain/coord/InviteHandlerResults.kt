package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipOutbox
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * cross-review R3 拆分 — InviteCoordinator handler 间共享类型。
 *
 * 设计原则:
 * - Handler(AcceptHandler / MediaPipeline / DialogHandler)**不直接持有 var 共享 state**;
 *   通过 [InviteSharedState] 窄读窗口拿当前 activeStream / SipState / awaitingAckCallId
 * - Handler 完成后**返回 sealed result** ([AcceptResult] / [DialogResult]),
 *   主类 `InviteCoordinatorImpl` 按结果决定是否切 `SipState` / 是否调 `cleanupActiveStream`
 * - 这样 `mutableSipState.value = transition(...)` 只在主类 1 处出现,杜绝 round-1/2/3 反复出的
 *   "state-stranding"类 bug
 */

/**
 * INVITE 接受路径(AcceptHandler.handleInvite)返回值。
 *
 * 主类按分支处理:
 * - [Success] → 主类用 [AcceptedInvite] 启动 media pipeline → 拼 ActiveStream → 发布 →
 *   切 SipState.InviteReceived → install ACK watchdog(顺序严格,治本 R3 #1)
 * - [Rejected] → handler 已发拒绝响应(488/500/404 等),主类**不**切 InCall,留 Registered
 * - [Failed] → handler 已尝试发兜底响应(可能已成功也可能没),主类**不**切 InCall,
 *   做好资源回收(关 rtp 等)
 */
internal sealed class AcceptResult {
    /** 200 OK 已成功发出,媒体管线该启动。载体含主类装配 ActiveStream 所需的全部 pre-media 字段 */
    data class Success(val accepted: AcceptedInvite) : AcceptResult()

    /** 已发拒绝响应(488 SDP / 500 RTP bind / 404 unknown channel / 486 busy 等),不进 InCall */
    data class Rejected(val statusCode: Int, val reason: String) : AcceptResult()

    /** 异常路径(已尝试发兜底响应),不进 InCall,主类清资源 */
    data class Failed(val cause: Throwable) : AcceptResult()
}

/**
 * mid-dialog 请求(ACK / CANCEL / BYE)处理返回值。
 *
 * 主类按分支处理:
 * - [TerminateDialog] → 调 `cleanupActiveStream(reason)`,主类负责 `SipState` 转换
 * - [KeepDialog] → 不动 state(handler 已处理完所有副作用)
 */
internal sealed class DialogResult {
    /** dialog 该终止(BYE / pending-phase CANCEL / pre-publication CANCEL) */
    data class TerminateDialog(val reason: String) : DialogResult()

    /** dialog 保留(ACK 收妥 / mid-dialog 校验失败已 481 / late CANCEL 仅 200) */
    object KeepDialog : DialogResult()
}

/**
 * Handler 共享 state 注入口 — 主类暴露给 handler 的**窄只读窗口**。
 *
 * Handler **不持有** activeStream / acceptInFlight / mutableSipState 的 var 引用;
 * 通过本接口的 getter 函数读当前值,需要写则**返回 sealed result** 让主类去写。
 *
 * 设计约束(plan §6 R3):InviteSharedState 成员数 **≤ 10**,防止滚成第二个 god object。
 * 新增成员需在 plan 文档列出 + 拍板。
 */
internal interface InviteSharedState {
    val config: SimConfig
    val outbox: SipOutbox
    val scope: CoroutineScope
    val catalogTree: StateFlow<List<CatalogNode>>
    val localIp: String
    val localPort: Int

    /** Handler 读 activeStream 当前值(不可写) */
    fun currentActiveStream(): InviteCoordinatorImpl.ActiveStream?

    /** Handler 读当前 SipState(不可写) */
    fun currentSipState(): SipState

    /** Handler 发布业务事件到 SimEvent 流 */
    suspend fun simEventEmit(event: SimEvent)
}
