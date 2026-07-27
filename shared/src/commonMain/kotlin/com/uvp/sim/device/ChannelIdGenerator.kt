package com.uvp.sim.device

/**
 * 基于 deviceId 派生通道编码，遵循 GB/T 28181-2016 §5.2.1 通道编码规范。
 *
 * 格式：前 13 位取自 deviceId（中心编码8+行业2+类型3），后 7 位为通道类型+序号。
 *
 * 示例（deviceId = "34020000001320000001"）:
 * - videoChannelId:  34020000001320000010（后 7 位 = 类型131+序号0001 → 简化为 0000010）
 * - frontChannelId:  34020000001320000020（后 7 位 = 0000020）
 * - alarmChannelId:  34020000001340000001（后 7 位 = 0000001）
 *
 * 注意：本实现不严格遵守国标通道编码分段语义，只保证唯一性 + 平台兼容性。
 */
object ChannelIdGenerator {

    /**
     * 派生视频通道编码（后置摄像头）。
     *
     * @param deviceId 设备编码（20 位）
     * @return 20 位视频通道编码，前 13 位同 deviceId，后 7 位为 "0000010"
     */
    fun deriveVideoChannelId(deviceId: String): String {
        require(deviceId.length == 20) { "deviceId must be 20 digits, got ${deviceId.length}" }
        return deviceId.take(13) + "0000010"
    }

    /**
     * 派生前置摄像头通道编码。
     *
     * @param deviceId 设备编码（20 位）
     * @return 20 位前置通道编码，前 13 位同 deviceId，后 7 位为 "0000020"
     */
    fun deriveFrontChannelId(deviceId: String): String {
        require(deviceId.length == 20) { "deviceId must be 20 digits, got ${deviceId.length}" }
        return deviceId.take(13) + "0000020"
    }

    /**
     * 派生报警通道编码。
     *
     * @param deviceId 设备编码（20 位）
     * @return 20 位报警通道编码，前 13 位同 deviceId，后 7 位为 "0000001"
     */
    fun deriveAlarmChannelId(deviceId: String): String {
        require(deviceId.length == 20) { "deviceId must be 20 digits, got ${deviceId.length}" }
        return deviceId.take(13) + "0000001"
    }
}
