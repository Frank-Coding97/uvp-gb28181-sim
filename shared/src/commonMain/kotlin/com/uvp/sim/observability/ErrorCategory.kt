package com.uvp.sim.observability

/**
 * P3-3 (audit §4.5 / Wave 5 PR-OBSERVABILITY) — 错误分级。
 *
 * 老的 `runCatching` / `try-catch` 各自处理,UI 层只能看到 `[ExceptionType]` 字符串,
 * 没有"重试有用 / 重试没用"的语义提示。引入 5 类 category:
 *
 * - [Transient]:网络抖动 / socket 暂闭等可重试的临时错。后台重连机制应自动恢复。
 * - [Permanent]:认证失败 / 协议不兼容 / 服务器拒绝。重试无意义,需要用户介入配置。
 * - [UserInput]:配置缺字段 / 格式非法。报回 UI 后用户改输入即可。
 * - [ProtocolViolation]:对端不遵 GB28181 / SIP 协议,SDP / 头字段畸形。技术问题,需告诉对方修。
 * - [Internal]:我方 bug(空指针 / 越界 / 不变量违反)。需开发者修代码,默认兜底。
 *
 * Mapping 规则见 [com.uvp.sim.domain.UserErrorMapper.categorize] — 用 simpleName 字符串匹配,
 * commonMain 不能 import `java.net.*`,所以走 reflective 名字判断。
 */
enum class ErrorCategory {
    Transient,
    Permanent,
    UserInput,
    ProtocolViolation,
    Internal,
}
