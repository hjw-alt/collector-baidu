package com.tigercode.evcollector.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Registration rotates the server token, so the response must be saved even at a work deadline. */
internal class DeviceAuthenticationGuard {
    private val registrationMutex = Mutex()

    suspend fun <T> register(registerAndSave: suspend () -> T): T =
        registrationMutex.withLock { finishRegistration(registerAndSave) }

    suspend fun <T> authenticated(
        readToken: () -> String,
        registerAndSave: suspend () -> Unit,
        request: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        var requestToken = readToken()
        if (requestToken.isBlank()) {
            refreshIfUnchanged(requestToken, readToken, registerAndSave)
            requestToken = readToken()
        }
        return try {
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() != 401 && error.code() != 403) throw error
            refreshIfUnchanged(requestToken, readToken, registerAndSave)
            // Retry once; a persistent auth/server error is reported to the caller.
            request()
        }
    }

    private suspend fun refreshIfUnchanged(
        rejectedToken: String,
        readToken: () -> String,
        registerAndSave: suspend () -> Unit,
    ) {
        registrationMutex.withLock {
            currentCoroutineContext().ensureActive()
            val currentToken = readToken()
            // Another collector/control request may already have refreshed this credential.
            if (currentToken.isNotBlank() && currentToken != rejectedToken) return@withLock
            finishRegistration(registerAndSave)
        }
    }

    private suspend fun <T> finishRegistration(registerAndSave: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        // The HTTP client bounds registration to ten seconds; only this transaction is protected.
        val result = withContext(NonCancellable) { registerAndSave() }
        currentCoroutineContext().ensureActive()
        return result
    }
}
