package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.EndpointHealthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryEndpointHealthTrackerTest {

    @Test
    fun unknownAndBlankEndpointIdsReturnHealthyDefault() {
        val tracker = InMemoryEndpointHealthTracker()

        assertEquals(EndpointHealthState.HEALTHY, tracker.getHealth("missing"))
        assertEquals(EndpointHealthState.HEALTHY, tracker.getHealth(""))

        tracker.touch("", nowMs = 1_000L)
        tracker.markFailure("", "ignored", 5_000L, nowMs = 1_000L)
        assertTrue(tracker.healthStates.value.isEmpty())
    }

    @Test
    fun touchFailureSuccessAndClearTransitionStateAndPublishFlowSnapshots() {
        val tracker = InMemoryEndpointHealthTracker()

        tracker.touch("ep_1", nowMs = 1_000L)
        assertEquals(1_000L, tracker.getHealth("ep_1").lastUsedAtMs)
        assertEquals(1_000L, tracker.healthStates.value["ep_1"]?.lastUsedAtMs)

        tracker.markFailure("ep_1", error = "HTTP 429", cooldownMs = 60_000L, nowMs = 2_000L)
        val afterFirstFailure = tracker.getHealth("ep_1")
        assertEquals(1, afterFirstFailure.consecutiveFailures)
        assertEquals(62_000L, afterFirstFailure.cooldownUntilMs)
        assertEquals("HTTP 429", afterFirstFailure.lastError)
        assertTrue(afterFirstFailure.isCoolingDown(nowMs = 61_999L))
        assertFalse(afterFirstFailure.isCoolingDown(nowMs = 62_000L))

        tracker.markFailure("ep_1", error = "HTTP 500", cooldownMs = 30_000L, nowMs = 3_000L)
        val afterSecondFailure = tracker.getHealth("ep_1")
        assertEquals(2, afterSecondFailure.consecutiveFailures)
        assertEquals(33_000L, afterSecondFailure.cooldownUntilMs)
        assertEquals("HTTP 500", afterSecondFailure.lastError)

        tracker.markSuccess("ep_1", nowMs = 4_000L)
        val afterSuccess = tracker.getHealth("ep_1")
        assertEquals(0, afterSuccess.consecutiveFailures)
        assertEquals(0L, afterSuccess.cooldownUntilMs)
        assertNull(afterSuccess.lastError)
        assertEquals(4_000L, afterSuccess.lastUsedAtMs)

        tracker.clear("ep_1")
        assertEquals(EndpointHealthState.HEALTHY, tracker.getHealth("ep_1"))
        assertFalse(tracker.healthStates.value.containsKey("ep_1"))
    }
}
