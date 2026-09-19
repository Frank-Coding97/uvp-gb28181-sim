package com.uvp.sim.sip

import com.uvp.sim.gb28181.ManscdpParser

/**
 * 入站报文的**传输层来源**(由 [com.uvp.sim.network.SipEnvelope] 提供)。
 *
 * 用途:平台 SUBSCRIBE 没带 `Contact` 时,回落构造 NOTIFY 的 Request-URI。
 * 见 [SubscribeHandler.parse] 的 `transportSource` 参数。
 */
data class TransportSource(val host: String, val port: Int)

sealed class SubscribeIntent {
    data class NewSubscription(
        val kind: String,
        val subscriberUri: String,
        val notifierUri: String,
        val notifyRequestUri: String,
        val event: String,
        val callId: String,
        val fromTag: String,
        val intervalSeconds: Int,
        val expiresSeconds: Int,
        /**
         * [notifyRequestUri] 是否是**回落**来的(平台没带 Contact,按传输层来源地址推的)。
         *
         * 这是「宽容处理但不隐瞒」:接受订阅(真机多数不校验 Contact),但要把这件事说出来,
         * 免得将来 NOTIFY 发不到时无从判断。UAC 侧按 RFC 3261 §12.1.1 本就该带 Contact。
         */
        val notifyRequestUriIsFallback: Boolean = false
    ) : SubscribeIntent()

    data class Refresh(
        val callId: String,
        val fromTag: String,
        val newExpiresSeconds: Int
    ) : SubscribeIntent()

    data class Cancel(
        val callId: String,
        val fromTag: String
    ) : SubscribeIntent()

    data class Reject(
        val statusCode: Int,
        val reason: String
    ) : SubscribeIntent()

    data class Ignored(
        val cmdType: String
    ) : SubscribeIntent()
}

object SubscribeHandler {

    private const val DEFAULT_EXPIRES_MOBILE_POSITION = 3600
    private const val DEFAULT_EXPIRES_CATALOG = 600    // GB/T 28181-2016 附录 P.2.1 默认 600s
    private const val DEFAULT_EXPIRES_ALARM = 3600     // GB §9.5.2 报警订阅,比 Catalog 短(spec Q7)
    private const val DEFAULT_EXPIRES_PTZ_POSITION = 3600
    private const val DEFAULT_SIP_PORT = 5060

