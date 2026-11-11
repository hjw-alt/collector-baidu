package com.tigercode.evcollector.core

import com.tigercode.evcollector.core.engine.DeviceUiPacingPolicy
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUiPacingPolicyTest {
    @Test fun normalDeviceKeepsTimingUnchanged() {
        val policy = DeviceUiPacingPolicy(false)
        assertEquals(0L, policy.extraDelayMs(Random(1)))
        assertEquals(6_000L, policy.timeoutMs(6_000L))
        assertEquals(15_000L, policy.timeoutMs(15_000L))
    }

    @Test fun slowDeviceHasTwoToFiveExtraSecondsAndEnoughObservationTime() {
        val policy = DeviceUiPacingPolicy(true)
        val random = Random(123)
        val delays = (1..1_000).map { policy.extraDelayMs(random) }
        assertTrue(delays.all { it in 2_000L..5_000L })
        assertTrue(delays.distinct().size > 1)
        assertEquals(11_000L, policy.timeoutMs(6_000L))
        assertTrue(delays.all { delay -> policy.timeoutMs(4_000L) - 2_000L - delay >= 2_000L })
    }

    @Test fun enablingOneInstallationDoesNotChangeOtherPolicies() {
        val slow = DeviceUiPacingPolicy(true)
        val normal = DeviceUiPacingPolicy(false)
        assertEquals(7_000L, slow.timeoutMs(2_000L))
        assertEquals(2_000L, normal.timeoutMs(2_000L))
        assertEquals(0L, normal.extraDelayMs())
    }
}
