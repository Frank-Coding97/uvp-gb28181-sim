package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 §F.3 PTZ 命令解码.
 *
 * PTZ 命令在 MANSCDP DeviceControl 消息体里以 8 字节(16 个 hex 字符)出现:
 *
 *   <PTZCmd>A50F0102320000DA</PTZCmd>
 *
 * 8 字节布局(byte3 高 4 位决定子族):
 *
 * | byte | 含义                                                          |
 * |------|---------------------------------------------------------------|
 * | 0    | 帧头 0xA5                                                     |
 * | 1    | 版本与校验位 0x0F                                            |
 * | 2    | 地址低 8 位 (设备地址,模拟器固定 0x01)                       |
 * | 3    | 指令码:                                                       |
 * |      |   高 4 位 = 0x0  → 方向控制(Motion):                        |
 * |      |     bit0 = 右   bit1 = 左   bit2 = 下   bit3 = 上            |
 * |      |     bit4 = zoom in   bit5 = zoom out                        |
 * |      |   高 4 位 = 0x8  → 预置位(Preset):                          |
 * |      |     0x81 SetPreset / 0x82 CallPreset / 0x83 DelPreset       |
 * |      |     0x84-0x88 巡航(本轮不解,返回 null)                     |
 * |      |     0x89-0x8A Aux                                           |
 * | 4    | Motion: Pan 速度 0-255 / Preset: 编号                        |
 * | 5    | Tilt 速度 0-255                                              |
 * | 6    | 高 4 位 = Zoom 速度 0-15                                     |
 * | 7    | checksum = (B0+B1+B2+B3+B4+B5+B6) mod 256                    |
 *
 * 设计:`decode()` 老接口只返回 Motion(预置位返回 null,向后兼容既有调用方);
 * `decodeInstruction()` 新接口走 sealed `PtzInstruction` 走 dispatcher when 分支.
 */
object PtzCmdDecoder {

    private const val FRAME_HEAD = 0xA5
    private const val VERSION = 0x0F

    /** 老接口:仅返回 Motion,预置位/巡航/Aux 一律 null. 不破坏既有 12 个测试与外部调用. */
    fun decode(hexString: String): PtzCommand? {
        return (decodeInstruction(hexString) as? PtzInstruction.Motion)?.cmd
    }

    fun decode(bytes: ByteArray): PtzCommand? {
        return (decodeInstruction(bytes) as? PtzInstruction.Motion)?.cmd
    }

