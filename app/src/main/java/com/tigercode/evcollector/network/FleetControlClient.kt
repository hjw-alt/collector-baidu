package com.tigercode.evcollector.network

import android.content.Context
import android.os.Build
import com.tigercode.evcollector.AppPreferences
import com.tigercode.evcollector.CollectionStatusNotifier
import com.tigercode.evcollector.CooldownAlarmScheduler
import com.tigercode.evcollector.accessibility.ChargingAccessibilityService
import com.tigercode.evcollector.recovery.DeepSeekKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun collectorCapabilities(context: Context): Map<String, Any> = mapOf(
    "accessibilityConnected" to ChargingAccessibilityService.isConnected(),
    "targetPackage" to ChargingAccessibilityService.AMAP_PACKAGE,
    "sdkInt" to Build.VERSION.SDK_INT,
    "manufacturer" to Build.MANUFACTURER,
    "model" to Build.MODEL,
    "fleetStopSupported" to true,
    "remoteDeviceControl" to 1,
    "collectionRunning" to AppPreferences.isScanning(context),
    "collectionMode" to if (!AppPreferences.isLocalQueueMode(context) && !AppPreferences.isMonitorMode(context)) "DEVICE_DISPATCH" else "OTHER",
    "deepSeekRecoveryTelemetry" to true,
    "deepSeekRecoveryEnabled" to DeepSeekKeyStore.isConfigured(context),
)

class FleetStoppedException : CancellationException("收到全部停止指令")

object FleetControlClient {
    fun api(context: Context): ServerApi =
        RetrofitClient.serverApi(AppPreferences.serverUrl(context), context, controlPlane = true)

    // Refreshing authentication never resumes a stopped device; only resumeManually does that.
    suspend fun control(context: Context, api: ServerApi, allowRegistration: Boolean = true): FleetControlDto {
        val response = if (allowRegistration) {
            DeviceRegistrationClient.authenticated(context) { api.deviceConfig() }
        } else {
            check(AppPreferences.deviceToken(context).isNotBlank()) { "等待设备注册" }
            api.deviceConfig()
        }
        val control = response.fleetControl
        check(control?.supported == true) { "服务器尚未部署全部停止功能，请先更新服务器" }
        return control
    }

    // Called only from a user click, never from automatic recovery or polling.
    suspend fun resumeManually(context: Context) {
        val api = api(context)
        val control = control(context, api, allowRegistration = true)
        check(control.releaseComplete) { "全停任务仍在释放，请稍后再开始" }
        api.resumeDevice(FleetGenerationRequest(control.stopGeneration))
    }

    fun markStopped(context: Context) {
        AppPreferences.setScanning(context, false)
        CooldownAlarmScheduler.cancel(context)
        val message = "收到服务端停采指令，本机已停止；可在监控台或手机上重新开始"
        AppPreferences.setRemoteStatus(context, message)
        AppPreferences.appendLog(context, message)
        CollectionStatusNotifier.show(context, message)
    }

    suspend fun stopAndAcknowledge(context: Context, api: ServerApi, generation: Long) {
        markStopped(context)
        withContext(NonCancellable) {
            withTimeoutOrNull(2_500L) {
                try {
                    api.acknowledgeStop(FleetGenerationRequest(generation))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Claim permission remains revoked even if the acknowledgement cannot arrive.
                }
            }
        }
    }

    suspend fun requireAllowed(context: Context, api: ServerApi) {
        val control = control(context, api)
        enforceFleetPermission(control) { generation -> stopAndAcknowledge(context, api, generation) }
    }
}

/** Authentication refresh must still pass the fleet stop/release gate before opening the map. */
internal suspend fun enforceFleetPermission(control: FleetControlDto, stop: suspend (Long) -> Unit) {
    if (control.stopRequired || !control.releaseComplete) {
        stop(control.stopGeneration)
        throw FleetStoppedException()
    }
}
