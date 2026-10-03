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
 *   B3=指令码,分五个子族:
 *     0x81/0x82/0x83 预置位 · 0x84~0x88 巡航 · 0x89/0x8A 扫描 · 0x8C/0x8D 辅助开关 → 整字节取值
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

    // ---------- 预置位 (GB-2022 附录 A.3.4,字节4(bytes[3]) = 0x81/0x82/0x83) ----------

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
        // 0x91:bit7=1 且不属于 0x81~0x8D(预置位/巡航/扫描/辅助开关),掉在标准没定义的空隙里;
        // 也不可能是 FI 族(表 A.6 把该族高 4 位钉死在 0100B,0x91 的 bit6=0)。
        // 原实现让这类字节落进 PTZ 按位拆解,凭空造出「右转 + 放大 + 聚焦近」三个动作。
        val hex = hex7ChecksumHex(0xA5, 0x0F, 0x01, 0x91, 0x32, 0x00, 0x80)
        assertNull(PtzCmdDecoder.decodeInstruction(hex))
    }

    // ---------- 辅助开关(GB/T 28181-2022 表 A.11:0x8C 开 / 0x8D 关)----------
    //
    // ⛔ 帧是**硬编码**的,不用 helper 现算 —— helper 和解码器一起错会互相印证(本仓
    //    `presetHex`/`cruiseHex` 都踩过)。下面四帧按表 A.11 手算:
    //    字节4=0x8C/0x8D、字节5=开关编号(标准注:1=雨刷),校验和 = 前 7 字节和 mod 256。
    //
    // ⛔⛔ 这一族曾经**被写成 0x89/0x8A**(扫描的码),于是平台点「开始扫描」设备去开了雨刷。
    //     下面三条用例就是那个回归的锚点:帧里的 0x8C/0x8D 一旦被改回 0x89/0x8A,它们必红。

    @Test
    fun `辅助开关 开雨刷 — 表A11 0x8C 字节5=1`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F018C01000042")
        assertTrue(ins is PtzInstruction.Aux, "expected Aux, got $ins")
        ins as PtzInstruction.Aux
        assertTrue(ins.on)
        assertEquals(1, ins.index)
        assertEquals(AuxFunction.Wiper, AuxFunction.fromIndex(ins.index))
    }

    @Test
    fun `辅助开关 关雨刷 — 表A11 0x8D 字节5=1`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F018D01000043")
        assertTrue(ins is PtzInstruction.Aux)
        ins as PtzInstruction.Aux
        assertFalse(ins.on)
        assertEquals(1, ins.index)
        assertEquals(AuxFunction.Wiper, AuxFunction.fromIndex(ins.index))
    }

    @Test
    fun `辅助开关 编号 2~5 不属国标 — 帧能解,但没有标准语义`() {
        // 字节5 是 `00H~FFH` 的**开放编号** ⇒ 帧本身合法,解码器仍要返回 Aux(不能拒收)。
        // 但 GB/T 28181 A.3.7(表 A.11)全节唯一一条语义注只写「取值为"1"表示雨刷控制」,
        // 编号 2~5 在标准里没有定义 ⇒ fromIndex 必须为 null。
        // ⛔ 不允许再把它们映射成"红外灯 / 加热 / 除雾 / 制冷"——那是厂商私有编号,
        //   2026-09-21 已从 AuxFunction 移除(见该枚举的 KDoc)。
        val frames = listOf(
            2 to "A50F018C02000043",
            3 to "A50F018C03000044",
            4 to "A50F018C04000045",
            5 to "A50F018C05000046",
        )
        for ((index, hex) in frames) {
            val ins = PtzCmdDecoder.decodeInstruction(hex)
            assertTrue(ins is PtzInstruction.Aux, "编号 $index 的帧应当仍被解成 Aux")
            ins as PtzInstruction.Aux
            assertEquals(index, ins.index)
            assertNull(AuxFunction.fromIndex(index), "编号 $index 在标准里没有语义")
        }
    }

    @Test
    fun `辅助开关 未知编号仍解码成功 由 dispatcher 决定语义`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F018C630000A4")
        assertTrue(ins is PtzInstruction.Aux)
        ins as PtzInstruction.Aux
        assertEquals(0x63, ins.index)
        assertNull(AuxFunction.fromIndex(0x63))  // 没有映射
    }

    // ---------- 自动扫描(GB/T 28181-2022 表 A.10:0x89 / 0x8A)----------
    //
    // ⛔ 前四帧是**平台真实下发的字节**,2026-09-20 从 `gb_sip_trace_message` 解密取得
    //    (平台 `PTZActionScan*` → `BuildExtendedPTZControlWithProfile`),不是按解码器的
    //    假设现算的 —— 这样"平台写出来的"和"设备读出来的"才是两件独立的事。
    //
    // ⛔⛔ `0x89` **一个指令码管三件事**,子动作在**字节6**(00 开始/01 左边界/02 右边界)。
    //     按"一个动作一个码"的直觉去写,会让三种动作全被解成同一种。

    @Test
    fun `扫描 开始 — 平台真实帧 0x89 字节6=00`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F01890000003E")
        // ⛔ 断言必须写在 `as` smart cast **之前**:一旦 `ins` 被缩窄成 Scan,`ins is Aux` 就是
        //    编译器能静态判定为 false 的恒假式(会报 "Check for instance is always 'false'"),
        //    断言看着绿,其实一个字节都没守。
        assertFalse(ins is PtzInstruction.Aux, "扫描帧不得被解成辅助开关")
        assertTrue(ins is PtzInstruction.Scan, "expected Scan, got $ins")
        ins as PtzInstruction.Scan
        assertEquals(ScanOp.START, ins.op)
        // ⭐ 组号 0 是**合法组号**(表 A.10 注1:00H~FFH),别照巡航族"组号 0 = 停止"去处理:
        //    平台默认就用 0 号组,把 0 当停止会让默认路径上的开始扫描永远不生效。
        assertEquals(0, ins.groupNum)
    }

    @Test
    fun `扫描 开始 带非零组号`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F01890100003F")
        assertTrue(ins is PtzInstruction.Scan)
        ins as PtzInstruction.Scan
        assertEquals(ScanOp.START, ins.op)
        assertEquals(1, ins.groupNum)
    }

    @Test
    fun `扫描 设左边界 — 0x89 字节6=01`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F01890001003F")
        assertTrue(ins is PtzInstruction.Scan)
        ins as PtzInstruction.Scan
        assertEquals(ScanOp.SET_LEFT_BOUNDARY, ins.op)
        assertEquals(0, ins.groupNum)
    }

    @Test
    fun `扫描 设右边界 — 0x89 字节6=02`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F018900020040")
        assertTrue(ins is PtzInstruction.Scan)
        ins as PtzInstruction.Scan
        assertEquals(ScanOp.SET_RIGHT_BOUNDARY, ins.op)
        assertEquals(0, ins.groupNum)
    }

    @Test
    fun `扫描 设速度 — 平台真实帧 0x8A 12位载荷`() {
        val ins = PtzCmdDecoder.decodeInstruction("A50F018A007800B7")
        assertTrue(ins is PtzInstruction.Scan)
        ins as PtzInstruction.Scan
        assertEquals(ScanOp.SET_SPEED, ins.op)
        assertEquals(0, ins.groupNum)
        assertEquals(120, ins.param)
    }

    @Test
    fun `扫描 速度高4位从字节7拼回 — 300 不能读成 44`() {
        // 与巡航 0x86 那个锚点(`A50F0186012C1078` → 300)同形:低 8 位在字节6(0x2C)、
        // 高 4 位在字节7 高半字节(0x1)。只取 bytes[5] 的实现在这里会读成 44。
        val ins = PtzCmdDecoder.decodeInstruction("A50F018A002C107B")
        assertTrue(ins is PtzInstruction.Scan)
        ins as PtzInstruction.Scan
        assertEquals(ScanOp.SET_SPEED, ins.op)
        assertEquals(300, ins.param)
    }

    @Test
    fun `扫描 未定义的子动作返回 null 而不是猜成开始扫描`() {
        // 字节6=03H 在表 A.10 里没有定义。猜成"开始扫描"是最坏的一种猜法 ——
        // 球机会真的自己转起来,而且平台侧看起来一切正常。
        assertNull(PtzCmdDecoder.decodeInstruction("A50F018900030041"))
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