    /**
     * 解析 SUBSCRIBE → [SubscribeIntent]。
     *
     * @param knownCallIds 已建立 dialog 的 Call-ID 集合(用于区分 Refresh / Cancel)
     * @param transportSource 报文来源的 host/port(来自 [com.uvp.sim.network.SipEnvelope]);
     *   仅在**平台没带 Contact 头**时用作回落的 NOTIFY Request-URI 来源。
     *
     * **关于 Contact 缺失(2026-09-19 起策略变更)**
     *
     * RFC 3261 §12.1.1 要求 SUBSCRIBE 必须带 Contact —— 设备靠它得到 NOTIFY 的 Request-URI。
     * 但实测本平台(`server/app/gb28181/uac/uac.go` 的 `buildSubscribeRequest`)**刻意不带**,
     * 于是整条 Catalog / MobilePosition / PTZPosition 订阅链路连 200 都拿不到
     * (设备回 `400 Missing Contact`,而 SIP 层看不出任何异常)。
     *
     * 变更后的优先级(逐级降级,都不成仍回 400):
     *  1. `Contact` 头 —— 标准路径,原样使用;
     *  2. `From` 的 host 若是**可路由**形态(IP 字面量 / 含点的域名)→ 用它 + From 的 port;
     *     ⛔ 本平台 From 的 host 是 SIP **域标识**(如 `3402000000`),不是地址,必须过滤掉;
     *  3. [transportSource] 的 host/port —— 报文真实来源(UDP/TCP 源地址)。
     *
     * ⛔ 不要退化成「用 From 的 host 无条件回填」:那样会拼出 `sip:平台ID@3402000000`
     * 这种不可路由的 Request-URI,NOTIFY 直接发不出去 —— 换了个更隐蔽的失败。
     */
    fun parse(
        request: SipRequest,
        knownCallIds: Set<String>,
        transportSource: TransportSource? = null,
    ): SubscribeIntent {
        val event = request.firstHeader(SipHeader.EVENT)
        // GB/T 28181-2016 的订阅示例既有 Event:presence(J.20),也有
        // 附录 P.4.1 的 Event:Catalog;id=num;对话内必须原样回显收到的 Event。
        // 移动位置使用 presence,报警使用 presence/Alarm 取决于对端实现。
        // GB/T 28181-2022 PTZ 精准位置订阅:Event:PTZPosition。
        // 四种都接受,具体 kind:Alarm 直接由 Event 头判定(报警 SUBSCRIBE
        // body 可能不带标准 CmdType),presence/Catalog 看 body CmdType 再确认。
        // Event 头允许带参数,如 "presence;id=xxx"、"PTZPosition;id=yyy",取分号前主标识。
        val eventName = event?.trim()?.substringBefore(';')?.trim()
        val isAlarmEvent = eventName != null && eventName.equals("Alarm", ignoreCase = true)
        val isPtzPositionEvent = eventName != null && eventName.equals("PTZPosition", ignoreCase = true)
        val eventOk = eventName != null && (
            eventName.equals("presence", ignoreCase = true) ||
                eventName.equals("Catalog", ignoreCase = true) ||
                isPtzPositionEvent ||
                isAlarmEvent
            )
        if (!eventOk) {
            return SubscribeIntent.Reject(489, "Bad Event: ${event ?: "(missing)"}")
        }

        val expiresHeader = request.firstHeader(SipHeader.EXPIRES)?.trim()?.toIntOrNull()

        val callId = request.callId() ?: return SubscribeIntent.Reject(400, "Missing Call-ID")

        // R2 #6:Refresh / Cancel 不能只看 Call-ID,把入站 From tag 带出去给 router 跟已注册
        // dialog.fromTag 对比,防止同链路下其他订阅者凭 Call-ID 复用 / 旁路掉别人的 dialog。
        val incomingFromTag = extractTag(request.fromHeader() ?: "") ?: ""

        if (expiresHeader == 0 && callId in knownCallIds) {
            return SubscribeIntent.Cancel(callId, incomingFromTag)
        }

        if (expiresHeader != null && expiresHeader > 0 && callId in knownCallIds) {
            return SubscribeIntent.Refresh(callId, incomingFromTag, expiresHeader)
        }

        val fromHeader = request.fromHeader() ?: ""
        val subscriberUri = extractUri(fromHeader)
        val notifierUri = extractUri(
            request.toHeader() ?: return SubscribeIntent.Reject(400, "Missing To")
        )
        if (SipHeaderHelpers.parseUriUser(notifierUri).isBlank()) {
            return SubscribeIntent.Reject(400, "To URI must contain device identity")
        }
        val contactUri = request.firstHeader(SipHeader.CONTACT)
            ?.let(::extractUri)
            ?.takeIf { it.isNotBlank() }
        val fallbackUri = if (contactUri == null) {
            buildFallbackNotifyRequestUri(fromHeader, transportSource)
        } else {
            null
        }
        val notifyRequestUri = contactUri ?: fallbackUri
            ?: return SubscribeIntent.Reject(400, "Missing Contact")
        val notifyRequestUriIsFallback = contactUri == null
        val fromTag = incomingFromTag

        // Event: Alarm 短路 — 报警是事件流,不依赖 body CmdType,不周期推送(interval=0)。
        if (isAlarmEvent) {
            return SubscribeIntent.NewSubscription(
                kind = "Alarm",
                subscriberUri = subscriberUri,
                notifierUri = notifierUri,
                notifyRequestUri = notifyRequestUri,
                event = event.trim(),
                callId = callId,
                fromTag = fromTag,
                intervalSeconds = 0,
                expiresSeconds = expiresHeader ?: DEFAULT_EXPIRES_ALARM,
                notifyRequestUriIsFallback = notifyRequestUriIsFallback,
            )
        }

        // ⚠️ 这里按 UTF-8 硬解,刻意不按 §6.10 的报文声明解码 —— 因为本函数只从 body 里取
        // CmdType 这类**纯 ASCII** 标签(GB2312/GB18030/UTF-8 三种编码下字节完全相同),
        // 不把任何中文内容带出去(只回 kind/interval/expires 这些枚举与数字)。
        // 哪天真要读 body 里的中文,必须换成 SignalingCharset.decodeSignalingBody。
        val xml = request.body.decodeToString()
        val cmdType = ManscdpParser.cmdType(xml)
            ?: return SubscribeIntent.Reject(400, "Missing CmdType in body")
        if (isPtzPositionEvent && !cmdType.equals("PTZPosition", ignoreCase = true)) {
            return SubscribeIntent.Reject(400, "Event PTZPosition requires CmdType PTZPosition")
        }

        // WVP-Pro 报警订阅:Event: presence + body <CmdType>Alarm</CmdType>(不是裸 Event:Alarm)
        val kind = when {
            cmdType.equals("MobilePosition", ignoreCase = true) -> "MobilePosition"
            cmdType.equals("Catalog", ignoreCase = true) -> "Catalog"
            cmdType.equals("Alarm", ignoreCase = true) -> "Alarm"
            cmdType.equals("PTZPosition", ignoreCase = true) -> "PtzPrecisePosition"
            else -> return SubscribeIntent.Ignored(cmdType)
        }

        val defaultExpires = when (kind) {
            "Catalog" -> DEFAULT_EXPIRES_CATALOG
            "Alarm" -> DEFAULT_EXPIRES_ALARM
            "PtzPrecisePosition" -> DEFAULT_EXPIRES_PTZ_POSITION
            else -> DEFAULT_EXPIRES_MOBILE_POSITION
        }
        val expires = expiresHeader ?: defaultExpires

        // Interval 对 Catalog / Alarm 不适用(不周期推送),仍解析便于日志
        val intervalStr = ManscdpParser.tagValue(xml, "Interval")
        val interval = intervalStr?.toIntOrNull() ?: when (kind) {
            "Catalog", "Alarm", "PtzPrecisePosition" -> 0
            else -> 30
        }

        return SubscribeIntent.NewSubscription(
            kind = kind,
            subscriberUri = subscriberUri,
            notifierUri = notifierUri,
            notifyRequestUri = notifyRequestUri,
            event = event.trim(),
            callId = callId,
            fromTag = fromTag,
            intervalSeconds = interval,
            expiresSeconds = expires,
            notifyRequestUriIsFallback = notifyRequestUriIsFallback,
        )
    }

