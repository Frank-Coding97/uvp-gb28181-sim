package com.uvp.sim.domain

import com.uvp.sim.config.GeoPoint
import com.uvp.sim.domain.location.LocationProvider
import com.uvp.sim.domain.location.PositionFix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock

/**
 * 布朗运动式随机漫游位置源 — 测试专用 impl of [LocationProvider]。
 *
 * 生产 Android 走 [com.uvp.sim.domain.location.AndroidSystemLocationProvider] 真实 GPS;
 * iOS v1 也复用本类(v1.1 换 CLLocationManager)。
 *
 * speed 语义 = m/s(与 Android Location.getSpeed 原生一致),协议层 km/h 换算在
 * [com.uvp.sim.gb28181.MobilePositionNotify] builder 做,本类不承担协议职责。
 */
class MockGpsSource(startPoint: GeoPoint, private val random: Random = Random.Default) : LocationProvider {

    private var lat = startPoint.latitude
    private var lng = startPoint.longitude
    private var direction = random.nextDouble(360.0)

    /**
     * 重置起点 — SimConfig.mockPosition 变更时调,把内部 lat/lng 拨回新起点,
     * 方向重新随机。引用语义保留,Coord 不需要换 holder ref。
     *
     * **不在 [LocationProvider] 接口上** —— 测试关注点不污染生产接口。
     * 调用方需要持有具体类型:`(holders.location as? MockGpsSource)?.reset(...)`
     */
    fun reset(startPoint: GeoPoint) {
        lat = startPoint.latitude
        lng = startPoint.longitude
        direction = random.nextDouble(360.0)
    }

    /** 生产接口方法 — 测试实现下 no-op,latestFix 是每次 [next] 现算,不需要预启动。 */
    override fun start() = Unit

    /** 生产接口方法 — 测试实现下 no-op。 */
    override fun stop() = Unit

    override fun next(): PositionFix {
        direction = (direction + random.nextDouble(-30.0, 30.0)).mod(360.0)
        val speed = random.nextDouble(0.0, 30.0) // m/s ≈ 0-108 km/h,车速范围
        val stepDeg = random.nextDouble(0.00001, 0.0001)
        val rad = direction * PI / 180.0
        lat += stepDeg * cos(rad)
        lng += stepDeg * sin(rad)
        return PositionFix(
            point = GeoPoint(longitude = lng, latitude = lat),
            speed = speed,
            direction = direction,
            altitude = 0.0,
            accuracy = MOCK_ACCURACY,
            fixTimeMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    private companion object {
        /** Mock 定位常量精度,满足 [PositionFix.accuracy] 契约(单位米)。 */
        const val MOCK_ACCURACY: Float = 5.0f
    }
}
