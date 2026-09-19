package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 附录 A 前端设备控制的 8 字节协议解码。
 *
 * PTZ 命令在 MANSCDP DeviceControl 消息体里以 8 字节(16 个 hex 字符)出现:
 *
 *   <PTZCmd>A50F0102320000DA</PTZCmd>
 *
 * 8 字节布局(表 A.1 指令格式):
 *
 * | 字节 | 含义                                                          |
 * |------|---------------------------------------------------------------|
 * | 1    | 帧头 0xA5                                                     |
 * | 2    | 组合码1:高 4 位版本(0x0),低 4 位校验位(0xF)                  |
 * | 3    | 地址低 8 位(模拟器固定 0x01)                                  |
 * | 4    | **指令码** —— 决定子族,见下                                    |
 * | 5    | 数据1                                                         |
 * | 6    | 数据2                                                         |
 * | 7    | 组合码2:高 4 位 = 数据3(变倍速度),低 4 位 = 地址高 4 位        |
 * | 8    | 校验码 = (字节1..7 之和) mod 256                              |
 *
 * **字节4 是整个解码的分叉点,共四个子族**,必须按子族分别解,不能混:
 *
 * ```
 * 族          字节4 取值              识别方式            节
 * 预置位      0x81 / 0x82 / 0x83      整字节              表 A.7
 * 巡航        0x84 ~ 0x88             整字节              表 A.8
 * 扫描/辅助   0x89 / 0x8A             整字节              ——
 * FI(聚焦/光圈) 0x40 ~ 0x4F            bit7=0 且 bit6=1    表 A.6
 * PTZ(方向/变倍) 0x00 ~ 0x3F           bit7=0 且 bit6=0    表 A.5
 * ```
 *
 * 表 A.5 PTZ 族(字节4 = `0 0 | bit5 bit4 | bit3 bit2 | bit1 bit0`):
 * ```
 *   bit5/bit4 = 缩小 / 放大     (0x20 / 0x10)
 *   bit3/bit2 = 上   / 下       (0x08 / 0x04)
 *   bit1/bit0 = 左   / 右       (0x02 / 0x01)
 * ```
 * 字节5 = 水平速度,字节6 = 垂直速度,字节7 高 4 位 = 变倍速度。全零 = 停止。
 *
 * 表 A.6 FI 族(字节4 = `0 1 0 0 | bit3 bit2 | bit1 bit0`):
 * ```
 *   bit3/bit2 = 光圈缩小 / 光圈放大   (0x48 / 0x44)
 *   bit1/bit0 = 聚焦近   / 聚焦远     (0x42 / 0x41)
 * ```
 * 字节5 = **聚焦**速度,字节6 = **光圈**速度(两条轴各占一整字节,不对称是标准如此)。
 * 0x40 = 该族停止。
 *
 * ⚠️ 表 A.6 的表下注 1:「**光圈控制和聚焦控制的指令可以组合**」——所以 FI 族必须按
 * 两条独立的位轴解,`0x46`(光圈放大+聚焦近)是完全合法的报文。整字节查表法会把它判成未知。
 * 同一轴内的两位「不应同时为 1」(既开又关),那种字节按非法拒绝。
 *
 * ⭐ **整字节三族的参数位置不一样,别套用**(2026-09-16 真机联调踩到):
 *
 * | 族 | 字节4 | 字节5(数据1) | 字节6(数据2) | 字节7 高4位 |
 * |---|---|---|---|---|
 * | 预置位 | 0x81/0x82/0x83 | **固定 0x00** | **预置位编号** | 0 |
 * | 巡航 | 0x84~0x88 | 巡航组号 | 预置位号 / 速度·时长的低 8 位 | 速度·时长的高 4 位 |
 * | 扫描/辅助 | 0x89/0x8A | 扫描组号 | 指令字节(00 开始/01 左边界/02 右边界) | 速度高 4 位 |
 *
 * 平台侧 `BuildExtendedPTZControlWithProfile` 也是这么写的(预置位写 `parameter2`)。
 * 标准样例 `A50F018100010037` = 新增 1 号预置点,可直接当回归锚点。
 *
 * 设计:`decode()` 老接口只返回 Motion(预置位返回 null,向后兼容既有调用方);
 * `decodeInstruction()` 新接口走 sealed `PtzInstruction` 走 dispatcher when 分支.
 */
