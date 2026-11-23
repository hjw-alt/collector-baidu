package com.tigercode.evcollector.core.engine

import org.junit.Assert.*
import org.junit.Test

class BaiduPriceSheetWaitPolicyTest {
    @Test fun retriesAreSpacedInsteadOfClickingEveryPoll() {
        val policy = BaiduPriceSheetWaitPolicy(0L, 15_000L)
        assertFalse(policy.shouldRetry(2_999L))
        assertTrue(policy.shouldRetry(3_000L))
        policy.recordRetry(3_000L)
        assertFalse(policy.shouldRetry(3_700L))
        assertFalse(policy.shouldRetry(5_999L))
        assertTrue(policy.shouldRetry(6_000L))
    }

    @Test fun secondRetryAllowsExactlyFiveSecondsAndNoMoreClicks() {
        val policy = BaiduPriceSheetWaitPolicy(0L, 15_000L)
        policy.recordRetry(3_000L)
        policy.recordRetry(6_000L)
        assertEquals(2, policy.retryClicks)
        assertEquals(5_000L, policy.remainingMs(6_000L))
        assertEquals(1L, policy.remainingMs(10_999L))
        assertEquals(0L, policy.remainingMs(11_000L))
        assertFalse(policy.shouldRetry(9_000L))
    }

    @Test fun slowDeviceTimeoutCannotExtendFinalFiveSeconds() {
        val policy = BaiduPriceSheetWaitPolicy(0L, 60_000L)
        policy.recordRetry(3_000L)
        policy.recordRetry(6_000L)
        assertEquals(11_000L, policy.deadlineMs)
    }

    @Test fun titleAloneStopsRetriesWhileRowsLoad() {
        val policy = BaiduPriceSheetWaitPolicy(0L, 15_000L)
        policy.observeSheet()
        assertTrue(policy.sheetObserved)
        assertFalse(policy.shouldRetry(3_000L))
        assertEquals(0, policy.retryClicks)
    }

    @Test fun titleAfterFirstRetryPreventsSecondClick() {
        val policy = BaiduPriceSheetWaitPolicy(0L, 15_000L)
        policy.recordRetry(3_000L)
        policy.observeSheet()
        assertFalse(policy.shouldRetry(6_000L))
        assertEquals(1, policy.retryClicks)
    }

    @Test(expected = IllegalStateException::class)
    fun thirdRetryIsRejected() {
        val policy = BaiduPriceSheetWaitPolicy(0L, 15_000L)
        policy.recordRetry(3_000L)
        policy.recordRetry(6_000L)
        policy.recordRetry(9_000L)
    }
}
