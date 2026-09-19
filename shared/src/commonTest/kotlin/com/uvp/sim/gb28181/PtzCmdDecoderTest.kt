package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * GB/T 28181-2022 附录 A 前端设备控制 8 字节命令解码测试.
 *
 * 8 字节布局:
 *   B0=0xA5  B1=0x0F  B2=0x01(地址)
 *   B3=指令码,分四个子族:
 *     0x81/0x82/0x83 预置位 · 0x84~0x88 巡航 · 0x89/0x8A 扫描·辅助  → 整字节取值
 *     bit7=0 bit6=0 → PTZ 族(表 A.5): bit0=右 bit1=左 bit2=下 bit3=上 bit4=放大 bit5=缩小
 *     bit7=0 bit6=1 → FI 族(表 A.6):  bit3/bit2=光圈缩小/放大 · bit1/bit0=聚焦近/远
 *   B4=数据1: PTZ 族 = 水平速度 / FI 族 = **聚焦**速度
 *   B5=数据2: PTZ 族 = 垂直速度 / FI 族 = **光圈**速度
 *   B6=组合码2: 高 4 位 = 变倍速度(0-15),低 4 位 = 地址高 4 位
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
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x81, 0x00, 0x03, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Preset, "expected Preset, got $ins")
        ins as PtzInstruction.Preset
        assertEquals(PresetOp.SET, ins.op)
        assertEquals(3, ins.index)
    }

    @Test
    fun `预置位 CallPreset 编号 5`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x82, 0x00, 0x05, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Preset)
        ins as PtzInstruction.Preset
        assertEquals(PresetOp.CALL, ins.op)
        assertEquals(5, ins.index)
    }

    @Test
    fun `预置位 DelPreset 编号 1`() {
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x83, 0x00, 0x01, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Preset)
        ins as PtzInstruction.Preset
        assertEquals(PresetOp.DEL, ins.op)
        assertEquals(1, ins.index)
    }

    // ─── 巡航族 0x84~0x88(表 A.8)────────────────────────────────────────────
    //
    // 字节位:**字节5 = 巡航组号**,字节6 = 预置位号(0x84/0x85) 或 12 位参数的**低 8 位**
    // (0x86/0x87),字节7 高 4 位 = 12 位参数的**高 4 位**。
    // 下面一律**硬编码真实字节**、不用 helper 生成 —— helper 和解码器一起错时
    // 两者会互相印证(预置位那条 2026-09-16 的坑就是这么骗过去的)。

    @Test
    fun `巡航 0x84 加入巡航点 组1 预置位3`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F01840103003D")
        assertTrue(ins is PtzInstruction.Cruise, "expected Cruise, got $ins")
        ins as PtzInstruction.Cruise
        assertEquals(CruiseOp.SET_POINT, ins.op)
        assertEquals(1, ins.trackNum)
        assertEquals(3, ins.param)
    }

    @Test
    fun `巡航 0x85 删除巡航点 组1 预置位3`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F01850103003E")
        assertTrue(ins is PtzInstruction.Cruise, "expected Cruise, got $ins")
        ins as PtzInstruction.Cruise
        assertEquals(CruiseOp.DEL_POINT, ins.op)
        assertEquals(1, ins.trackNum)
        assertEquals(3, ins.param)
    }

    @Test
    fun `巡航 0x88 开始巡航 组1`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F01880100003E")
        assertTrue(ins is PtzInstruction.Cruise, "expected Cruise, got $ins")
        ins as PtzInstruction.Cruise
        assertEquals(CruiseOp.START, ins.op)
        assertEquals(1, ins.trackNum)
    }

    /**
     * ⛔ 回归锚点:12 位参数**必须**把字节7 的高半字节拼回来。
     *
     * `A50F0186012C1078` = 组1 设巡航速度 **300**(0x12C):字节6=0x2C(低 8 位)、
     * 字节7 高半字节=0x1(高 4 位)。原实现只读 `bytes[5]`(字节6),解出来是 **44** ——
     * 平台发 300、设备收到 44,而且是**静默**的。类头那张表一直写着"低 8 位 / 高 4 位",
     * 是实现没照着做。
     */
    @Test
    fun `巡航 0x86 设速度 12位参数要拼上字节7高半字节 300 不是 44`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F0186012C1078")
        assertTrue(ins is PtzInstruction.Cruise, "expected Cruise, got $ins")
        ins as PtzInstruction.Cruise
        assertEquals(CruiseOp.SET_SPEED, ins.op)
        assertEquals(1, ins.trackNum)
        assertEquals(300, ins.param, "只取低 8 位会得到 44 —— 高 4 位丢了")
    }

    @Test
    fun `巡航 0x87 设停留时间 12位满量程 4095`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F018702FFF02D")
        assertTrue(ins is PtzInstruction.Cruise, "expected Cruise, got $ins")
        ins as PtzInstruction.Cruise
        assertEquals(CruiseOp.SET_DWELL_TIME, ins.op)
        assertEquals(2, ins.trackNum)
        assertEquals(4095, ins.param)
    }

    @Test
    fun `巡航 0x86 小值仍然正确 速度 45 高半字节为 0`() {
        // 45 = 0x02D → 字节6=0x2D、字节7 高半字节=0x0。拼不拼高半字节都得 45,
        // 这条用来钉住"小值不能被拼错"(高半字节误当地位就会变成 45+... )
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x86, 0x01, 0x2D, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Cruise)
        ins as PtzInstruction.Cruise
        assertEquals(CruiseOp.SET_SPEED, ins.op)
        assertEquals(45, ins.param)
    }

    /**
     * ⛔ 回归锚点:平台真实下发的置预置位帧。
     *
     * `A50F018100010037` 是**标准出版物与厂商文档共同给出的样例**(「新增1号预置点」),
     * 也是本仓平台 `POST /ptz/presets {presetId:1}` 实际发出的字节 —— 2026-09-16 从
     * SIP trace 解密出来逐字节核对过。
     *
     * 原实现读 `bytes[4]`(字节5,固定为 0x00)→ 解出编号 0 → 越界丢弃,
     * 于是**预置位永远存不进去**,连带看守位自动归位(依赖 `presets[PresetIndex]`)
     * 永不触发。这条用例直接钉住 wire 上的真值,不用 helper 生成,避免 helper 与解码器
     * 一起错还互相印证。
     */
    @Test
    fun `预置位 平台真实报文 A50F018100010037 解出编号 1`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F018100010037")
        assertTrue(ins is PtzInstruction.Preset, "expected Preset, got $ins")
        ins as PtzInstruction.Preset
        assertEquals(PresetOp.SET, ins.op)
        assertEquals(1, ins.index, "编号在字节6(数据2),字节5 固定 0x00")
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
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x81, 0x00, 0x03, 0x00)
        assertNull(PtzCmdDecoder.decode(hex))
    }

    @Test
    fun `decodeInstruction 对标准未定义的字节4返回 null 而不是猜成某个动作`() {
        // 0x91:bit7=1 且不属于 0x81~0x8A(预置位/巡航/扫描·辅助),掉在标准没定义的空隙里;
        // 也不可能是 FI 族(表 A.6 把该族高 4 位钉死在 0100B,0x91 的 bit6=0)。
        // 原实现让这类字节落进 PTZ 按位拆解,凭空造出「右转 + 放大 + 聚焦近」三个动作。
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x91, 0x32, 0x00, 0x80)
        assertNull(PtzCmdDecoder.decodeInstruction(hex))
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

    // ---------- FI 族(GB/T 28181-2022 表 A.6:聚焦 / 光圈)----------
    //
    // 字节4 = `0 1 0 0 | bit3 bit2 | bit1 bit0`
    //   bit3/bit2 = 光圈缩小 / 光圈放大   (0x08 / 0x04)
    //   bit1/bit0 = 聚焦近   / 聚焦远     (0x02 / 0x01)
    // 字节5 = 聚焦速度(注2),字节6 = 光圈速度(注3),0x40 = 该族停止。
    //
    // ⚠️ 下面几条含"平台真实下发报文"的用例:那 5 个 8 字节是平台侧
    //    `encodePTZ` 的实际产物,拿来当输入是为了把两侧的解码口径焊在一起 ——
    //    任何一边改了字节位置或速度字节,这里都会红。
    //
    // 历史:本文件原先有 4 条把 bit6/bit7 当 Focus Near/Far、把字节6 低 4 位当聚焦速度的
    // 用例(Focus Near bit7 / Focus Far bit6 / Zoom+Focus / 0x91 三动作组合),那是按
    // 某些厂商文档的误读写的,与标准表 A.6 不符,已按标准重写。

    @Test
    fun `FI — 平台四个镜头动作的真实报文都解出正确语义`() {
        val cases = listOf(
            "A50F014120000016" to Pair(FocusDirection.FAR, IrisDirection.NONE),
            "A50F014220000017" to Pair(FocusDirection.NEAR, IrisDirection.NONE),
            "A50F014400200019" to Pair(FocusDirection.NONE, IrisDirection.OPEN),
            "A50F01480020001D" to Pair(FocusDirection.NONE, IrisDirection.CLOSE),
        )
        for ((hex, expected) in cases) {
            val ins = PtzCmdDecoder.decodeInstruction(hex)
            assertTrue(ins is PtzInstruction.Lens, "$hex 期望 Lens,实际 $ins")
            ins as PtzInstruction.Lens
            assertEquals(expected.first, ins.focus, hex)
            assertEquals(expected.second, ins.iris, hex)
        }
    }

    @Test
    fun `FI — 聚焦速度在字节5、光圈速度在字节6(不对称是标准如此)`() {
        val focus = PtzCmdDecoder.decodeInstruction("A50F014120000016") as PtzInstruction.Lens
        assertEquals(0x20, focus.focusSpeed)
        assertEquals(0, focus.irisSpeed)

        val iris = PtzCmdDecoder.decodeInstruction("A50F014400200019") as PtzInstruction.Lens
        assertEquals(0, iris.focusSpeed)
        assertEquals(0x20, iris.irisSpeed)
    }

    @Test
    fun `FI — 0x40 是该族停止,两轴都归 NONE`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F0140000000F5")
        assertTrue(ins is PtzInstruction.Lens, "0x40 期望 Lens,实际 $ins")
        ins as PtzInstruction.Lens
        assertEquals(FocusDirection.NONE, ins.focus)
        assertEquals(IrisDirection.NONE, ins.iris)
        assertEquals(0, ins.focusSpeed)
        assertEquals(0, ins.irisSpeed)
    }

    @Test
    fun `FI — 光圈与聚焦可以组合下发(表A6 注1)`() {
        // 0x46 = 0x40 | bit2(光圈放大) | bit1(聚焦近)。整字节查表法会把这条判成未知 ——
        // 这正是必须按两条独立位轴解的原因。
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x46, 0x66, 0xCC, 0x00)
        val ins = PtzCmdDecoder.decodeInstruction(hex)
        assertTrue(ins is PtzInstruction.Lens, "0x46 期望 Lens,实际 $ins")
        ins as PtzInstruction.Lens
        assertEquals(FocusDirection.NEAR, ins.focus)
        assertEquals(IrisDirection.OPEN, ins.iris)
        assertEquals(0x66, ins.focusSpeed)
        assertEquals(0xCC, ins.irisSpeed)
    }

    @Test
    fun `FI — 同一轴两位同时置1 属非法,返回 null(表A6 注1)`() {
        // 0x4C = bit3+bit2 → 光圈既缩又放;0x43 = bit1+bit0 → 聚焦既近又远
        assertNull(
            PtzCmdDecoder.decodeInstruction(hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x4C, 0x10, 0x10, 0x00))
        )
        assertNull(
            PtzCmdDecoder.decodeInstruction(hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x43, 0x10, 0x00, 0x00))
        )
    }

    @Test
    fun `FI — bit5 或 bit4 置位不是本表描述的报文,返回 null`() {
        // 表 A.6 把该族高 4 位钉死为 0100B:bit5、bit4 必须为 0。
        assertNull(
            PtzCmdDecoder.decodeInstruction(hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x50, 0x10, 0x00, 0x00))
        )
        assertNull(
            PtzCmdDecoder.decodeInstruction(hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x60, 0x10, 0x00, 0x00))
        )
    }

    @Test
    fun `FI — 老接口 decode 只认 Motion,FI 一律返回 null`() {
        // 既有调用方把返回值当云台运动量用,不能让镜头动作混进去。
        assertNull(PtzCmdDecoder.decode("A50F014120000016"))
        assertNull(PtzCmdDecoder.decode("A50F014400200019"))
    }

    @Test
    fun `FI — Lens 转 PtzCommand 时云台三轴留空,供 HUD 复用同一类型`() {
        val lens = PtzCmdDecoder.decodeInstruction("A50F014620CC00E7") as PtzInstruction.Lens
        val cmd = lens.toPtzCommand()
        assertEquals(PanDirection.NONE, cmd.panDirection)
        assertEquals(TiltDirection.NONE, cmd.tiltDirection)
        assertEquals(ZoomDirection.NONE, cmd.zoomDirection)
        assertEquals(0, cmd.panSpeed)
        assertEquals(0, cmd.tiltSpeed)
        assertEquals(0, cmd.zoomSpeed)
        assertEquals(FocusDirection.NEAR, cmd.focusDirection)
        assertEquals(IrisDirection.OPEN, cmd.irisDirection)
        assertEquals(0x20, cmd.focusSpeed)
        assertEquals(0xCC, cmd.irisSpeed)
    }
}
