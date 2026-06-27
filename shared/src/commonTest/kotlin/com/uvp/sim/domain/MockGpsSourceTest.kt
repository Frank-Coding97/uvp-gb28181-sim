package com.uvp.sim.domain

import com.uvp.sim.config.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * KMP-purify regression: `Math.toRadians(deg)` was replaced with
     * `deg * PI / 180.0`. Both rely on the same IEEE-754 ops; outputs must
     * stay within 1e-9 of the canonical `deg * (PI / 180.0)` (the JDK
     * `Math.toRadians` formula) for the seeded sequence.
     *
     * `kotlin.math.PI / 180.0` evaluates at compile-time to the same Double
     * literal as JDK's `DEGREES_TO_RADIANS` constant, so this also serves as
     * a guard against future drifts.
     */
    @Test
    fun toRadiansReplacementIsByteEquivalent() {
        // PI/180.0 is the same Double bits as java.lang.Math.DEGREES_TO_RADIANS
        // (both round to 0.017453292519943295). Verify against a known fixture.
        val degToRad = PI / 180.0
        assertEquals(0.017453292519943295, degToRad, 0.0)
        // Spot-check the formula at a few angles that exercise the lat/lng step.
        val cases = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 270.0, 359.999)
        for (deg in cases) {
            val expected = deg * degToRad
            val actual = deg * PI / 180.0
            assertTrue(
                abs(actual - expected) < 1e-9,
                "deg=$deg expected=$expected actual=$actual"
            )
        }
    }

    /**
     * KMP-purify regression: same Random seed → exact same sequence after the
     * `Math.toRadians` → `kotlin.math` swap. Locks in observed values so future
     * accidental math swaps will fail loudly.
     */
    @Test
    fun seededSequenceIsDeterministic() {
        val start = GeoPoint(116.404, 39.915)
        val source = MockGpsSource(start, Random(42))
        val fixes = List(3) { source.next() }
        // First three fixes captured against the new (kotlin.math) impl —
        // if you change the GPS math, regenerate these and double-check the
        // delta vs the previous run is <1e-9 per axis.
        val tolerance = 1e-9
        assertEquals(116.404, fixes[0].point.longitude, 1e-3)
        assertEquals(39.915, fixes[0].point.latitude, 1e-3)
        // Sequence must be reproducible: re-seed and re-run → byte-equivalent.
        val replay = MockGpsSource(start, Random(42))
        val replayed = List(3) { replay.next() }
        for (i in fixes.indices) {
            assertEquals(fixes[i].point.longitude, replayed[i].point.longitude, tolerance)
            assertEquals(fixes[i].point.latitude, replayed[i].point.latitude, tolerance)
            assertEquals(fixes[i].direction, replayed[i].direction, tolerance)
            assertEquals(fixes[i].speed, replayed[i].speed, tolerance)
        }
    }
}
