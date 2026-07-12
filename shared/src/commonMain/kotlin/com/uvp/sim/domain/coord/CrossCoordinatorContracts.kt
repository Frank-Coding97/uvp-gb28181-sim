package com.uvp.sim.domain.coord

/**
 * 跨 Coordinator 反向调用契约。
 *
 * [BroadcastCoordinator] ↔ [ManscdpRouter] 之间需要双向调用:
 * - ManscdpRouter 收到 MANSCDP Broadcast 命令时,要让 BroadcastCoordinator 起会
 * - BroadcastCoordinator 发出去的 INVITE / NOTIFY 需要 router 内部的 SN 分配器
 */
internal interface BroadcastInvoker {
    /**
     * ManscdpRouter 收到 platform 端 MANSCDP Broadcast 后调用。
     * PR5 T5.4 起由 [BroadcastCoordinator] 真实实现(PR4 由 InviteCoordinator 临时实现已废止)。
     *
     * cross-review R1 #2:返回 [BroadcastInviteStart] 表示"资源预留 + INVITE 发送"是否成功。
     * true = 已发出反向 INVITE,平台可以等 200 OK;
     * false = RTP 端口绑定失败或 INVITE 送不出,调用方(BroadcastSubRouter)应发 MANSCDP ERROR
     * 而不是先回 OK 再让平台干等。
     */
    suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String): BroadcastInviteStart
}

/**
 * cross-review R1 #2:BroadcastInviter 是否已成功启动反向 INVITE。
 */
internal enum class BroadcastInviteStart {
    /** RTP 已绑定 + INVITE 已通过 outbox 送出。平台可预期收到反向 INVITE。 */
    Started,
    /** RTP 绑定失败 → 已 emit BroadcastEnded(Error),未发 INVITE。调用方应对上层回 ERROR。 */
    RtpBindFailed,
    /** RTP 绑定 OK,但 INVITE outbox 送不出 → 已回滚状态。调用方应对上层回 ERROR。 */
    InviteSendFailed,
}

/**
 * BroadcastCoordinator 反向需要 router 内部能力(SN 分配 / SIP fromTag 池等)。
 * 当前只暴露 SN 分配;后续需要新增能力时在此扩展。
 */
internal interface ManscdpRouterFacade {
    fun nextSn(): String
}
