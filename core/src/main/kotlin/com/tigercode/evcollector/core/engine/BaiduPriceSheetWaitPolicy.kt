package com.tigercode.evcollector.core.engine

/** Monotonic timestamps; after the second retry only observe, for at most five seconds. */
class BaiduPriceSheetWaitPolicy(startedAtMs: Long, initialTimeoutMs: Long) {
    var retryClicks: Int = 0
        private set
    var sheetObserved: Boolean = false
        private set
    var deadlineMs: Long = startedAtMs + initialTimeoutMs
        private set
    private var nextRetryAtMs: Long = startedAtMs + 3_000L

    fun observeSheet() {
        sheetObserved = true
    }

    fun shouldRetry(nowMs: Long): Boolean = !sheetObserved && retryClicks < 2 &&
        nowMs >= nextRetryAtMs && nowMs < deadlineMs

    fun recordRetry(nowMs: Long) {
        check(shouldRetry(nowMs)) { "Price sheet retry is not permitted" }
        retryClicks++
        nextRetryAtMs = nowMs + 3_000L
        if (retryClicks == 2) deadlineMs = nowMs + 5_000L
    }

    fun remainingMs(nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)
}
