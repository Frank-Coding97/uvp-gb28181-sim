package com.uvp.sim.sip

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaStatusNotifyTest {

    private fun stubConfig() = SimConfig(
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "12345678"
        ),
        server = ServerConfig(
            serverId = "34020000002000000001",
            domain = "3402000000",
            ip = "192.0.2.10",
            port = 5060
        )
    )

    @Test fun xml_containsNotifyType121() {
        val xml = MediaStatusNotify.buildXml("34020000001320000001", 7)
        assertTrue(xml.contains("<NotifyType>121</NotifyType>"))
    }

    @Test fun xml_containsGb2312Declaration() {
        val xml = MediaStatusNotify.buildXml("dev", 1)
        assertTrue(xml.contains("encoding=\"GB2312\""))
    }

    @Test fun xml_containsDeviceId() {
        val xml = MediaStatusNotify.buildXml("34020000001320000001", 7)
        assertTrue(xml.contains("<DeviceID>34020000001320000001</DeviceID>"))
    }

    @Test fun xml_containsSn() {
        val xml = MediaStatusNotify.buildXml("dev", 42)
        assertTrue(xml.contains("<SN>42</SN>"))
    }

    @Test fun xml_containsCmdTypeMediaStatus() {
        val xml = MediaStatusNotify.buildXml("dev", 1)
        assertTrue(xml.contains("<CmdType>MediaStatus</CmdType>"))
    }

    @Test fun build_methodIsMessage() {
        val req = MediaStatusNotify.build(
            config = stubConfig(), cseq = 5, callId = "abc",
            branch = "z9hG4bK-x", fromTag = "ft1",
            localIp = "192.0.2.99", localPort = 5060, sn = 1
        )
        assertEquals(SipMethod.MESSAGE, req.method)
    }

    @Test fun build_contentTypeIsMansCdpXml() {
        val req = MediaStatusNotify.build(
            config = stubConfig(), cseq = 5, callId = "abc",
            branch = "z9hG4bK-x", fromTag = "ft1",
            localIp = "192.0.2.99", localPort = 5060, sn = 1
        )
        assertEquals(
            "application/MANSCDP+xml",
            req.firstHeader(SipHeader.CONTENT_TYPE)
        )
    }
}