    /** 新接口:返回 sealed `PtzInstruction`,dispatcher 走 when 分发. */
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
            opCode == 0x81 || opCode == 0x82 || opCode == 0x83 -> decodePreset(bytes, opCode)
            // GB-2022 §F.3 byte3 = 0x89 / 0x8A 辅助开关 (Aux On/Off)
            // byte4 = aux 编号: 1=雨刷 / 2=红外灯 / 3=加热 / 4=除雾 / 5=制冷(海康/大华事实标准)
            opCode == 0x89 || opCode == 0x8A -> decodeAux(bytes, opCode)
            // 巡航控制(GB-2022 §F.3 byte3 = 0x84-0x88)
            // 0x84=SetCruisePoint / 0x85=DelCruisePoint / 0x86=CruiseSpeed / 0x87=CruiseTime / 0x88=StartCruise
            opCode in 0x84..0x88 -> decodeCruise(bytes, opCode)
            // 其他全走 Motion bit 拆解(包括 byte3 bit6=Focus Far / bit7=Focus Near
            //                          + byte3 = 0x44/0x48 行业 Iris Open/Close)
            else -> PtzInstruction.Motion(decodeMotion(bytes, opCode))
        }
    }

    private fun decodePreset(bytes: ByteArray, opCode: Int): PtzInstruction.Preset {
        val op = when (opCode) {
            0x81 -> PresetOp.SET
            0x82 -> PresetOp.CALL
            else -> PresetOp.DEL  // 0x83
        }
        val idx = bytes[4].toInt() and 0xFF
        return PtzInstruction.Preset(op, idx)
    }

    private fun decodeAux(bytes: ByteArray, opCode: Int): PtzInstruction.Aux {
        val on = opCode == 0x89  // 0x89=on / 0x8A=off
        val auxIndex = bytes[4].toInt() and 0xFF
        return PtzInstruction.Aux(on, auxIndex)
    }

    /** 巡航子命令解码. byte4 = 巡航号(1-N),byte5 = 预置位号 / 速度 / 时长(取决于 op). */
    private fun decodeCruise(bytes: ByteArray, opCode: Int): PtzInstruction.Cruise {
        val op = when (opCode) {
            0x84 -> CruiseOp.SET_POINT       // byte4=巡航号 byte5=预置位号
            0x85 -> CruiseOp.DEL_POINT       // byte4=巡航号 byte5=预置位号
            0x86 -> CruiseOp.SET_SPEED       // byte4=巡航号 byte5+byte6 高=速度
            0x87 -> CruiseOp.SET_DWELL_TIME  // byte4=巡航号 byte5+byte6 高=停留时长
            else -> CruiseOp.START           // 0x88: byte4=巡航号
        }
        val trackNum = bytes[4].toInt() and 0xFF
        val param = bytes[5].toInt() and 0xFF
        return PtzInstruction.Cruise(op, trackNum, param)
    }

    private fun decodeMotion(bytes: ByteArray, opCode: Int): PtzCommand {
        val panSpeed = bytes[4].toInt() and 0xFF
        val tiltSpeed = bytes[5].toInt() and 0xFF
        val zoomSpeed = (bytes[6].toInt() and 0xFF) ushr 4
        val focusOrIrisSpeed = bytes[6].toInt() and 0x0F  // byte6 低 4 位通常是 Focus/Iris 速度

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
        // Focus: byte3 bit6 = Focus Far(远焦) / bit7 = Focus Near(近焦)
        // 注意:整字节匹配 0x81/0x82/0x83 / 0x89/0x8A / 0x84-0x88 已在外层 decodeInstruction 拦截,
        // 走到这里的 opCode 不会是预置位/Aux/Cruise 整字节值,bit7 单独置位是合法 Focus Near.
        val focusDir = when {
            (opCode and 0x80) != 0 -> FocusDirection.NEAR
            (opCode and 0x40) != 0 -> FocusDirection.FAR
            else -> FocusDirection.NONE
        }
        // Iris(光圈):各家 byte3 编码不一致,模拟器约定 0x44=Iris Open / 0x48=Iris Close
        // (不在 bit 拆解范围,需要外层精确匹配整字节)
        // 注:这里走到 decodeMotion 的 0x44/0x48 也会被 bit 拆解成 Focus(0x44=Focus Far + bit2=down,
        // 0x48=Focus Far + bit3=up),实际语义需要平台明确;整字节匹配在外层处理.
        val irisDir = IrisDirection.NONE  // 由外层精确字节判别(见 dispatcher)
        return PtzCommand(
            panDirection = panDir,
            tiltDirection = tiltDir,
            zoomDirection = zoomDir,
            focusDirection = focusDir,
            irisDirection = irisDir,
            panSpeed = if (panDir == PanDirection.NONE) 0 else panSpeed,
            tiltSpeed = if (tiltDir == TiltDirection.NONE) 0 else tiltSpeed,
            zoomSpeed = if (zoomDir == ZoomDirection.NONE) 0 else zoomSpeed,
            focusSpeed = if (focusDir == FocusDirection.NONE) 0 else focusOrIrisSpeed,
            irisSpeed = 0,
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
    data class Motion(val cmd: PtzCommand) : PtzInstruction()
    /** 预置位 CRUD (byte3 = 0x81/0x82/0x83) */
    data class Preset(val op: PresetOp, val index: Int) : PtzInstruction()
    /** 辅助开关 (byte3 = 0x89/0x8A,byte4 = aux 编号) — 雨刷/红外灯/加热/除雾/制冷. */
    data class Aux(val on: Boolean, val index: Int) : PtzInstruction()
    /** 巡航 (byte3 = 0x84-0x88,byte4 = 巡航号,byte5 = 参数). */
    data class Cruise(val op: CruiseOp, val trackNum: Int, val param: Int) : PtzInstruction()
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

enum class PanDirection { LEFT, RIGHT, NONE }
enum class TiltDirection { UP, DOWN, NONE }
enum class ZoomDirection { IN, OUT, NONE }
enum class FocusDirection { NEAR, FAR, NONE }
enum class IrisDirection { OPEN, CLOSE, NONE }