object PtzCmdDecoder {

    private const val FRAME_HEAD = 0xA5
    private const val VERSION = 0x0F

    /** 老接口:仅返回 Motion,预置位/巡航/辅助/FI 一律 null. 不破坏既有调用方。 */
    fun decode(hexString: String): PtzCommand? {
        return (decodeInstruction(hexString) as? PtzInstruction.Motion)?.cmd
    }

    fun decode(bytes: ByteArray): PtzCommand? {
        return (decodeInstruction(bytes) as? PtzInstruction.Motion)?.cmd
    }

    /** 新接口:返回 sealed `PtzInstruction`,dispatcher 走 when 分发。 */
    fun decodeInstruction(hexString: String): PtzInstruction? {
        val bytes = hexToBytes(hexString) ?: return null
        return decodeInstruction(bytes)
    }

    fun decodeInstruction(bytes: ByteArray): PtzInstruction? {
        if (bytes.size != 8) return null
        if ((bytes[0].toInt() and 0xFF) != FRAME_HEAD) return null
        if ((bytes[1].toInt() and 0xFF) != VERSION) return null
        if (!verifyChecksum(bytes)) return null

        val opCode = bytes[3].toInt() and 0xFF
        return when {
            // 整字节子族:这三个族用**整个字节取值**寻址,不按位拆解,所以必须先判掉。
            opCode == 0x81 || opCode == 0x82 || opCode == 0x83 -> decodePreset(bytes, opCode)
            opCode in 0x84..0x88 -> decodeCruise(bytes, opCode)
            opCode == 0x89 || opCode == 0x8A -> decodeAux(bytes, opCode)

            // FI 族(表 A.6):bit7=0 且 bit6=1。
            // ⚠️ 必须排在 PTZ 族之前判 —— 这族字节的 bit3/bit2 看起来像 PTZ 的「上/下」、
            // bit6 看起来像某些厂商文档里的「聚焦远」,一旦落进按位拆解 PTZ 的分支,
            // 「平台调光圈」就会变成「云台跟着仰俯」。
            (opCode and 0xC0) == 0x40 -> decodeLens(opCode, bytes)

            // PTZ 族(表 A.5):bit7=0 且 bit6=0。
            (opCode and 0xC0) == 0x00 -> PtzInstruction.Motion(decodeMotion(bytes, opCode))

            // bit7=1 但不在 0x81~0x8A 之间:标准未定义该字节的语义。
            // 返回 null(dispatcher 走 200 OK 的 ack 兜底),不猜成某个动作。
            else -> null
        }
    }

    private fun decodePreset(bytes: ByteArray, opCode: Int): PtzInstruction.Preset {
        val op = when (opCode) {
            0x81 -> PresetOp.SET
            0x82 -> PresetOp.CALL
            else -> PresetOp.DEL  // 0x83
        }
        // ⛔ 预置位编号在**字节6**(数据2),不在字节5。这族是**唯一**把参数放数据2的:
        //   预置位/巡航/扫描三族的「组号」都在字节5,但预置位指令的字节5**固定为 0x00**,
        //   编号在字节6。
        //   2026-09-16 真机联调抓到:原实现读 `bytes[4]`,于是平台下发的
        //   `A50F018100010037`(置1号预置位)被解成编号 0 → 越界丢弃 → 预置位表永远为空,
        //   连带看守位自动归位(需要 presets[PresetIndex] 存在)永远不触发。
        //   现场/权威资料里的标准样例就是这一帧:
        //     `新增1号预置点: A50F018100010037`
        //   「字节5默认为00,字节6为预置点位」——平台侧 `BuildExtendedPTZControlWithProfile`
        //   也是写 `parameter2`(字节6),两侧对齐。
        val idx = bytes[5].toInt() and 0xFF
        return PtzInstruction.Preset(op, idx)
    }

    private fun decodeAux(bytes: ByteArray, opCode: Int): PtzInstruction.Aux {
        val on = opCode == 0x89  // 0x89=on / 0x8A=off
        val auxIndex = bytes[4].toInt() and 0xFF
        return PtzInstruction.Aux(on, auxIndex)
    }

