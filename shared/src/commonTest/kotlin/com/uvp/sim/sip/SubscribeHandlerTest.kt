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
            SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
            SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-abc")
        )
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
        val req = subscribeRequest(event = "catalog")
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
        // Catalog 默认 Expires 86400(24h)
        assertEquals(86400, intent.expiresSeconds)
        // Catalog 不周期推送,interval=0
        assertEquals(0, intent.intervalSeconds)
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
}
