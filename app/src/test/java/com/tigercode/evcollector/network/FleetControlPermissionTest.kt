package com.tigercode.evcollector.network

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class FleetControlPermissionTest {
    @Test fun validPermissionDoesNotStopDevice() = runBlocking {
        var stops = 0
        enforceFleetPermission(FleetControlDto(supported = true, releaseComplete = true)) { stops++ }
        assertEquals(0, stops)
    }

    @Test fun stopOrIncompleteReleasePreventsAutomaticResume() = runBlocking {
        for (control in listOf(
            FleetControlDto(supported = true, stopGeneration = 44, stopRequired = true, releaseComplete = true),
            FleetControlDto(supported = true, stopGeneration = 44, stopRequired = false, releaseComplete = false),
        )) {
            var acknowledgedGeneration: Long? = null
            var openedMap = false
            try {
                enforceFleetPermission(control) { acknowledgedGeneration = it }
                openedMap = true
                fail("Fleet stop must prevent map launch")
            } catch (_: FleetStoppedException) {
                // Expected even when a credential was refreshed successfully.
            }
            assertEquals(44L, acknowledgedGeneration)
            assertFalse(openedMap)
        }
    }

    @Test fun refreshedCredentialStillHonorsStopReceivedDuringBreak() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        var token = "expired"
        var registrations = 0
        var acknowledgedGeneration: Long? = null
        var resumed = false
        val control = guard.authenticated({ token }, { registrations++; token = "new" }) {
            if (token == "expired") throw HttpException(Response.error<Any>(401, "{}".toResponseBody()))
            FleetControlDto(supported = true, stopGeneration = 45, stopRequired = true, releaseComplete = true)
        }
        try {
            enforceFleetPermission(control) { acknowledgedGeneration = it }
            resumed = true
            fail("Authentication must not grant collection permission")
        } catch (_: FleetStoppedException) {
            // Only an explicit user start may call the resume API.
        }
        assertEquals(1, registrations)
        assertEquals(45L, acknowledgedGeneration)
        assertFalse(resumed)
    }
}
