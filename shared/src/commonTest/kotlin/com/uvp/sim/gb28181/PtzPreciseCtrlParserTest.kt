package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** GB-2022 §9.3.4 A.2.3.1.11 PTZPreciseCtrl 解析测试. */
class PtzPreciseCtrlParserTest {

    @Test
    fun `完整字段解析成功`() {
        val xml = """
            <Control>
              <CmdType>DeviceControl</CmdType>
              <PTZPreciseCtrl>
                <Pan>123.45</Pan>
                <Tilt>-15.0</Tilt>
                <Zoom>3.5</Zoom>
              </PTZPreciseCtrl>
            </Control>
        """.trimIndent()
        val p = PtzPreciseCtrlParser.parse(xml)
        assertEquals(PtzPreciseCtrl(123.45f, -15.0f, 3.5f), p)
    }

    @Test
    fun `缺 Tilt 字段返回 null`() {
        val xml = "<C><PTZPreciseCtrl><Pan>1.0</Pan><Zoom>2.0</Zoom></PTZPreciseCtrl></C>"
        assertNull(PtzPreciseCtrlParser.parse(xml))
    }

    @Test
    fun `非数字字段返回 null`() {
        val xml = "<C><PTZPreciseCtrl><Pan>abc</Pan><Tilt>0</Tilt><Zoom>1</Zoom></PTZPreciseCtrl></C>"
        assertNull(PtzPreciseCtrlParser.parse(xml))
    }
}