    /**
     * 巡航子命令解码。
     *
     * 字节位(见类头表):
     * ```
     *   字节5 = 巡航组号
     *   字节6 = 预置位号(0x84/0x85) | 速度·时长的**低 8 位**(0x86/0x87)
     *   字节7 高 4 位 = 速度·时长的**高 4 位**(仅 0x86/0x87)
     * ```
     *
     * ⛔ 0x86/0x87 的参数是 **12 位**(标准:01H-FFFH),必须把字节7 的高半字节拼回来。
     *    这里原来只取 `bytes[5]`(低 8 位)就把高 4 位丢了 —— 平台发速度 300(0x12C)
     *    时字节6=0x2C、字节7 高半字节=0x1,设备读到的是 **44**。类头表格其实一直写着
     *    "低 8 位 / 高 4 位",是实现没照着做。0x84/0x85 的字节7 是 0,不受影响。
     */
    private fun decodeCruise(bytes: ByteArray, opCode: Int): PtzInstruction.Cruise {
        val op = when (opCode) {
            0x84 -> CruiseOp.SET_POINT       // 字节5=巡航号 字节6=预置位号
            0x85 -> CruiseOp.DEL_POINT       // 字节5=巡航号 字节6=预置位号
            0x86 -> CruiseOp.SET_SPEED       // 字节5=巡航号 字节6 低8位 + 字节7 高4位 = 速度
            0x87 -> CruiseOp.SET_DWELL_TIME  // 字节5=巡航号 字节6 低8位 + 字节7 高4位 = 停留时长
            else -> CruiseOp.START           // 0x88: 字节5=巡航号
        }
        val trackNum = bytes[4].toInt() and 0xFF
        val param = when (op) {
            // 12 位重组:低 8 位在字节6,高 4 位在字节7 的高半字节
            CruiseOp.SET_SPEED, CruiseOp.SET_DWELL_TIME ->
                (bytes[5].toInt() and 0xFF) or (((bytes[6].toInt() and 0xF0) shr 4) shl 8)
            // 预置位号是整字节,高半字节不参与
            else -> bytes[5].toInt() and 0xFF
        }
        return PtzInstruction.Cruise(op, trackNum, param)
    }

    /**
     * FI 指令族(表 A.6) —— 聚焦与光圈。
     *
     * 字节4 的高 4 位标准钉死为 `0100B`:bit7=0、bit6=1 是本族标识,bit5、bit4 规定为 0。
     * 低 4 位是**两条彼此独立的控制轴**:
     *
     * ```
     *   bit3 = 光圈缩小        bit2 = 光圈放大
     *   bit1 = 聚焦近          bit0 = 聚焦远
     * ```
     *
     * 表下注 1 明确「光圈控制和聚焦控制的指令可以组合」,所以两轴分别解:0x46
     * (光圈放大 + 聚焦近)是合法报文,原实现按整字节查表会把它判成未知。
     * 同一轴内两位同时置 1(既开又关)违反注 1 的「不应同时为 1」,按非法拒绝。
     *
     * 速度字节也是两条轴**各占一整字节**(注 2/注 3):字节5 = 聚焦速度、字节6 = 光圈速度,
     * 范围均为 00H~FFH。别把某一轴的速度塞进另一轴的字节 —— 不对称是标准本身如此。
     */
    private fun decodeLens(opCode: Int, bytes: ByteArray): PtzInstruction? {
        // bit5 / bit4 标准规定为 0;置起来就不是本表描述的报文,不猜语义。
        if ((opCode and 0x30) != 0) return null

        val irisClose = (opCode and 0x08) != 0
        val irisOpen = (opCode and 0x04) != 0
        val focusNear = (opCode and 0x02) != 0
        val focusFar = (opCode and 0x01) != 0

        // 表下注 1:同一轴内的两位不应同时为 1。
        if (irisClose && irisOpen) return null
        if (focusNear && focusFar) return null

        val focus = when {
            focusNear -> FocusDirection.NEAR
            focusFar -> FocusDirection.FAR
            else -> FocusDirection.NONE
        }
        val iris = when {
            irisClose -> IrisDirection.CLOSE
            irisOpen -> IrisDirection.OPEN
            else -> IrisDirection.NONE
        }
        return PtzInstruction.Lens(
            focus = focus,
            // 字节5 = 聚焦速度(注 2)。该轴停止时读到的速度无意义,归一成 0。
            focusSpeed = if (focus == FocusDirection.NONE) 0 else bytes[4].toInt() and 0xFF,
            iris = iris,
            // 字节6 = 光圈速度(注 3)。
            irisSpeed = if (iris == IrisDirection.NONE) 0 else bytes[5].toInt() and 0xFF,
        )
    }

