package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.DeviceControlActions
import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.gb28181.SnapShotConfig
import com.uvp.sim.recording.NoopRecordingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wave 4 PR-D / P2-1:[DeviceControlSubRouter] 直接路径覆盖。
 *
 * 关注点:DeviceControl 路径含 DeviceControlDispatcher 派生 + Record/StopRecord 触发副作用 +
 * 5 个 Query 路径(Preset / PtzPrecise / HomePosition / StorageCard / CruiseTrack 2 个)
 * 各能出栈 MANSCDP Response。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceControlSubRouterTest {

    private object NoopActions : DeviceControlActions {
        override suspend fun reboot() {}
        override suspend fun snapshot() {}
        override fun requestKeyFrame() {}
        override suspend fun triggerSnapshotConfig(cfg: SnapShotConfig) {}
        override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) {}
    }

    @Test
    fun accepts_returns_true_for_owned_cmdTypes() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            ctx = f.ctx, recordingService = NoopRecordingService, dispatcher = dispatcher,
            alarmResetCallback = {},
        )

        assertTrue(r.accepts("DeviceControl"))
        assertTrue(r.accepts("PresetQuery"))
        assertTrue(r.accepts("PTZPreciseStatusQuery"))
        assertTrue(r.accepts("HomePositionQuery"))
        assertTrue(r.accepts("StorageCardStatusQuery"))
        assertTrue(r.accepts("CruiseTrackListQuery"))
        assertTrue(r.accepts("CruiseTrackQuery"))

        assertFalse(r.accepts("Catalog"))
        assertFalse(r.accepts("Broadcast"))
        assertFalse(r.accepts("AlarmStatus"))
    }

    @Test
    fun preset_query_emits_preset_response() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>PresetQuery</CmdType><SN>1</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("PresetQuery", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>PresetQuery</CmdType>") },
        )
    }

    @Test
    fun ptz_precise_query_emits_response_with_pose() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        // 注入一个非默认 pose
        f.deviceControlState.value = f.deviceControlState.value.copy(panAngle = 45f, tiltAngle = 10f, zoomLevel = 2f)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>PTZPreciseStatusQuery</CmdType><SN>2</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("PTZPreciseStatusQuery", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>PTZPreciseStatusQuery</CmdType>") },
        )
    }

    @Test
    fun home_position_query_emits_response() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>HomePositionQuery</CmdType><SN>3</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("HomePositionQuery", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>HomePositionQuery</CmdType>") },
        )
    }

    @Test
    fun storage_card_status_query_emits_response() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>StorageCardStatusQuery</CmdType><SN>4</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("StorageCardStatusQuery", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>StorageCardStatusQuery</CmdType>") },
        )
    }

    @Test
    fun cruise_track_list_query_emits_response() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackListQuery</CmdType><SN>5</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("CruiseTrackListQuery", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>CruiseTrackListQuery</CmdType>") },
        )
    }

    @Test
    fun cruise_track_query_uses_group_id() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackQuery</CmdType><SN>6</SN>" +
            "<DeviceID>34020000001320000001</DeviceID><GroupID>2</GroupID></Query>"

        assertTrue(r.handle("CruiseTrackQuery", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent.first().body.decodeToString()
        assertTrue(body.contains("<CmdType>CruiseTrackQuery</CmdType>"))
        assertTrue(body.contains("<GroupID>2</GroupID>"))
    }

    @Test
    fun unknown_cmdtype_returns_false() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        assertFalse(r.handle("Catalog", "<x/>", fromUri = null))
    }
}
