package com.uvp.sim.domain

import com.uvp.sim.gb28181.PtzPositionSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PtzNotifyTrackerTest {

    private val first = PtzPositionSnapshot(1.0, 2.0, 1.0, 60.0, 35.0, 300.0)
    private val second = first.copy(pan = 2.0)

    private fun dialog() = SubscriptionDialog(
        kind = "PtzPrecisePosition",
        subscriberUri = "sip:platform@host",
        callId = "ptz-call@host",
        fromTag = "platform-tag",
        toTag = "device-tag",
        intervalSeconds = 0,
        expiresSeconds = 60,
        remainingSeconds = 60,
    )

    @Test
    fun onlyMatching2xxConfirmsAndIncrementsCount() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog()) {}
        val attempt = assertIs<PtzNotifyPreparation.Send>(registry.preparePtzNotify("ptz-call@host", first))

        assertEquals(1, attempt.dialog.cseqNotify)
        assertEquals(SubscriptionLifecycle.Subscribing, registry.subscriptions.value["PtzPrecisePosition"]?.lifecycle)
        assertNull(registry.completePtzNotify("other@host", 1, 200))
        assertEquals(0, registry.subscriptions.value["PtzPrecisePosition"]?.notifyCount)

        val completion = registry.completePtzNotify("ptz-call@host", attempt.dialog.cseqNotify, 200)
        assertIs<PtzNotifyCompletion.Confirmed>(completion)
        assertEquals(1, registry.subscriptions.value["PtzPrecisePosition"]?.notifyCount)
        assertEquals(SubscriptionLifecycle.Subscribed, registry.subscriptions.value["PtzPrecisePosition"]?.lifecycle)
    }

    @Test
    fun failureDoesNotConfirmAndSameSnapshotIsNotRetried() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog()) {}
        val attempt = assertIs<PtzNotifyPreparation.Send>(registry.preparePtzNotify("ptz-call@host", first))

        val failure = registry.completePtzNotify("ptz-call@host", attempt.dialog.cseqNotify, 503)
        assertIs<PtzNotifyCompletion.Failed>(failure)
        assertEquals(0, registry.subscriptions.value["PtzPrecisePosition"]?.notifyCount)
        assertEquals(SubscriptionLifecycle.Exception, registry.subscriptions.value["PtzPrecisePosition"]?.lifecycle)
        assertIs<PtzNotifyPreparation.Unchanged>(registry.preparePtzNotify("ptz-call@host", first))
        assertIs<PtzNotifyPreparation.Send>(registry.preparePtzNotify("ptz-call@host", second))
    }

    @Test
    fun inFlightChangesAreCoalescedAndCanSendAfterConfirmed() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog()) {}
        val firstAttempt = assertIs<PtzNotifyPreparation.Send>(registry.preparePtzNotify("ptz-call@host", first))
        assertIs<PtzNotifyPreparation.Queued>(registry.preparePtzNotify("ptz-call@host", second))
        assertEquals(1, registry.currentDialog("ptz-call@host")?.cseqNotify)

        val completion = registry.completePtzNotify("ptz-call@host", firstAttempt.dialog.cseqNotify, 200)
        assertIs<PtzNotifyCompletion.Confirmed>(completion)
        assertEquals(second, completion.nextPosition)
        assertTrue(registry.currentDialog("ptz-call@host")?.inFlightNotifyCseq == null)
    }

    @Test
    fun timeoutIsFailureWithoutAutomaticRetry() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog()) {}
        val attempt = assertIs<PtzNotifyPreparation.Send>(registry.preparePtzNotify("ptz-call@host", first))

        assertIs<PtzNotifyCompletion.Failed>(
            registry.timeoutPtzNotify("ptz-call@host", attempt.dialog.cseqNotify)
        )
        assertIs<PtzNotifyPreparation.Unchanged>(registry.preparePtzNotify("ptz-call@host", first))
    }
}
