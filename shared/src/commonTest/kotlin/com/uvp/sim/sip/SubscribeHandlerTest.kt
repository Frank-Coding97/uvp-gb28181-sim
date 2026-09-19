package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubscribeHandlerTest {

    private fun subscribeRequest(
        event: String? = "presence",
        expires: String? = "1800",
        callId: String = "abc123@192.168.1.1",
        fromTag: String = "tag-from-platform",
        toHeader: String? = "<sip:34020000001110000001@192.168.1.50:5060>",
        contactHeader: String? = "<sip:34020000002000000001@192.168.1.100:5060>",
        // 默认复刻平台真实形态:From 的 host 是 **SIP 域标识**(3402000000),不是地址。
        fromHeader: String = "<sip:34020000002000000001@3402000000>;tag=$fromTag",
        body: String = """<?xml version="1.0"?>
<Query>
<CmdType>MobilePosition</CmdType>
<SN>1</SN>
<DeviceID>34020000001110000001</DeviceID>
<Interval>5</Interval>
</Query>"""
    ): SipRequest {
        val headers = mutableListOf(
            SipMessage.Header(SipHeader.FROM, fromHeader),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-abc")
        )
        if (contactHeader != null) headers += SipMessage.Header(SipHeader.CONTACT, contactHeader)
        if (toHeader != null) headers += SipMessage.Header(SipHeader.TO, toHeader)
        if (event != null) headers += SipMessage.Header(SipHeader.EVENT, event)
        if (expires != null) headers += SipMessage.Header(SipHeader.EXPIRES, expires)
        return SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = headers,
            body = body.encodeToByteArray()
        )
    }

    @Test
    fun normalSubscribeReturnsNewSubscription() {
        val req = subscribeRequest()
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("MobilePosition", intent.kind)
        assertEquals(5, intent.intervalSeconds)
        assertEquals(1800, intent.expiresSeconds)
        assertEquals("tag-from-platform", intent.fromTag)
        assertEquals("sip:34020000002000000001@3402000000", intent.subscriberUri)
        assertEquals("sip:34020000001110000001@192.168.1.50:5060", intent.notifierUri)
        assertEquals("sip:34020000002000000001@192.168.1.100:5060", intent.notifyRequestUri)
    }

    @Test
    fun missingToRejects400() {
        val intent = SubscribeHandler.parse(subscribeRequest(toHeader = null), emptySet())
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(400, intent.statusCode)
        assertEquals("Missing To", intent.reason)
    }

    @Test
    fun toWithoutDeviceIdentityRejects400() {
        val intent = SubscribeHandler.parse(
            subscribeRequest(toHeader = "<sip:192.168.1.50:5060>"),
            emptySet(),
        )
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(400, intent.statusCode)
        assertEquals("To URI must contain device identity", intent.reason)
    }

    @Test
    fun contactPresent_isUsedVerbatim_andNotMarkedAsFallback() {
        // ⛔ transportSource 必须传一个**与 Contact 不同**的地址:否则"Contact 优先"这条
        // 语义根本没有被检验(来源缺席时回落本来就推不出东西,不变异也看不出来)。
        val intent = SubscribeHandler.parse(
            subscribeRequest(),
            emptySet(),
            transportSource = TransportSource("192.168.10.106", 5060),
        )
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("sip:34020000002000000001@192.168.1.100:5060", intent.notifyRequestUri)
        assertEquals(false, intent.notifyRequestUriIsFallback)
    }

    // ------------------------------------------------------------------
    // Contact 缺失的回落策略(2026-09-19 起不再直接 400)
    //
    // 背景:本平台 `uac.go buildSubscribeRequest` 刻意不带 Contact,旧行为直接把整条
    // Catalog / MobilePosition / PTZPosition 订阅链路打成 400(SIP 层无任何异常可看)。
    // ------------------------------------------------------------------

    @Test
    fun missingContact_withoutAnySource_stillRejects400() {
        // 兜底不能退化成"无条件接受":连来源都推不出来时,接受订阅只会换来一个发不出去的
        // NOTIFY —— 不如 400 说得清楚。
        val intent = SubscribeHandler.parse(
            subscribeRequest(contactHeader = null),
            emptySet(),
            transportSource = null,
        )
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(400, intent.statusCode)
        assertEquals("Missing Contact", intent.reason)
    }

    @Test
    fun missingContact_fallsBackToTransportSource_whenFromHostIsDomainIdentifier() {
        // 平台 From 的 host 是 SIP 域标识 `3402000000`(不可路由)→ 必须落到 sourceIp:sourcePort
        val intent = SubscribeHandler.parse(
            subscribeRequest(contactHeader = null),
            emptySet(),
            transportSource = TransportSource("192.168.10.106", 5060),
        )
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("sip:34020000002000000001@192.168.10.106:5060", intent.notifyRequestUri)
        assertEquals(true, intent.notifyRequestUriIsFallback)
        // 回落不得影响其余字段
        assertEquals("MobilePosition", intent.kind)
        assertEquals(1800, intent.expiresSeconds)
        assertEquals(5, intent.intervalSeconds)
        assertEquals("tag-from-platform", intent.fromTag)
    }

    @Test
    fun missingContact_prefersFromHostWhenItLooksLikeAnAddress() {
        // From 直接写了地址时用它(比传输层来源更贴合 UAC 的自我声明),端口取自 From
        val intent = SubscribeHandler.parse(
            subscribeRequest(
                contactHeader = null,
                fromHeader = "<sip:34020000002000000001@10.9.8.7:5070>;tag=tag-from-platform",
            ),
            emptySet(),
            transportSource = TransportSource("192.168.10.106", 5060),
        )
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("sip:34020000002000000001@10.9.8.7:5070", intent.notifyRequestUri)
        assertEquals(true, intent.notifyRequestUriIsFallback)
    }

    @Test
    fun missingContact_fromHostWithoutPort_borrowsPortFromTransportSource() {
        // From 有地址但没端口 → 端口借传输层来源的;⛔ 不能猜一个 5060 了事
        val intent = SubscribeHandler.parse(
            subscribeRequest(
                contactHeader = null,
                fromHeader = "<sip:34020000002000000001@10.9.8.7>;tag=tag-from-platform",
            ),
            emptySet(),
            transportSource = TransportSource("192.168.10.106", 5080),
        )
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("sip:34020000002000000001@10.9.8.7:5080", intent.notifyRequestUri)
    }

    @Test
    fun missingContact_ipv6LiteralInFrom_isNotSplitOnInnerColon() {
        val intent = SubscribeHandler.parse(
            subscribeRequest(
                contactHeader = null,
                fromHeader = "<sip:34020000002000000001@[fe80::1]:5062>;tag=tag-from-platform",
            ),
            emptySet(),
            transportSource = TransportSource("192.168.10.106", 5060),
        )
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("sip:34020000002000000001@[fe80::1]:5062", intent.notifyRequestUri)
    }

    @Test
    fun missingContact_fromWithoutUser_rejects400() {
        // From 连 user 都没有 → 推不出订阅者身份,回 400(不是硬凑一个匿名 URI)
        val intent = SubscribeHandler.parse(
            subscribeRequest(
                contactHeader = null,
                fromHeader = "<sip:192.168.9.9:5060>;tag=tag-from-platform",
            ),
            emptySet(),
            transportSource = TransportSource("192.168.10.106", 5060),
        )
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(400, intent.statusCode)
    }

    @Test
    fun missingContact_onAlarmEvent_alsoFallsBack() {
        // Alarm 走 Event 短路分支,是**另一处** NewSubscription 构造点 —— 同样要带回落标记
        val intent = SubscribeHandler.parse(
            subscribeRequest(
                event = "Alarm",
                expires = null,
                contactHeader = null,
                body = "<Query><SN>1</SN></Query>",
            ),
            emptySet(),
            transportSource = TransportSource("192.168.10.106", 5060),
        )
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Alarm", intent.kind)
        assertEquals("sip:34020000002000000001@192.168.10.106:5060", intent.notifyRequestUri)
        assertEquals(true, intent.notifyRequestUriIsFallback)
    }

    @Test
    fun missingEventRejects489() {
        val req = subscribeRequest(event = null)
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(489, intent.statusCode)
    }

    @Test
    fun wrongEventRejects489() {
        val req = subscribeRequest(event = "dialog")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(489, intent.statusCode)
    }

    @Test
    fun expires0WithKnownCallIdReturnsCancel() {
        val callId = "known-call@host"
        val req = subscribeRequest(expires = "0", callId = callId)
        val intent = SubscribeHandler.parse(req, setOf(callId))
        assertIs<SubscribeIntent.Cancel>(intent)
        assertEquals(callId, intent.callId)
    }

    @Test
    fun existingCallIdReturnsRefresh() {
        val callId = "existing@host"
        val req = subscribeRequest(callId = callId, expires = "3600")
        val intent = SubscribeHandler.parse(req, setOf(callId))
        assertIs<SubscribeIntent.Refresh>(intent)
        assertEquals(3600, intent.newExpiresSeconds)
    }

    @Test
    fun missingCmdTypeRejects400() {
        val req = subscribeRequest(body = "<Query><SN>1</SN></Query>")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(400, intent.statusCode)
    }

    @Test
    fun catalogCmdTypeReturnsNewSubscriptionWithKindCatalog() {
        val body = """<?xml version="1.0"?>
<Query><CmdType>Catalog</CmdType><SN>1</SN><DeviceID>dev1</DeviceID></Query>"""
        val req = subscribeRequest(body = body, expires = null)
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Catalog", intent.kind)
        // GB/T 28181-2016 附录 P.2.1 默认 Expires 600s
        assertEquals(600, intent.expiresSeconds)
        // Catalog 不周期推送,interval=0
        assertEquals(0, intent.intervalSeconds)
    }

    @Test
    fun catalogSubscribeAcceptsEventCatalog() {
        // GB §9.3.1.2: 目录订阅 Event 头是 "Catalog",不是 presence
        val body = """<?xml version="1.0"?>
<Query><CmdType>Catalog</CmdType><SN>1</SN><DeviceID>dev1</DeviceID></Query>"""
        val req = subscribeRequest(event = "Catalog", body = body, expires = "86400")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Catalog", intent.kind)
    }

    @Test
    fun catalogSubscribeAcceptsEventCatalogWithIdParameter() {
        val body = """<?xml version="1.0"?>
<Query><CmdType>Catalog</CmdType><SN>1</SN></Query>"""
        val req = subscribeRequest(event = "Catalog;id=abc", body = body, expires = "86400")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Catalog", intent.kind)
    }

    @Test
    fun catalogWithExplicitExpiresUsesHeader() {
        val body = """<?xml version="1.0"?>
<Query><CmdType>Catalog</CmdType><SN>1</SN></Query>"""
        val req = subscribeRequest(body = body, expires = "7200")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Catalog", intent.kind)
        assertEquals(7200, intent.expiresSeconds)
    }

    @Test
    fun unsupportedCmdTypeReturnsIgnored() {
        val body = """<?xml version="1.0"?>
<Query><CmdType>RecordInfo</CmdType><SN>1</SN></Query>"""
        val req = subscribeRequest(body = body)
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.Ignored>(intent)
        assertEquals("RecordInfo", intent.cmdType)
    }

    @Test
    fun defaultIntervalIs30WhenMissing() {
        val body = """<?xml version="1.0"?>
<Query><CmdType>MobilePosition</CmdType><SN>1</SN><DeviceID>dev1</DeviceID></Query>"""
        val req = subscribeRequest(body = body)
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals(30, intent.intervalSeconds)
    }

    @Test
    fun defaultExpiresIs3600WhenMissing() {
        val req = subscribeRequest(expires = null)
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals(3600, intent.expiresSeconds)
    }

    @Test
    fun eventWithIdParameterAccepted() {
        val req = subscribeRequest(event = "presence;id=abc123")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
    }

    @Test
    fun alarmEventReturnsNewSubscriptionWithKindAlarm() {
        // Event: Alarm 走 Event 头直接判定,body 可不含标准 CmdType
        val req = subscribeRequest(event = "Alarm", expires = null, body = "<Query><SN>1</SN></Query>")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Alarm", intent.kind)
    }

    @Test
    fun alarmEventWithIdParameterAccepted() {
        val req = subscribeRequest(event = "Alarm;id=42", expires = null, body = "<Query><SN>1</SN></Query>")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Alarm", intent.kind)
    }

    @Test
    fun alarmDefaultExpiresIs3600() {
        val req = subscribeRequest(event = "Alarm", expires = null, body = "<Query><SN>1</SN></Query>")
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals(3600, intent.expiresSeconds)
    }

    @Test
    fun wvpAlarmSubscribeViaPresenceEventAndBodyCmdType() {
        // WVP-Pro 实测:报警订阅用 Event: presence;id=xxx + body <CmdType>Alarm</CmdType>,
        // 不是裸 Event: Alarm。必须靠 body CmdType 识别成 kind=Alarm,否则 Ignored → 超时。
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<Query>
<CmdType>Alarm</CmdType>
<SN>916329</SN>
<DeviceID>35020000001310000001</DeviceID>
</Query>"""
        val req = subscribeRequest(event = "presence;id=8052", expires = "60", body = body)
        val intent = SubscribeHandler.parse(req, emptySet())
        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("Alarm", intent.kind)
        assertEquals(60, intent.expiresSeconds)
        assertEquals(0, intent.intervalSeconds)
    }

    @Test
    fun ptzPositionSubscribeReturnsIndependentEventDrivenKind() {
        val body = """<?xml version="1.0" encoding="GB2312"?>
<Query>
<CmdType>PTZPosition</CmdType>
<SN>18</SN>
<DeviceID>34020000001320000001</DeviceID>
</Query>"""
        val intent = SubscribeHandler.parse(
            subscribeRequest(event = "PTZPosition", expires = null, body = body),
            emptySet(),
        )

        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("PtzPrecisePosition", intent.kind)
        assertEquals(0, intent.intervalSeconds)
        assertEquals(3600, intent.expiresSeconds)
    }

    @Test
    fun ptzPositionSubscribeAcceptsEventIdParameter() {
        val body = """<?xml version="1.0" encoding="GB2312"?>
<Query><CmdType>PTZPosition</CmdType><SN>19</SN><DeviceID>34020000001320000001</DeviceID></Query>"""
        val intent = SubscribeHandler.parse(
            subscribeRequest(event = "PTZPosition;id=ptz-position", body = body),
            emptySet(),
        )

        assertIs<SubscribeIntent.NewSubscription>(intent)
        assertEquals("PtzPrecisePosition", intent.kind)
    }

    @Test
    fun ptzPositionEventRejectsMismatchedCmdType() {
        val body = """<?xml version="1.0"?>
<Query><CmdType>MobilePosition</CmdType><SN>20</SN><DeviceID>34020000001320000001</DeviceID></Query>"""
        val intent = SubscribeHandler.parse(
            subscribeRequest(event = "PTZPosition", body = body),
            emptySet(),
        )

        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(400, intent.statusCode)
    }
}
