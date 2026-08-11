package com.uvp.sim.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PtzPositionSnapshotTest {

    @Test
    fun actualPoseProducesDeterministicSixFieldSnapshot() {
        val snapshot = DeviceControlModel(
            panAngle = 90f,
            tiltAngle = -15f,
            zoomLevel = 2f,
        ).toPtzPositionSnapshot()

        assertEquals(90.0, snapshot.pan)
        assertEquals(-15.0, snapshot.tilt)
        assertEquals(2.0, snapshot.zoom)
        assertEquals(30.0, snapshot.horizontalFieldAngle)
        assertEquals(18.0, snapshot.verticalFieldAngle)
        assertEquals(600.0, snapshot.maxViewDistance)
    }

    @Test
    fun focusAndIrisDoNotChangeStandardSnapshot() {
        val base = DeviceControlModel(panAngle = 1f, tiltAngle = 2f, zoomLevel = 3f)
        val opticalExtensionOnly = base.copy(focusLevel = 0.9f, irisLevel = 0.1f)

        assertEquals(base.toPtzPositionSnapshot(), opticalExtensionOnly.toPtzPositionSnapshot())
    }

    @Test
    fun zoomProfileRemainsFiniteAtBoundary() {
        val snapshot = DeviceControlModel(zoomLevel = 0f).toPtzPositionSnapshot()

        assertTrue(snapshot.horizontalFieldAngle.isFinite())
        assertTrue(snapshot.verticalFieldAngle.isFinite())
        assertTrue(snapshot.maxViewDistance.isFinite())
    }

    @Test
    fun localAdjustmentUsesSharedPoseAndClampsBoundaries() {
        val adjusted = DeviceControlModel(
            panAngle = 179f,
            tiltAngle = -89f,
            zoomLevel = 19.5f,
        ).adjustLocalPtzPosition(
            panDelta = 5f,
            tiltDelta = -5f,
            zoomDelta = 2f,
        )

        assertEquals(180f, adjusted.panAngle)
        assertEquals(-90f, adjusted.tiltAngle)
        assertEquals(20f, adjusted.zoomLevel)
        assertEquals(
            DeviceEffect.LocalPoseGoto(PtzPose(180f, -90f, 20f)),
            adjusted.pendingEffect,
        )
    }

    @Test
    fun localAdjustmentAtBoundaryReturnsSameState() {
        val boundary = DeviceControlModel(panAngle = 180f, tiltAngle = 90f, zoomLevel = 1f)

        assertTrue(
            boundary === boundary.adjustLocalPtzPosition(
                panDelta = 5f,
                tiltDelta = 5f,
                zoomDelta = -1f,
            ),
        )
    }
}
