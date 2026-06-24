package com.uvp.sim.domain.coord

/**
 * 跨 Coordinator 反向调用契约(plan 第 2.5 节 "BroadcastInvoker" 段)。
 *
 * [BroadcastCoordinator] ↔ [ManscdpRouter] 之间需要双向调用:
 * - ManscdpRouter 收到 MANSCDP Broadcast 命令时,要让 BroadcastCoordinator 起会
 * - BroadcastCoordinator 发出去的 INVITE / NOTIFY 需要 router 内部的 SN 分配器
 *
 * 把"对方暴露给我的窄接口"独立出来,避免两个 Coordinator 拿到对方的完整 interface
 * 形成隐式耦合;同时 PR3 ManscdpRouter 先落地时,可以临时注入一个 "调用旧 Engine 私有
 * 方法"的 anonymous impl(见 tasks T3.3),PR5 BroadcastCoordinator 落地时再替换。
 */
internal interface BroadcastInvoker {
    /**
     * ManscdpRouter 收到 platform 端 MANSCDP Broadcast 后调用。
     * 由 [BroadcastCoordinator] 真实实现:发 INVITE / 等 200 OK / 起 RX。
     */
    suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String)
}

/**
 * BroadcastCoordinator 反向需要 router 内部能力(SN 分配 / SIP fromTag 池等)。
 * 当前只暴露 SN 分配;后续需要新增能力时在此扩展。
 */
internal interface ManscdpRouterFacade {
    fun nextSn(): String
}

/**
 * **PR4 临时桥**(PR5 BroadcastCoordinator 落地时整段删除)。
 *
 * 决策 6(plan-tasks §2):PR4 抽 InviteCoordinator 时,Invite 实现 [BroadcastInvoker.fireBroadcastInvite]
 * 把 SIP INVITE 发出去 + 处理 200 OK / 4xx 响应,但 RX 媒体链(rtpReceiver / audioPlayback /
 * `_currentBroadcast` 状态机)**仍然留 Engine 上**。
 *
 * Invite 在 200 OK / 4xx 处理完调本接口告诉 Engine 该启 RX 还是清状态。Engine 实现接口的
 * 三个回调:
 *   - [onInviting]:INVITE 发出去后,Engine 把 `_currentBroadcast.value = BroadcastDialog(Inviting)`
 *   - [onTalking]:200 OK + ACK 处理完 + codec 校验通过,Engine `state = Talking` + 启 RX 链
 *   - [onFailed]:4xx / codec 拒绝 / TCP 连接失败,Engine 清状态 + emit BroadcastEnded
 *
 * PR5 拆 BroadcastCoordinator 时:本接口由 BroadcastCoordinator 实现,Invite 退出广播域。
 */
internal interface BroadcastDialogHandshakeListener {
    /**
     * PR4 临时桥:Engine 同步 bind RTP receiver 拿本地音频端口。
     * Invite 发 outbound INVITE 之前必须用真实端口拼 SDP offer。
     * 返回 -1 表示 bind 失败,Invite 取消 INVITE。
     */
    suspend fun bindBroadcastRtpPort(mode: com.uvp.sim.network.RtpMode): Int

    suspend fun onInviting(
        callId: String,
        fromTag: String,
        cseq: Int,
        sourceId: String,
        targetId: String,
        platformUri: String,
        localAudioPort: Int,
        deviceSsrc: String,
        mode: com.uvp.sim.network.RtpMode,
    )

    suspend fun onTalking(
        callId: String,
        remoteTag: String,
        remoteHost: String,
        remotePort: Int,
        codec: com.uvp.sim.domain.AudioRxCodec,
    )

    suspend fun onFailed(callId: String, reason: BroadcastEndReasonHint)
}
