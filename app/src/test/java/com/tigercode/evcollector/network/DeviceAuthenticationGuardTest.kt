package com.tigercode.evcollector.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DeviceAuthenticationGuardTest {
    private fun httpError(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody()))

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error !is T) throw AssertionError("Expected ${T::class.java.simpleName}, got ${error.javaClass.simpleName}", error)
            return error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun registrationSavesRotatedTokenBeforeHonoringWorkDeadlineCancellation() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        val token = AtomicReference("old")
        val requestSent = CompletableDeferred<Unit>()
        val serverReply = CompletableDeferred<Unit>()
        var continuedCollecting = false
        val work = launch {
            guard.register {
                requestSent.complete(Unit)
                withTimeout(3_000) { serverReply.await() }
                token.set("new")
            }
            continuedCollecting = true
        }
        withTimeout(3_000) { requestSent.await() }
        work.cancel()
        serverReply.complete(Unit)
        withTimeout(3_000) { work.join() }
        assertEquals("new", token.get())
        assertFalse(continuedCollecting)
    }

    @Test fun cancellingQueuedRegistrationDoesNotRotateAnotherToken() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var registrations = 0
        val first = launch {
            guard.register {
                registrations++
                firstStarted.complete(Unit)
                withTimeout(3_000) { finishFirst.await() }
            }
        }
        withTimeout(3_000) { firstStarted.await() }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { guard.register { registrations++ } }
        second.cancelAndJoin()
        finishFirst.complete(Unit)
        withTimeout(3_000) { first.join() }
        assertEquals(1, registrations)
    }

    @Test fun registrationCallsSaveInServerOrder() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        val token = AtomicReference("old")
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val first = async {
            guard.register {
                firstStarted.complete(Unit)
                withTimeout(3_000) { finishFirst.await() }
                token.set("first")
            }
        }
        withTimeout(3_000) { firstStarted.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            guard.register {
                assertEquals("first", token.get())
                token.set("second")
            }
        }
        finishFirst.complete(Unit)
        withTimeout(3_000) { awaitAll(first, second) }
        assertEquals("second", token.get())
    }

    @Test fun missingCredentialRegistersBeforeRequest() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        val token = AtomicReference("")
        var registrations = 0
        val result = guard.authenticated(token::get, { registrations++; token.set("new") }) {
            assertEquals("new", token.get())
            "allowed"
        }
        assertEquals("allowed", result)
        assertEquals(1, registrations)
    }

    @Test fun expiredCredentialRefreshesAndRetriesFor401And403() = runBlocking {
        for (code in listOf(401, 403)) {
            val guard = DeviceAuthenticationGuard()
            val token = AtomicReference("old")
            var registrations = 0
            var requests = 0
            val result = guard.authenticated(token::get, { registrations++; token.set("new") }) {
                requests++
                if (token.get() == "old") throw httpError(code)
                "success"
            }
            assertEquals("success", result)
            assertEquals(1, registrations)
            assertEquals(2, requests)
        }
    }

    @Test fun concurrentAuthFailuresShareOneCredentialRefresh() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        val token = AtomicReference("old")
        val staleRequests = AtomicInteger()
        val registrations = AtomicInteger()
        val bothRequestsSent = CompletableDeferred<Unit>()
        val jobs = List(2) {
            async {
                guard.authenticated(token::get, { registrations.incrementAndGet(); token.set("new") }) {
                    val sentToken = token.get()
                    if (sentToken == "old") {
                        if (staleRequests.incrementAndGet() == 2) bothRequestsSent.complete(Unit)
                        withTimeout(3_000) { bothRequestsSent.await() }
                        throw httpError(401)
                    }
                    sentToken
                }
            }
        }
        assertEquals(listOf("new", "new"), withTimeout(3_000) { jobs.awaitAll() })
        assertEquals(1, registrations.get())
    }

    @Test fun authFailureReusesCredentialFromRegistrationAlreadyInFlight() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        val token = AtomicReference("old")
        val registrationStarted = CompletableDeferred<Unit>()
        val finishRegistration = CompletableDeferred<Unit>()
        var extraRegistrations = 0
        val registration = launch {
            guard.register {
                registrationStarted.complete(Unit)
                withTimeout(3_000) { finishRegistration.await() }
                token.set("new")
            }
        }
        withTimeout(3_000) { registrationStarted.await() }
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            guard.authenticated(token::get, { extraRegistrations++; token.set("unnecessary") }) {
                if (token.get() == "old") throw httpError(401)
                token.get()
            }
        }
        finishRegistration.complete(Unit)
        assertEquals("new", withTimeout(3_000) { recovery.await() })
        registration.join()
        assertEquals(0, extraRegistrations)
    }

    @Test fun persistentAuthFailureStopsAfterOneRetry() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        val token = AtomicReference("old")
        var registrations = 0
        var requests = 0
        val error = expectFailure<HttpException> {
            guard.authenticated(token::get, { registrations++; token.set("new") }) {
                requests++
                throw httpError(401)
            }
        }
        assertEquals(401, error.code())
        assertEquals(1, registrations)
        assertEquals(2, requests)
    }

    @Test fun networkAndServerFailuresDoNotRotateCredentials() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        var registrations = 0
        expectFailure<IOException> {
            guard.authenticated({ "valid" }, { registrations++ }) { throw IOException("offline") }
        }
        val error = expectFailure<HttpException> {
            guard.authenticated({ "valid" }, { registrations++ }) { throw httpError(503) }
        }
        assertEquals(503, error.code())
        assertEquals(0, registrations)
    }

    @Test fun cancellationDoesNotTriggerAuthenticationRecovery() = runBlocking {
        val guard = DeviceAuthenticationGuard()
        var registrations = 0
        expectFailure<kotlinx.coroutines.CancellationException> {
            guard.authenticated({ "valid" }, { registrations++ }) {
                throw kotlinx.coroutines.CancellationException("user stopped")
            }
        }
        assertEquals(0, registrations)
    }
}
