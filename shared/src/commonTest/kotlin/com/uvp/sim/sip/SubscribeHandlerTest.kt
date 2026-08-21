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
        body: String = """<?xml version="1.0"?>
<Query>
<CmdType>MobilePosition</CmdType>
<SN>1</SN>
<DeviceID>34020000001110000001</DeviceID>
<Interval>5</Interval>
</Query>"""
    ): SipRequest {
        val headers = mutableListOf(
            SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=$fromTag"),
            SipMessage.Header(SipHeader.CONTACT, "<sip:34020000002000000001@192.168.1.100:5060>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-abc")
        )
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
    fun missingContactRejects400() {
        val request = subscribeRequest().copy(
            headers = subscribeRequest().headers.filterNot {
                SipHeader.canonicalize(it.name) == SipHeader.CONTACT
            },
        )
        val intent = SubscribeHandler.parse(request, emptySet())
        assertIs<SubscribeIntent.Reject>(intent)
        assertEquals(400, intent.statusCode)
        assertEquals("Missing Contact", intent.reason)
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
