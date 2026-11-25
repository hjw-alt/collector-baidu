package com.tigercode.evcollector.recovery

import android.content.Context
import com.tigercode.evcollector.AppPreferences
import com.tigercode.evcollector.accessibility.ChargingAccessibilityService
import com.tigercode.evcollector.privileged.ShizukuMapController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The active task owns the restart; a stop or foreground-app change revokes that permission. */
internal suspend fun restartBaiduForAiRecovery(
    context: Context,
    service: ChargingAccessibilityService,
    recoveryIsActive: () -> Boolean,
    awaitNextRead: suspend () -> Unit,
): Boolean {
    currentCoroutineContext().ensureActive()
    if (!recoveryIsActive() || ShizukuMapController.isBusy) return false
    if (!service.canRestartBaiduForRecovery()) {
        AppPreferences.appendLog(context, "DeepSeek 恢复：当前前台页面不允许自动重启百度地图")
        return false
    }
    val stopped = ShizukuMapController.forceStopMapForRecovery(context) {
        recoveryIsActive() && service.canRestartBaiduForRecovery()
    }
    currentCoroutineContext().ensureActive()
    if (!stopped.success) {
        AppPreferences.appendLog(context, "DeepSeek 恢复：Shizuku 未能关闭百度地图，${stopped.message}")
        return false
    }
    awaitNextRead()
    repeat(8) {
        currentCoroutineContext().ensureActive()
        if (!recoveryIsActive() || ShizukuMapController.isBusy) return false
        if (service.canRestartBaiduForRecovery()) {
            return service.relaunchBaiduForRecovery(clearTask = true).also { launched ->
                AppPreferences.appendLog(context, if (launched) "DeepSeek 恢复：已通过 Shizuku 关闭并重新打开百度地图" else "DeepSeek 恢复：百度地图重新打开失败")
            }
        }
        awaitNextRead()
    }
    AppPreferences.appendLog(context, "DeepSeek 恢复：关闭地图后前台页面未就绪，未重新打开地图")
    return false
}
