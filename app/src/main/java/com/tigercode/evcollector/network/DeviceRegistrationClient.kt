package com.tigercode.evcollector.network

import android.content.Context
import android.os.Build
import com.tigercode.evcollector.AppPreferences
import com.tigercode.evcollector.CollectorVersions

internal object DeviceRegistrationClient {
    private val guard = DeviceAuthenticationGuard()

    suspend fun register(context: Context): DeviceRegisterResponse =
        guard.register { registerAndSave(context) }

    suspend fun <T> authenticated(context: Context, request: suspend () -> T): T =
        guard.authenticated(
            readToken = { AppPreferences.deviceToken(context) },
            registerAndSave = {
                AppPreferences.appendLog(context, "设备认证需要更新，正在重新连接采集服务器")
                registerAndSave(context)
                AppPreferences.appendLog(context, "设备认证更新成功，继续检查服务器采集权限")
            },
            request = request,
        )

    private suspend fun registerAndSave(context: Context): DeviceRegisterResponse {
        val api = RetrofitClient.serverApi(AppPreferences.serverUrl(context), context, controlPlane = true)
        val response = api.register(DeviceRegisterRequest(
            activationCode = AppPreferences.activationCode(context),
            deviceCode = AppPreferences.deviceCode(context),
            name = Build.MODEL,
            appVersion = CollectorVersions.APP_VERSION,
            parserVersion = CollectorVersions.PARSER_VERSION,
            targetAppVersion = "",
            capabilities = collectorCapabilities(context),
        ))
        check(response.deviceToken.isNotBlank()) { "设备注册未返回认证凭据" }
        AppPreferences.saveDeviceToken(context, response.deviceToken)
        return response
    }
}
