package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * GB/T 28181-2022 §F.3 PTZ 8 字节命令解码测试.
 *
 * 8 字节布局:
 *   B0=0xA5  B1=0x0F  B2=0x01(地址)
 *   B3=指令码: bit0=右 bit1=左 bit2=下 bit3=上 bit4=zoom in bit5=zoom out
 *   B4=Pan speed (0-255)
 *   B5=Tilt speed (0-255)
 *   B6=Zoom speed (高 4 位, 0-15)
 *   B7=checksum = (B0+B1+B2+B3+B4+B5+B6) mod 256
 */
class PtzCmdDecoderTest {

    private fun hex7ChecksumHex(b0: Int, b1: Int, b2: Int, b3: Int, b4: Int, b5: Int, b6: Int): String {
        val sum = (b0 + b1 + b2 + b3 + b4 + b5 + b6) and 0xFF
        return listOf(b0, b1, b2, b3, b4, b5, b6, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    @Test
    fun `case 1 — 左转 speed=50`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x02, 0x32, 0x00, 0x00)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(PanDirection.LEFT, cmd?.panDirection)
        assertEquals(50, cmd?.panSpeed)
        assertEquals(TiltDirection.NONE, cmd?.tiltDirection)
        assertEquals(0, cmd?.tiltSpeed)
        assertEquals(ZoomDirection.NONE, cmd?.zoomDirection)
        assertEquals(0, cmd?.zoomSpeed)
    }

    @Test
    fun `case 2 — 上仰 speed=100`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x08, 0x00, 0x64, 0x00)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(TiltDirection.UP, cmd?.tiltDirection)
        assertEquals(100, cmd?.tiltSpeed)
        assertEquals(PanDirection.NONE, cmd?.panDirection)
        assertEquals(0, cmd?.panSpeed)
    }

    @Test
    fun `case 3 — zoom in speed=8`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x10, 0x00, 0x00, 0x80)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(ZoomDirection.IN, cmd?.zoomDirection)
        assertEquals(8, cmd?.zoomSpeed)
        assertEquals(PanDirection.NONE, cmd?.panDirection)
        assertEquals(TiltDirection.NONE, cmd?.tiltDirection)
    }

    @Test
    fun `case 4 — 左上组合 pan=50 tilt=100`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x0A, 0x32, 0x64, 0x00)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(PanDirection.LEFT, cmd?.panDirection)
        assertEquals(TiltDirection.UP, cmd?.tiltDirection)
        assertEquals(50, cmd?.panSpeed)
        assertEquals(100, cmd?.tiltSpeed)
    }

    @Test
    fun `case 5 — 停止全零`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x00, 0x00, 0x00, 0x00)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(PanDirection.NONE, cmd?.panDirection)
        assertEquals(TiltDirection.NONE, cmd?.tiltDirection)
        assertEquals(ZoomDirection.NONE, cmd?.zoomDirection)
        assertEquals(0, cmd?.panSpeed)
        assertEquals(0, cmd?.tiltSpeed)
        assertEquals(0, cmd?.zoomSpeed)
    }

    @Test
    fun `case 6 — 校验码错误返回 null`() {
        val cmd = PtzCmdDecoder.decode("A50F010232000000")
        assertNull(cmd)
    }

    @Test
    fun `case 7 — 长度不足返回 null`() {
        assertNull(PtzCmdDecoder.decode("A50F010232"))
        assertNull(PtzCmdDecoder.decode(""))
        assertNull(PtzCmdDecoder.decode("A50F0102320000AAEE"))
    }

    @Test
    fun `case 8 — zoom out bit5`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x20, 0x00, 0x00, 0x80)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(ZoomDirection.OUT, cmd?.zoomDirection)
        assertEquals(8, cmd?.zoomSpeed)
    }

    @Test
    fun `右下组合`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x05, 0x10, 0x20, 0x00)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(PanDirection.RIGHT, cmd?.panDirection)
        assertEquals(TiltDirection.DOWN, cmd?.tiltDirection)
    }

    @Test
    fun `verifyChecksum 直接接口`() {
        val good = byteArrayOf(0xA5.toByte(), 0x0F, 0x01, 0x00, 0x00, 0x00, 0x00, 0xB5.toByte())
        assertTrue(PtzCmdDecoder.verifyChecksum(good))
        val bad = byteArrayOf(0xA5.toByte(), 0x0F, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertFalse(PtzCmdDecoder.verifyChecksum(bad))
    }

    @Test
    fun `小写 hex 也能解码`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x02, 0x32, 0x00, 0x00).lowercase()
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(PanDirection.LEFT, cmd?.panDirection)
    }

    @Test
    fun `非 hex 字符返回 null`() {
        assertNull(PtzCmdDecoder.decode("A50F01023200ZZ00"))
    }
}
