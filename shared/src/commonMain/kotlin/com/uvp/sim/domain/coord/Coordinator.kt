package com.uvp.sim.domain.coord

import com.uvp.sim.network.NetworkState
import com.uvp.sim.sip.SipMessage

/**
 * 一个 Coordinator 拥有一个 SIP 对话域(注册 / 主叫推流 / 回放 / 语音对讲 / MANSCDP 路由)。
 * Engine 总分发把 transport.incoming 的每条消息**顺序**喂给所有 Coordinator,
 * 第一个返回 [RoutingResult.Handled] 的吃下,后续 Coordinator 不再处理。
 *
 * 重构 spec:[[wiki/projects/uvp-gb28181-sim/specs/refactor-simulator-engine]]
 * 重构 plan:[[wiki/projects/uvp-gb28181-sim/plans/refactor-simulator-engine]]
 */
internal interface Coordinator {
    /**
     * Engine 总分发的入口。Coordinator 自己判断这条消息是否归我管。
     *
     * - 命中(如 dialog Call-ID 匹配 / SIP method 属于本域):处理后返回 [RoutingResult.Handled]
     * - 不归我管:返回 [RoutingResult.Skip],Engine 会试下一个 Coordinator
     * - 归我但处理失败:返回 [RoutingResult.Error],Engine 记录错误并不再 fallthrough
     */
    suspend fun onIncoming(msg: SipMessage): RoutingResult

    /**
     * 网络状态变化(IPv4 切换 / Wi-Fi ↔ 4G / 离线)。
     * 默认不做任何事;只有需要重建 dialog 的 Coordinator 重写
     * (如 Registration 触发重注册,Invite/Playback 中断当前流)。
     */
    suspend fun onNetworkChange(state: NetworkState) {
        // default: no-op
    }

    /**
     * Engine.shutdown() 时调用,Coordinator 必须释放自己持有的资源
     * (RTP socket / 音频引擎 / 定时器 / 协程)。
     */
    suspend fun shutdown()
}

/**
 * 总分发路由结果。
 */
internal sealed class RoutingResult {
    /** 我吃了。Engine 不再 fallthrough。 */
    data object Handled : RoutingResult()

    /** 不归我管。Engine 试下一个 Coordinator。 */
    data object Skip : RoutingResult()

    /** 归我但处理失败。Engine 记日志且不 fallthrough。 */
    data class Error(val reason: String) : RoutingResult()
}
