package com.tigercode.evcollector.core.engine

import kotlin.random.Random

/** Local to one installation; normal devices keep their existing timing. */
data class DeviceUiPacingPolicy(val enabled: Boolean) {
    fun extraDelayMs(random: Random = Random.Default): Long =
        if (enabled) random.nextLong(2_000L, 5_001L) else 0L

    fun timeoutMs(baseMs: Long): Long = baseMs + if (enabled) 5_000L else 0L
}
