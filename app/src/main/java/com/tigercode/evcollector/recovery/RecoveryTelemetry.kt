package com.tigercode.evcollector.recovery

import android.content.Context
import com.tigercode.evcollector.AppPreferences
import com.tigercode.evcollector.network.RecoveryEventRequest
import com.tigercode.evcollector.network.RetrofitClient
import com.tigercode.evcollector.network.ServerApi
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

internal enum class RecoveryOutcome { NO_ACTION, REJECTED, RESUMED, NEEDS_REVIEW, CANCELLED }

/** Two updates for the same request. Neither delivery nor retries block the collector. */
internal class RecoveryTelemetry(context: Context, evidence: RecoveryEvidence) {
    private val app = context.applicationContext
    private val url = AppPreferences.serverUrl(app)
    private val deviceCode = AppPreferences.deviceCode(app)
    private val request = RecoveryEventRequest(UUID.randomUUID().toString(), evidence.stage.name, evidence.cause.name, "REQUESTED")
    private var enqueued = false

    fun requested() {
        enqueued = true
        send(request)
    }

    fun finish(outcome: RecoveryOutcome, result: RecoveryModelResult, modelMs: Long, appliedActions: Int) {
        if (!enqueued) return
        send(request.copy(
            outcome = outcome.name,
            modelStatus = result.status.name,
            action = result.recommendation?.decision?.name,
            modelMs = modelMs.coerceIn(0, 120_000),
            httpStatus = result.httpStatus,
            appliedActions = appliedActions,
        ))
    }

    private fun send(event: RecoveryEventRequest) {
        if (AppPreferences.deviceToken(app).isNotBlank()) {
            RecoveryEventSender.offer(RecoveryDelivery(app, url, deviceCode, event))
        }
    }
}

private data class RecoveryDelivery(val app: Context, val url: String, val deviceCode: String, val event: RecoveryEventRequest) {
    fun matchesDevice(): Boolean = url == AppPreferences.serverUrl(app) && deviceCode == AppPreferences.deviceCode(app)
}

private object RecoveryEventSender {
    private val queue = Channel<RecoveryDelivery>(64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var cachedUrl: String? = null
            var api: ServerApi? = null
            for (delivery in queue) {
                if (!delivery.matchesDevice()) continue
                val client = try {
                    if (api == null || cachedUrl != delivery.url) {
                        api = RetrofitClient.serverApi(delivery.url, delivery.app, controlPlane = true)
                        cachedUrl = delivery.url
                    }
                    checkNotNull(api)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (!deliver(client, delivery)) {
                    delay(500L)
                    if (delivery.matchesDevice()) deliver(client, delivery)
                }
            }
        }
    }

    fun offer(delivery: RecoveryDelivery) {
        queue.trySend(delivery)
    }

    // A failed network request or token rotation can be retried with the same invocation ID.
    private suspend fun deliver(api: ServerApi, delivery: RecoveryDelivery): Boolean = try {
        withTimeoutOrNull(3_500L) {
            api.reportRecoveryEvent(delivery.event)
            true
        } ?: false
    } catch (_: IOException) {
        false
    } catch (error: HttpException) {
        error.code() !in 500..599 && error.code() !in setOf(401, 429)
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        true
    }
}
