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
            // byte3 高位置位但非 0x81-0x83: 巡航 / Aux / 扩展位,本轮不解析
            opCode >= 0x80 -> null
            // 0x00-0x3F 方向位组合(bit0=右 / bit1=左 / bit2=下 / bit3=上 / bit4=zoom in / bit5=zoom out)
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
            zoomSpeed = if (zoomDir == ZoomDirection.NONE) 0 else zoomSpeed
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

/** PTZ 8 字节命令解码后的语义,Motion 走方向控制 / Preset 走预置位 CRUD. */
sealed class PtzInstruction {
    data class Motion(val cmd: PtzCommand) : PtzInstruction()
    data class Preset(val op: PresetOp, val index: Int) : PtzInstruction()
}

enum class PresetOp { SET, CALL, DEL }

data class PtzCommand(
    val panDirection: PanDirection,
    val tiltDirection: TiltDirection,
    val zoomDirection: ZoomDirection,
    val panSpeed: Int,
    val tiltSpeed: Int,
    val zoomSpeed: Int,
)

enum class PanDirection { LEFT, RIGHT, NONE }
enum class TiltDirection { UP, DOWN, NONE }
enum class ZoomDirection { IN, OUT, NONE }
