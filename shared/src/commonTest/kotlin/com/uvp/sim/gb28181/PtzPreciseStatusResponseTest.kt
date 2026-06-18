package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.PtzPose
import kotlin.test.Test
import kotlin.test.assertTrue

/** GB-2022 §9.5.3 A.2.4.13 PTZ 精准状态查询应答测试. */
class PtzPreciseStatusResponseTest {

    private val cfg = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "12345"
        )
    )

    @Test
    fun build_completePose() {
        val xml = PtzPreciseStatusResponse.build(
            config = cfg,
            sn = "9",
            channelId = "ch001",
            pose = PtzPose(45.5f, -10.0f, 3.0f)
        )
        assertTrue(xml.contains("<CmdType>PTZPreciseStatusQuery</CmdType>"))
        assertTrue(xml.contains("<SN>9</SN>"))
        assertTrue(xml.contains("<DeviceID>ch001</DeviceID>"))
        assertTrue(xml.contains("<Pan>45.50</Pan>"))
        assertTrue(xml.contains("<Tilt>-10.00</Tilt>"))
        assertTrue(xml.contains("<Zoom>3.00</Zoom>"))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
    }

    @Test
    fun build_blankChannelId_fallsBackToDeviceId() {
        val xml = PtzPreciseStatusResponse.build(
            config = cfg,
            sn = "1",
            channelId = "",
            pose = PtzPose(0f, 0f, 1f)
        )
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        assertTrue(xml.contains("<Pan>0.00</Pan>"))
        assertTrue(xml.contains("<Zoom>1.00</Zoom>"))
    }
}
