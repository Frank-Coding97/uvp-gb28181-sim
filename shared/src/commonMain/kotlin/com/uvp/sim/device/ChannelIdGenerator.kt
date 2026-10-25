package com.uvp.sim.device

/**
 * 基于 deviceId 派生通道编码，遵循 GB/T 28181 的 20 位编码结构与类型段语义。
 *
 * 编码结构（GB/T 28181 §5.2，20 位）：
 * ```
 *   中心编码(8) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(6)
 * ```
 * 设备与通道的差别**只在类型段**：设备 `105`、视频通道 `132`、报警通道 `134`。
 *
 * ⛔ 历史实现是「deviceId 前 13 位 + 固定后 7 位」，把设备的 `105` 类型段原样抄进了
 * 通道编码 —— 平台按类型段校验时会认不出这是通道（EasyGBS 上表现为「只显示设备自身
 * 一条」）。2026-10-25 修正为按类型段重新拼装。
 *
 * 新规则（每台设备唯一、同设备内各通道唯一）：
 * ```
 *   deviceId.take(10)   中心 + 行业，与设备一致
 *   + "132"/"134"       类型段
 *   + 通道号(1 位)      1=后置 2=前置 1=报警（报警靠 134 与视频区分）
 *   + deviceId 后 6 位  沿用设备序号的哈希尾巴，保证跨设备不撞号
 * ```
 * 例（deviceId = 34020000001057079864）：
 * - 后置：34020000001321079864
 * - 前置：34020000001322079864
 * - 报警：34020000001341079864
 *
 * ⚠️ 已保存到配置里的通道 ID **不会**被重新派生（配置是权威），本规则只影响新装 /
 * 重置后的默认值；老配置校正见 [isLegacyDerived]。
 */
object ChannelIdGenerator {

    /** 视频通道类型段（132 = 视频通道）。 */
    private const val VIDEO_TYPE = "132"

    /** 报警通道类型段（134 = 报警通道）。 */
    private const val ALARM_TYPE = "134"

    /** 后置摄像头通道号。 */
    private const val CHANNEL_NO_BACK = "1"

    /** 前置摄像头通道号。 */
    private const val CHANNEL_NO_FRONT = "2"

    /** 报警通道号（类型段已用 134 与视频区分，这里复用 1）。 */
    private const val CHANNEL_NO_ALARM = "1"

    /**
     * 派生视频通道编码（后置摄像头）。
     *
     * @param deviceId 设备编码（20 位十进制）
     * @return 20 位视频通道编码，类型段固定 132
     */
    fun deriveVideoChannelId(deviceId: String): String =
        derive(deviceId, VIDEO_TYPE, CHANNEL_NO_BACK)

    /**
     * 派生前置摄像头通道编码。
     *
     * @param deviceId 设备编码（20 位十进制）
     * @return 20 位视频通道编码，类型段固定 132，通道号 2
     */
    fun deriveFrontChannelId(deviceId: String): String =
        derive(deviceId, VIDEO_TYPE, CHANNEL_NO_FRONT)

    /**
     * 派生报警通道编码。
     *
     * @param deviceId 设备编码（20 位十进制）
     * @return 20 位报警通道编码，类型段固定 134
     */
    fun deriveAlarmChannelId(deviceId: String): String =
        derive(deviceId, ALARM_TYPE, CHANNEL_NO_ALARM)

    /**
     * 判断某个通道编码是否是**旧规则**派生出来的（`前 13 位 + 固定后 7 位`）。
     *
     * 只用于配置迁移：老版本装出来的默认值带着设备的 105 类型段，平台认不出；
     * 升级时若通道 ID 与旧规则**逐字节相同**才重算，用户手改过的一律不动。
     */
    fun isLegacyDerived(deviceId: String, channelId: String): Boolean {
        if (deviceId.length != 20 || channelId.length != 20) return false
        val prefix = deviceId.take(13)
        return channelId == prefix + "0000010" ||
            channelId == prefix + "0000020" ||
            channelId == prefix + "0000001"
    }

    /**
     * 判定通道编码的类型段是否为合法的通道类型（132 / 134）。
     *
     * 平台侧普遍按第 11–13 位判断「这是不是一个通道」，设备类型段（105）会被忽略。
     */
    fun hasChannelTypeSegment(channelId: String): Boolean {
        if (channelId.length != 20) return false
        val type = channelId.substring(10, 13)
        return type == VIDEO_TYPE || type == ALARM_TYPE
    }

    private fun derive(deviceId: String, typeCode: String, channelNo: String): String {
        require(deviceId.length == 20) { "deviceId must be 20 digits, got ${deviceId.length}" }
        require(deviceId.all { it in '0'..'9' }) { "deviceId must be digits only, got $deviceId" }
        return deviceId.take(10) + typeCode + channelNo + deviceId.takeLast(6)
    }
}
