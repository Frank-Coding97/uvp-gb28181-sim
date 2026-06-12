package com.uvp.sim.domain

import com.uvp.sim.config.GeoPoint
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class MockGpsSourceTest {

    @Test
    fun nextReturnsPointWithinReasonableDrift() {
        val start = GeoPoint(116.404, 39.915)
        val source = MockGpsSource(start, Random(42))
        var lastPoint = start
        repeat(100) {
            val fix = source.next()
            lastPoint = fix.point
            assertTrue(fix.speed in 0.0..30.0, "speed out of range: ${fix.speed}")
            assertTrue(fix.direction in 0.0..360.0, "direction out of range: ${fix.direction}")
        }
        val latDrift = abs(lastPoint.latitude - start.latitude)
        val lngDrift = abs(lastPoint.longitude - start.longitude)
        assertTrue(latDrift < 0.015, "lat drifted too far: $latDrift")
        assertTrue(lngDrift < 0.015, "lng drifted too far: $lngDrift")
    }

    @Test
    fun firstCallReturnsNearStartPoint() {
        val start = GeoPoint(116.404, 39.915)
        val source = MockGpsSource(start, Random(1))
        val fix = source.next()
        assertTrue(abs(fix.point.latitude - start.latitude) < 0.001)
        assertTrue(abs(fix.point.longitude - start.longitude) < 0.001)
    }
}
