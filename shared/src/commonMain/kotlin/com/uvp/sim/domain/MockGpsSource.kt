package com.uvp.sim.domain

import com.uvp.sim.config.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MockGpsSource(startPoint: GeoPoint, private val random: Random = Random.Default) {

    private var lat = startPoint.latitude
    private var lng = startPoint.longitude
    private var direction = random.nextDouble(360.0)

    data class PositionFix(
        val point: GeoPoint,
        val speed: Double,
        val direction: Double,
        val altitude: Double = 0.0
    )

    /**
     * 重置起点 — SimConfig.mockPosition 变更时调,把内部 lat/lng 拨回新起点,
     * 方向重新随机。引用语义保留,Coord 不需要换 holder ref。
     */
    fun reset(startPoint: GeoPoint) {
        lat = startPoint.latitude
        lng = startPoint.longitude
        direction = random.nextDouble(360.0)
    }

    fun next(): PositionFix {
        direction = (direction + random.nextDouble(-30.0, 30.0)).mod(360.0)
        val speed = random.nextDouble(0.0, 30.0)
        val stepDeg = random.nextDouble(0.00001, 0.0001)
        val rad = direction * PI / 180.0
        lat += stepDeg * cos(rad)
        lng += stepDeg * sin(rad)
        return PositionFix(
            point = GeoPoint(longitude = lng, latitude = lat),
            speed = speed,
            direction = direction,
            altitude = 0.0
        )
    }
}
