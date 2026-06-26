package com.uvp.sim.api

/**
 * 系统日志分类标签。SIP 故意不在这里 — SIP 事件走 SimEvent,
 * 系统日志只记非协议层关注点。
 *
 * UI 友好公开 API (PR-A T1.3 缩水版搬家). 旧位置 com.uvp.sim.observability.LogTag
 * 通过 typealias 兜底, shared 内部代码零侵入.
 */
enum class LogTag(val display: String) {
    Network("NETWORK"),
    Media("MEDIA"),
    Lifecycle("LIFECYCLE"),
    Resource("RESOURCE"),
    User("USER"),
    Subscription("SUBSCRIPTION"),
}
