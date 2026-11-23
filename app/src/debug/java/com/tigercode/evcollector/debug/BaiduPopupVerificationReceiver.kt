package com.tigercode.evcollector.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tigercode.evcollector.BuildConfig
import com.tigercode.evcollector.accessibility.BaiduPromotionDetector
import com.tigercode.evcollector.accessibility.ChargingAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Invokes one supported popup close without starting collection or touching task state. */
class BaiduPopupVerificationReceiver : BroadcastReceiver() {
    companion object {
        private val busy = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != "com.tigercode.bdcollector.VERIFY_POPUP_CLOSE") return
        if (!busy.compareAndSet(false, true)) return
        val pending = goAsync()
        scope.launch {
            try {
                val service = checkNotNull(ChargingAccessibilityService.current()) { "Accessibility service is not connected" }
                val before = checkNotNull(service.freshSnapshot()) { "Baidu is not the active window" }
                Log.i("BaiduPopupTest", "SCREEN ${service.screenWidthPx()}x${service.screenHeightPx()} root=${before.bounds}")
                if (intent.getBooleanExtra("dump_snapshot", false)) {
                    context.filesDir.resolve("baidu-popup-verification-before.json").writeText(com.google.gson.Gson().toJson(before))
                }
                val target = checkNotNull(service.observeBaiduPromotion(before, inspectImage = false).target) {
                    "No supported popup close control is currently visible; no click performed"
                }
                Log.i("BaiduPopupTest", "BEFORE target=${target.evidence} bounds=${target.bounds}")
                val after = service.closeBaiduPromotionOnce(target) { delay(900L) }
                if (intent.getBooleanExtra("dump_snapshot", false)) {
                    context.filesDir.resolve("baidu-popup-verification-after.json").writeText(com.google.gson.Gson().toJson(after))
                }
                val remaining = service.observeBaiduPromotion(after, inspectImage = false)
                check(remaining.target == null && !remaining.blockingImageCard && !BaiduPromotionDetector.isUpdateDialogVisible(after) &&
                    !service.isBaiduPlaceNameSheetVisible(after)) {
                    "Popup is still present after the production close action"
                }
                Log.i("BaiduPopupTest", "PASS production close action dismissed popup; no collection started")
            } catch (error: Exception) {
                Log.e("BaiduPopupTest", "FAIL ${error.message}")
            } finally {
                busy.set(false)
                pending.finish()
            }
        }
    }
}
