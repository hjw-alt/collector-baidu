package com.tigercode.evcollector.core.engine

enum class BaiduRestPhase { COLLECTING, STOPPING, RESTING, REOPENING }

data class BaiduWorkRestState(
    val phase: BaiduRestPhase,
    val deadlineElapsedMs: Long,
    val bootCount: Int,
)

/** Elapsed realtime includes device sleep and is immune to wall-clock corrections. */
object BaiduWorkRestPolicy {
    const val COLLECTION_MS = 2 * 60 * 60 * 1000L
    const val REST_MS = 5 * 60 * 1000L

    fun collecting(nowElapsedMs: Long, bootCount: Int) = BaiduWorkRestState(
        BaiduRestPhase.COLLECTING, nowElapsedMs + COLLECTION_MS, bootCount,
    )

    fun stopping(bootCount: Int) = BaiduWorkRestState(BaiduRestPhase.STOPPING, 0L, bootCount)

    fun resting(nowElapsedMs: Long, bootCount: Int) = BaiduWorkRestState(
        BaiduRestPhase.RESTING, nowElapsedMs + REST_MS, bootCount,
    )

    fun restore(saved: BaiduWorkRestState?, nowElapsedMs: Long, bootCount: Int): BaiduWorkRestState =
        when {
            saved == null -> collecting(nowElapsedMs, bootCount)
            saved.bootCount == bootCount -> saved
            // After a reboot a paused cycle must close the map again before timing its rest.
            saved.phase != BaiduRestPhase.COLLECTING -> stopping(bootCount)
            else -> collecting(nowElapsedMs, bootCount)
        }

    fun remainingMs(state: BaiduWorkRestState, nowElapsedMs: Long): Long =
        (state.deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
}
