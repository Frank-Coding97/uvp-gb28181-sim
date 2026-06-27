package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.gb28181.ManscdpParser

/**
 * 4 个 [ManscdpSubRouter] 的链式分派器(Wave 4 PR-D / P2-1)。
 *
 * 派发规则:
 *  - 按 [routers] 顺序问 [ManscdpSubRouter.accepts]
 *  - 命中后调 [ManscdpSubRouter.handle];返回 true 表示已吃下,不再 fallthrough
 *  - 都不识别返回 false,Caller 可走 fallback(目前 [ManscdpRouterImpl] 直接吞掉,跟原行为一致)
 *
 * 解析 cmdType 在 dispatcher 里做一次(子路由的 accepts/handle 都收同一个 [cmdType] 字符串,避免重复 grep)。
 */
internal class ManscdpDispatcher(
    private val routers: List<ManscdpSubRouter>,
) {

    /**
     * 路由一条 MANSCDP MESSAGE 体。
     *
     * @return true=被某个 SubRouter 处理了;false=都不识别
     */
    suspend fun route(xml: String, fromUri: String?): Boolean {
        val cmd = ManscdpParser.cmdType(xml) ?: return false
        val target = routers.firstOrNull { it.accepts(cmd) } ?: return false
        return target.handle(cmd, xml, fromUri)
    }
}