    /**
     * PTZ 指令族(表 A.5) —— 方向与变倍。
     *
     * 字节4 = `0 0 | bit5 bit4 | bit3 bit2 | bit1 bit0`:
     * ```
     *   bit5/bit4 = 缩小 / 放大      0x20 / 0x10
     *   bit3/bit2 = 上   / 下        0x08 / 0x04
     *   bit1/bit0 = 左   / 右        0x02 / 0x01
     * ```
     * 字节5 = 水平速度、字节6 = 垂直速度、字节7 高 4 位 = 变倍速度。
     *
     * ⚠️ 本族**不含聚焦/光圈** —— 那两轴住在 FI 族(见 [decodeLens])。原实现把 bit7/bit6
     * 当成 Focus Near/Far、又把字节6 的低 4 位当聚焦速度,于是 0x44/0x48(光圈放大/缩小)
     * 被拆成「聚焦远 + 下/上」,表现为**平台一调光圈、云台就跟着仰俯**。
     */
    private fun decodeMotion(bytes: ByteArray, opCode: Int): PtzCommand {
        val panSpeed = bytes[4].toInt() and 0xFF
        val tiltSpeed = bytes[5].toInt() and 0xFF
        val zoomSpeed = (bytes[6].toInt() and 0xFF) ushr 4

        val panDir = when {
            (opCode and 0x02) != 0 -> PanDirection.LEFT
            (opCode and 0x01) != 0 -> PanDirection.RIGHT
            else -> PanDirection.NONE
        }
        val tiltDir = when {
            (opCode and 0x08) != 0 -> TiltDirection.UP
            (opCode and 0x04) != 0 -> TiltDirection.DOWN
            else -> TiltDirection.NONE
        }
        val zoomDir = when {
            (opCode and 0x10) != 0 -> ZoomDirection.IN
            (opCode and 0x20) != 0 -> ZoomDirection.OUT
            else -> ZoomDirection.NONE
        }
        return PtzCommand(
            panDirection = panDir,
            tiltDirection = tiltDir,
            zoomDirection = zoomDir,
            panSpeed = if (panDir == PanDirection.NONE) 0 else panSpeed,
            tiltSpeed = if (tiltDir == TiltDirection.NONE) 0 else tiltSpeed,
            zoomSpeed = if (zoomDir == ZoomDirection.NONE) 0 else zoomSpeed,
        )
    }