    /**
     * Contact 缺失时,从 `From` + 传输层来源推一个可路由的 NOTIFY Request-URI。
     *
     * @return 推不出来(From 连 user 都没有)时返回 `null` —— 调用方据此仍回 400。
     */
    private fun buildFallbackNotifyRequestUri(
        fromHeader: String,
        transportSource: TransportSource?,
    ): String? {
        val fromUri = extractUri(fromHeader)
        val (user, fromHost, fromPort) = splitSipUri(fromUri)
        if (user.isBlank()) return null

        // From 的 host 只有在"像地址"时才敢用:本平台填的是 SIP 域标识(`3402000000`),
        // 直接回填会拼出不可路由的 Request-URI。
        val host = if (looksLikeAddress(fromHost)) fromHost else transportSource?.host.orEmpty()
        if (host.isBlank()) return null
        val port = fromPort ?: transportSource?.port ?: DEFAULT_SIP_PORT
        return "sip:$user@$host:$port"
    }

    /** 拆 `sip:user@host[:port][;params]` → (user, host, port?);缺段返回空串/null。 */
    private fun splitSipUri(uri: String): Triple<String, String, Int?> {
        val withoutScheme = uri.substringAfter("sip:", uri).substringBefore(';')
        // ⛔ user 只在真有 `@` 时才取 —— 否则 `sip:192.168.9.9:5060` 会把整个地址当成 user,
        // 于是"From 没有订阅者身份"这个情况被误判成有,回落到一个匿名 URI。
        val hasAt = withoutScheme.contains('@')
        val user = if (hasAt) withoutScheme.substringBefore('@') else ""
        val hostPort = if (hasAt) withoutScheme.substringAfter('@') else withoutScheme
        // IPv6 字面量形如 [::1]:5060 —— 冒号在方括号内,不能按首个冒号切
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return Triple(user, "", null)
            val host = hostPort.substring(0, close + 1)
            val port = hostPort.substring(close + 1).removePrefix(":").toIntOrNull()
            return Triple(user, host, port)
        }
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "").toIntOrNull()
        return Triple(user, host, port)
    }

    /** `true` = IP 字面量或含点域名(可路由);`false` = 裸标识(如 SIP 域 `3402000000`)。 */
    private fun looksLikeAddress(host: String): Boolean {
        if (host.isBlank()) return false
        if (host.startsWith("[")) return true          // [IPv6]
        if (host.contains(':')) return true            // 裸 IPv6
        return host.contains('.')                      // IPv4 或 FQDN
    }

    private fun extractUri(header: String): String {
        val start = header.indexOf('<')
        val end = header.indexOf('>')
        return if (start >= 0 && end > start) header.substring(start + 1, end) else header.trim()
    }

    private fun extractTag(header: String): String? {
        val tagIdx = header.indexOf(";tag=")
        if (tagIdx < 0) return null
        val afterTag = header.substring(tagIdx + 5)
        val endIdx = afterTag.indexOfFirst { it == ';' || it == '>' || it == ' ' }
        return if (endIdx >= 0) afterTag.substring(0, endIdx) else afterTag
    }
}
