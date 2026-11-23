package com.tigercode.evcollector.network

import android.content.Context
import com.google.gson.JsonParser
import com.tigercode.evcollector.AppPreferences
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

data class RemoteTaskLease(val taskId: String, val leaseToken: String, val deviceCode: String, val mode: String)

object TaskLeaseReleaseClient {
    /** A journal survives process death between receiving a lease and returning it. */
    suspend fun releasePending(
        context: Context,
        reasonCode: String = "SCHEDULED_REST",
        reasonMessage: String = "定时休息，归还尚未完成的站点任务",
    ): Boolean {
        val lease = AppPreferences.activeRemoteLease(context) ?: return true
        val source = if (reasonCode == "AI_RECOVERY_PAUSED") "DeepSeek 恢复暂停" else "自动休息"
        try {
            val response = RetrofitClient.serverApi(AppPreferences.serverUrl(context), context, controlPlane = true).releaseTask(
                lease.taskId,
                TaskActionRequest(deviceCode = lease.deviceCode, mode = lease.mode, leaseToken = lease.leaseToken,
                    errorCode = reasonCode, errorMessage = reasonMessage),
            )
            val task = response["task"] as? Map<*, *>
            check(response["released"] == true && task?.get("id") == lease.taskId && task["leaseToken"] == "") {
                "服务端未确认当前租约已释放"
            }
            AppPreferences.clearActiveRemoteLease(context)
            AppPreferences.appendLog(context, "$source：当前任务已释放回原队列，不计失败或消耗重试次数")
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            val reason = runCatching { JsonParser.parseString(error.response()?.errorBody()?.string().orEmpty())
                .asJsonObject.get("detail")?.asString }.getOrNull()
            if (error.code() == 409 && reason in setOf("TASK_NOT_ACTIVE", "TASK_LEASE_STALE", "TASK_NOT_FOUND")) {
                AppPreferences.clearActiveRemoteLease(context)
                AppPreferences.appendLog(context, "$source：原租约已结束或不再属于本机（$reason），未改动其他任务")
                return true
            }
            val message = if (error.code() == 404 || error.code() == 405) "服务端尚未部署任务释放接口" else "任务释放接口返回 ${error.code()}"
            AppPreferences.setRemoteStatus(context, "$source：等待释放任务", message)
            return false
        } catch (error: Exception) {
            AppPreferences.setRemoteStatus(context, "$source：等待释放任务", "网络或连接异常，稍后继续释放")
            return false
        }
    }
}