    fun verifyChecksum(bytes: ByteArray): Boolean {
        if (bytes.size != 8) return false
        var sum = 0
        for (i in 0..6) sum += bytes[i].toInt() and 0xFF
        return (sum and 0xFF) == (bytes[7].toInt() and 0xFF)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length != 16) return null
        val out = ByteArray(8)
        for (i in 0 until 8) {
            val hi = hexDigit(hex[i * 2]) ?: return null
            val lo = hexDigit(hex[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}

/** PTZ 8 字节命令解码后的语义. */
sealed class PtzInstruction {
    /** PTZ 族(表 A.5):方向 + 变倍。 */
    data class Motion(val cmd: PtzCommand) : PtzInstruction()

    /** 预置位 CRUD (字节4 = 0x81/0x82/0x83) */
    data class Preset(val op: PresetOp, val index: Int) : PtzInstruction()

    /** 辅助开关 (字节4 = 0x89/0x8A,字节5 = aux 编号) — 雨刷/红外灯/加热/除雾/制冷. */
    data class Aux(val on: Boolean, val index: Int) : PtzInstruction()

    /** 巡航 (字节4 = 0x84-0x88,字节5 = 巡航号,字节6 = 参数). */
    data class Cruise(val op: CruiseOp, val trackNum: Int, val param: Int) : PtzInstruction()

    /**
     * FI 族(表 A.6):聚焦 + 光圈。
     *
     * 两轴可能**同时有效**(表下注 1 允许组合下发,如 0x46),也各自可能为 NONE
     * (只动一条轴)。全 NONE 即 0x40,是该族的停止指令。
     */
    data class Lens(
        val focus: FocusDirection,
        val focusSpeed: Int,
        val iris: IrisDirection,
        val irisSpeed: Int,
    ) : PtzInstruction() {
        /**
         * 渲染层([com.uvp.sim.domain.LastDeviceCommand.ptz] / HUD)只认 [PtzCommand]。
         * FI 族不涉及云台三轴,那三个方向留 NONE、速度留 0,只搬聚焦/光圈两条轴,
         * 这样 UI 契约不用为一个新族再分一套类型。
         */
        fun toPtzCommand(): PtzCommand = PtzCommand(
            panDirection = PanDirection.NONE,
            tiltDirection = TiltDirection.NONE,
            zoomDirection = ZoomDirection.NONE,
            focusDirection = focus,
            irisDirection = iris,
            panSpeed = 0,
            tiltSpeed = 0,
            zoomSpeed = 0,
            focusSpeed = focusSpeed,
            irisSpeed = irisSpeed,
        )
    }
}

enum class PresetOp { SET, CALL, DEL }

/** 巡航子操作. */
enum class CruiseOp { SET_POINT, DEL_POINT, SET_SPEED, SET_DWELL_TIME, START }

/** 辅助控制编号映射(海康/大华行业事实标准). */
enum class AuxFunction(val index: Int, val displayName: String) {
    Wiper(1, "雨刷"),
    InfraredLight(2, "红外灯"),
    Heater(3, "加热"),
    Defog(4, "除雾"),
    Cooler(5, "制冷");

    companion object {
        fun fromIndex(idx: Int): AuxFunction? = entries.firstOrNull { it.index == idx }
    }
}

data class PtzCommand(
    val panDirection: PanDirection,
    val tiltDirection: TiltDirection,
    val zoomDirection: ZoomDirection,
    val focusDirection: FocusDirection = FocusDirection.NONE,
    val irisDirection: IrisDirection = IrisDirection.NONE,
    val panSpeed: Int,
    val tiltSpeed: Int,
    val zoomSpeed: Int,
    val focusSpeed: Int = 0,
    val irisSpeed: Int = 0,
)

/**
 * 是否是一条**停止**指令 —— 第 4~7 字节全零。
 *
 * 标准里**没有给出专门的「停止巡航」指令码**：巡航与扫描都用这条全零帧停（与方向族同一个
 * 停止码，见类头那张整字节三族的对照表；控制层 `0x88` 只定义"开始巡航 + 组号"）。
 * 平台侧 `PTZActionCruiseStop` / `PTZActionScanStop` 编出来的正是这一帧（其余字段留零），
 * 所以设备侧只能靠"是不是全零"来区分「开始动作」和「停动作」。
 *
 * ⚠️ 判据用的是**解码后的语义**而不是原始字节：`decodeMotion` 已经把"方向为 NONE"的那些轴
 * 的速度归一成 0，两者等价；用语义字段的好处是这个谓词跟着表 A.5 的语义走，
 * 而不是跟着某一条报文的字节排布走。
 */
internal val PtzCommand.isFullStop: Boolean
    get() = panDirection == PanDirection.NONE && tiltDirection == TiltDirection.NONE &&
        zoomDirection == ZoomDirection.NONE && focusDirection == FocusDirection.NONE &&
        irisDirection == IrisDirection.NONE &&
        panSpeed == 0 && tiltSpeed == 0 && zoomSpeed == 0 &&
        focusSpeed == 0 && irisSpeed == 0

enum class PanDirection { LEFT, RIGHT, NONE }
enum class TiltDirection { UP, DOWN, NONE }
enum class ZoomDirection { IN, OUT, NONE }
enum class FocusDirection { NEAR, FAR, NONE }
enum class IrisDirection { OPEN, CLOSE, NONE }
