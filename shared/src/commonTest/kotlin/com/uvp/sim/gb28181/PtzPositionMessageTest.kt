package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PtzPositionMessageTest {

    private val position = PtzPositionSnapshot(
        pan = 12.5,
        tilt = -3.25,
        zoom = 2.0,
        horizontalFieldAngle = 30.0,
        verticalFieldAngle = 18.0,
        maxViewDistance = 600.0,
    )

    @Test
    fun build_matches2022ResponseGoldenFixture() {
        val xml = PtzPositionMessage.build(
            sn = 7,
            deviceId = "34020000001320000001",
            position = position,
        )

        assertEquals(
            """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>PTZPosition</CmdType>
<SN>7</SN>
<DeviceID>34020000001320000001</DeviceID>
<Pan>12.50</Pan>
<Tilt>-3.25</Tilt>
<Zoom>2.00</Zoom>
<HorizontalFieldAngle>30.00</HorizontalFieldAngle>
<VerticalFieldAngle>18.00</VerticalFieldAngle>
<MaxViewDistance>600.00</MaxViewDistance>
</Response>
""".replace("\n", "\r\n"),
            xml,
        )
    }

    @Test
    fun parse_requiresAllSixStandardFields() {
        val valid = PtzPositionMessage.parse(
            PtzPositionMessage.build(7, "34020000001320000001", position)
        )
        assertEquals(position, valid?.position)

        val missingMaxDistance = PtzPositionMessage.build(7, "34020000001320000001", position)
            .replace("<MaxViewDistance>600.00</MaxViewDistance>\r\n", "")
        assertNull(PtzPositionMessage.parse(missingMaxDistance))
    }

    @Test
    fun parse_focusAndIrisCannotReplaceStandardFields() {
        val extensionOnly = """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>PTZPosition</CmdType>
<SN>9</SN>
<DeviceID>34020000001320000001</DeviceID>
<Focus>0.75</Focus>
<Iris>0.50</Iris>
</Response>
""".replace("\n", "\r\n")

        assertNull(PtzPositionMessage.parse(extensionOnly))
    }

    @Test
    fun parse_rejectsWrongEnvelopeOrIdentifier() {
        val valid = PtzPositionMessage.build(7, "34020000001320000001", position)

        assertNull(PtzPositionMessage.parse(valid.replace("<Response>", "<Notify>")))
        assertNull(PtzPositionMessage.parse(valid.replace("</Response>", "</Notify>")))
        assertNull(PtzPositionMessage.parse(valid.replace("PTZPosition", "PTZPreciseStatusQuery")))
        assertNull(PtzPositionMessage.parse(valid.replace("<SN>7</SN>", "<SN>0</SN>")))
        assertNull(PtzPositionMessage.parse(valid.replace("34020000001320000001", "bad-id")))
    }
}
