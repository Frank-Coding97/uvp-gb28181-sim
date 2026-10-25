package com.uvp.sim.device

import kotlin.math.absoluteValue

/**
 * 从硬件标识派生符合 GB/T 28181-2016 附录 D 的 20 位设备编码。
 *
 * 分段：`34020000 | 00 | 132 | 7 | NNNNNN`
 *
 * | 段 | 位 | 值 | 说明 |
 * |---|---|---|---|
 * | 中心编码 | 1–8 | `34020000` | 安徽合肥，非基层单位，空余位按标准取 0 |
 * | 行业编码 | 9–10 | `00` | 社会治安路面接入 |
 * | 类型编码 | 11–13 | `132` | 网络摄像机(IPC) |
 * | 网络标识 | 14 | `7` | Internet，模拟器走 IP 网络接入 |
 * | 序号 | 15–20 | 硬件哈希 | 同机恒定、异机不同 |
 *
 * ⛔ 类型段曾经是「固定 `1` + 哈希前两位」，等于每台机器的类型码都是随机数
 * （`105`/`112`/`137`…）。平台虽然不校验设备自身这三位，但一旦哈希撞出 `132`，
 * 设备根节点就和视频通道同类型；更糟的是第 14 位的哈希值撞上通道号 `1`/`2` 时，
 * 设备编码会和后置/前置通道编码**完全相等**（1/5 概率），目录树出现重复 ID、
 * 点播串到错的通道。所以类型段与网络标识都钉死，只让序号随硬件变。
 *
 * 特性：同输入恒等输出；序号 6 位（100 万空间），对模拟器的实际部署规模够用。
 */
object DeviceIdGenerator {

    /** 中心(8) + 行业(2) + 类型 `132` + 网络标识 `7` = 14 位固定前缀。 */
    private const val PREFIX = "34020000001327"

    /** 6 位序号空间。 */
    private const val MODULO = 1_000_000

    /**
     * 从硬件标识派生 20 位 deviceId。
     *
     * @param hardwareId 硬件标识字符串（Android ANDROID_ID / iOS identifierForVendor）
     * @return 20 位十进制 deviceId，`34020000001327` + 6 位哈希序号
     */
    fun deriveDeviceId(hardwareId: String): String {
        require(hardwareId.isNotEmpty()) { "hardwareId must not be empty" }

        // 先转 Long 再取绝对值：Int.MIN_VALUE.absoluteValue 仍是负数，会让序号带 '-'
        val hash = hardwareId.hashCode().toLong().absoluteValue % MODULO
        val suffix = hash.toString().padStart(6, '0')

        return PREFIX + suffix
    }
}
