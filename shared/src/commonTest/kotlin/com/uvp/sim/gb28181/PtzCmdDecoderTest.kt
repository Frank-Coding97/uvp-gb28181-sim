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

    // ---------- 预置位 (GB-2022 §F.3 byte3 高 4 位 = 0x8) ----------

    @Test
    fun `预置位 SetPreset 编号 3`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x81, 0x03, 0x00, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Preset, "expected Preset, got $ins")
        ins as PtzInstruction.Preset
        assertEquals(PresetOp.SET, ins.op)
        assertEquals(3, ins.index)
    }

    @Test
    fun `预置位 CallPreset 编号 5`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x82, 0x05, 0x00, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Preset)
        ins as PtzInstruction.Preset
        assertEquals(PresetOp.CALL, ins.op)
        assertEquals(5, ins.index)
    }

    @Test
    fun `预置位 DelPreset 编号 1`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x83, 0x01, 0x00, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Preset)
        ins as PtzInstruction.Preset
        assertEquals(PresetOp.DEL, ins.op)
        assertEquals(1, ins.index)
    }

    @Test
    fun `预置位 校验和错误返回 null`() {
        // 故意把 checksum 改错
        assertNull(PtzCmdDecoder.decodeInstruction("A50F01810300000000"))
    }

    @Test
    fun `decodeInstruction 对方向位返回 Motion`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x02, 0x32, 0x00, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Motion)
        ins as PtzInstruction.Motion
        assertEquals(PanDirection.LEFT, ins.cmd.panDirection)
        assertEquals(50, ins.cmd.panSpeed)
    }

    @Test
    fun `decode 老接口对预置位返回 null 向后兼容`() {
        // 既有调用方只关心 Motion,预置位走 decodeInstruction 分支,decode 应该 null
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x81, 0x03, 0x00, 0x00)
        assertNull(PtzCmdDecoder.decode(hex))
    }

    @Test
    fun `decodeInstruction 对 0x91 Right plus ZoomIn plus FocusNear bit 组合 走 Motion`() {
        // 0x91 = bit0 (Right) + bit4 (ZoomIn) + bit7 (FocusNear) — 一次性同时下发 3 个动作
        // 这种组合实际不一定常见,但 GB28181 byte3 是位字段,各位独立有效
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x91, 0x32, 0x00, 0x80)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Motion)
        ins as PtzInstruction.Motion
        assertEquals(PanDirection.RIGHT, ins.cmd.panDirection)
        assertEquals(ZoomDirection.IN, ins.cmd.zoomDirection)
        assertEquals(FocusDirection.NEAR, ins.cmd.focusDirection)
    }

    // ---------- 辅助控制 (GB-2022 §F.3 byte3 = 0x89 / 0x8A) ----------

    @Test
    fun `Aux On 雨刷 byte4 eq 1`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x89, 0x01, 0x00, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Aux, "expected Aux, got $ins")
        ins as PtzInstruction.Aux
        assertTrue(ins.on)
        assertEquals(1, ins.index)
        assertEquals(AuxFunction.Wiper, AuxFunction.fromIndex(ins.index))
    }

    @Test
    fun `Aux Off 加热 byte4 eq 3`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x8A, 0x03, 0x00, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Aux)
        ins as PtzInstruction.Aux
        assertFalse(ins.on)
        assertEquals(3, ins.index)
        assertEquals(AuxFunction.Heater, AuxFunction.fromIndex(ins.index))
    }

    @Test
    fun `Aux 未知 index 仍解码成功 由 dispatcher 决定语义`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x89, 0xEE, 0x00, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Aux)
        ins as PtzInstruction.Aux
        assertEquals(0xEE, ins.index)
        assertNull(AuxFunction.fromIndex(0xEE))  // 没有映射
    }

    // ---------- Focus (byte3 bit6 / bit7) ----------

    @Test
    fun `Focus Near bit7 speed eq 8`() {
        // byte3 = 0x80 (bit7), byte6 低 4 位 = focus speed = 8
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x80, 0x00, 0x00, 0x08)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(FocusDirection.NEAR, cmd?.focusDirection)
        assertEquals(8, cmd?.focusSpeed)
        assertEquals(PanDirection.NONE, cmd?.panDirection)
    }

    @Test
    fun `Focus Far bit6 speed eq 4`() {
        // byte3 = 0x40 (bit6), byte6 低 4 位 = focus speed = 4
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x40, 0x00, 0x00, 0x04)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(FocusDirection.FAR, cmd?.focusDirection)
        assertEquals(4, cmd?.focusSpeed)
    }

    @Test
    fun `Zoom plus Focus 同时 bit4 plus bit6`() {
        // byte3 = 0x50 = bit4 + bit6 → ZoomIn + FocusFar
        // byte6 = 0x84 → 高 4 位 zoom speed = 8 / 低 4 位 focus speed = 4
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x50, 0x00, 0x00, 0x84)
        val cmd = PtzCmdDecoder.decode(hex)
        assertEquals(ZoomDirection.IN, cmd?.zoomDirection)
        assertEquals(8, cmd?.zoomSpeed)
        assertEquals(FocusDirection.FAR, cmd?.focusDirection)
        assertEquals(4, cmd?.focusSpeed)
    }
}
