package com.tigercode.evcollector.core

import com.tigercode.evcollector.core.engine.BaiduRestPhase
import com.tigercode.evcollector.core.engine.BaiduWorkRestPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class BaiduWorkRestPolicyTest {
    @Test fun collectionExpiresExactlyAtTwoHours() {
        val state = Policy.collecting(100L, 4)
        assertEquals(1L, Policy.remainingMs(state, 100L + 7_200_000L - 1L))
        assertEquals(0L, Policy.remainingMs(state, 100L + 7_200_000L))
        assertEquals(0L, Policy.remainingMs(state, 100L + 7_200_001L))
    }

    @Test fun restStartsAfterClosureAndLastsFiveMinutes() {
        val state = Policy.resting(500_000L, 4)
        assertEquals(BaiduRestPhase.RESTING, state.phase)
        assertEquals(300_000L, Policy.remainingMs(state, 500_000L))
        assertEquals(1L, Policy.remainingMs(state, 799_999L))
        assertEquals(0L, Policy.remainingMs(state, 800_000L))
    }

    @Test fun serviceRestartKeepsOriginalCollectionDeadline() {
        val state = Policy.collecting(100L, 4)
        assertEquals(state, Policy.restore(state, 2_000_000L, 4))
    }

    @Test fun serviceRestartCannotSkipRestOrReopenEarly() {
        val state = Policy.resting(100L, 4)
        assertEquals(state, Policy.restore(state, 200L, 4))
        assertEquals(299_900L, Policy.remainingMs(Policy.restore(state, 200L, 4), 200L))
    }

    @Test fun rebootDuringRestMustCloseMapAgain() {
        for (phase in listOf(BaiduRestPhase.STOPPING, BaiduRestPhase.RESTING, BaiduRestPhase.REOPENING)) {
            val saved = Policy.resting(100L, 4).copy(phase = phase)
            assertEquals(Policy.stopping(5), Policy.restore(saved, 10L, 5))
        }
    }

    @Test fun rebootDuringCollectionStartsNewTimedSession() {
        assertEquals(Policy.collecting(10L, 5), Policy.restore(Policy.collecting(100L, 4), 10L, 5))
    }

    @Test fun expiredSavedRestDoesNotStartAnotherFiveMinuteWait() {
        val saved = Policy.resting(100L, 4)
        val restored = Policy.restore(saved, 400_000L, 4)
        assertEquals(saved, restored)
        assertEquals(0L, Policy.remainingMs(restored, 400_000L))
    }

    @Test fun nextCycleStartsFromSuccessfulReopening() {
        val next = Policy.collecting(1_000_000L, 4)
        assertEquals(7_200_000L, Policy.remainingMs(next, 1_000_000L))
    }
}
