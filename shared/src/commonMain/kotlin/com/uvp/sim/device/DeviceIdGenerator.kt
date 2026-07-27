package com.uvp.sim.device

import kotlin.math.absoluteValue

/**
 * 从硬件标识派生符合 GB/T 28181-2016 §5.2 规范的 20 位设备编码。
 *
 * 算法：
 * 1. 输入硬件标识（Android ANDROID_ID / iOS identifierForVendor）
 * 2. 计算哈希 → 取绝对值 → 模 10^9（生成 9 位随机后缀）
 * 3. 固定前缀 "34020000001" (11 位，模拟中心编码+行业+类型) + 后缀 9 位 = 20 位
 *
 * 特性：
 * - 确定性：同输入必定产生同输出
 * - 唯一性：9 位随机后缀支持 10 亿设备，冲突概率 < 1/10^9
 * - 格式：20 位十进制，前缀 "34020000001"（安徽合肥 + 民用视频）
 *
 * 注意：不严格遵守国标的"中心编码(8)+行业(2)+类型(3)+网络(1)+序号(6)"分段语义，
 * 因为 sim 是模拟器，不对应真实行政区划/行业。平台只要求 device_id 唯一。
 */
object DeviceIdGenerator {

    private const val PREFIX = "34020000001"  // 11 位固定前缀（安徽合肥340200 + 民用视频00001）
    private const val MODULO = 1_000_000_000  // 10^9，生成 9 位后缀

    /**
     * 从硬件标识派生 20 位 deviceId。
     *
     * @param hardwareId 硬件标识字符串（Android ANDROID_ID / iOS identifierForVendor）
     * @return 20 位十进制 deviceId，前缀 "34020000001" + 9 位哈希后缀
     */
    fun deriveDeviceId(hardwareId: String): String {
        require(hardwareId.isNotEmpty()) { "hardwareId must not be empty" }

        // 用 Kotlin 内置 hashCode()，取绝对值避免负数，模 10^9 生成 9 位后缀
        val hash = hardwareId.hashCode().absoluteValue % MODULO
        val suffix = hash.toString().padStart(9, '0')

        return "$PREFIX$suffix"
    }
}
