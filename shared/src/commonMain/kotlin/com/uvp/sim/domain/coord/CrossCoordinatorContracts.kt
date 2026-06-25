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
