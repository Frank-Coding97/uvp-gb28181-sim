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
