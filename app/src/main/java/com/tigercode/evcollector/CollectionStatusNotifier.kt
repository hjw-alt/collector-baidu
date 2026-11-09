package com.tigercode.evcollector

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.tigercode.evcollector.accessibility.ChargingAccessibilityService
import kotlin.math.roundToInt

/** One bottom status view, updated in place so fast steps never build a stale Toast queue. */
object CollectionStatusNotifier {
    private val handler = Handler(Looper.getMainLooper())
    private var owner: ChargingAccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var statusView: TextView? = null
    private var fallbackToast: Toast? = null
    private val dismissRunnable = Runnable { clearOnMain() }

    fun show(context: Context, message: String) {
        if (message.isBlank()) return
        val appContext = context.applicationContext
        handler.post {
            handler.removeCallbacks(dismissRunnable)
            val service = ChargingAccessibilityService.current()
            if (service != null && showOverlay(service, message)) {
                fallbackToast?.cancel()
                fallbackToast = null
            } else {
                fallbackToast?.cancel()
                fallbackToast = Toast.makeText(appContext, message, Toast.LENGTH_SHORT).also { it.show() }
            }
        }
    }

    fun dismissAfter(delayMs: Long) {
        handler.post {
            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, delayMs)
        }
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) clearOnMain()
        else handler.post { clearOnMain() }
    }

    private fun showOverlay(service: ChargingAccessibilityService, message: String): Boolean {
        return try {
            if (owner !== service) clearOverlay()
            var view = statusView
            if (view == null) {
                val metrics = service.resources.displayMetrics
                fun dp(value: Int): Int = (value * metrics.density).roundToInt()
                view = TextView(service).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = (metrics.widthPixels - dp(40)).coerceAtLeast(1)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = GradientDrawable().apply {
                        setColor(0xE62B2B2B.toInt())
                        cornerRadius = dp(12).toFloat()
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(80)
                }
                val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                manager.addView(view, params)
                owner = service
                windowManager = manager
                statusView = view
            }
            view.text = message
            true
        } catch (error: RuntimeException) {
            Log.w("EvCollector", "底部状态提示暂不可用，使用系统提示", error)
            clearOverlay()
            false
        }
    }

    private fun clearOverlay() {
        statusView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: RuntimeException) {
                // The accessibility service/window may already have been detached.
            }
        }
        statusView = null
        windowManager = null
        owner = null
    }

    private fun clearOnMain() {
        handler.removeCallbacks(dismissRunnable)
        clearOverlay()
        fallbackToast?.cancel()
        fallbackToast = null
    }
}
