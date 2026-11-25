package com.tigercode.evcollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.tigercode.evcollector.core.engine.BaiduRestPhase
import com.tigercode.evcollector.core.engine.BaiduWorkRestPolicy
import com.tigercode.evcollector.core.engine.BaiduWorkRestState
import com.tigercode.evcollector.network.TaskLeaseReleaseClient
import com.tigercode.evcollector.privileged.ShizukuMapController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tigercode.evcollector.accessibility.ChargingAccessibilityService
import com.tigercode.evcollector.data.CollectorRepository
import com.tigercode.evcollector.engine.CollectionEngine
import com.tigercode.evcollector.network.FleetControlClient
import com.tigercode.evcollector.network.FleetGenerationRequest
import com.tigercode.evcollector.network.FleetStoppedException
import com.tigercode.evcollector.network.SyncManager
import com.tigercode.evcollector.network.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CollectorKeepAliveService : Service() {
    // MIUI/HyperOS 可能在息屏/切后台时压制主线程调度。调度循环使用工作线程，
    // 并由 PARTIAL_WAKE_LOCK 保证设备在长期插电采集时不会进入 CPU 深度休眠。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var cycleJob: Job? = null
    private var restAlarmDeadline = 0L
    private var lastRestWaitLog = ""
    private var lastRestWaitLogAt = 0L
    private var fleetWatchJob: Job? = null
    private var engine: CollectionEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        remoteInstance = this
        createNotificationChannel()
        acquireKeepAliveLock()
        startAsForeground("采集服务正在启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppPreferences.setScanning(this, false)
            cycleJob?.cancel()
            engine?.stop()
            CooldownAlarmScheduler.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME_AFTER_COOLDOWN) {
            if (!AppPreferences.isScanning(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            startAsForeground("采集服务继续运行")
            startSyncLoop()
            return START_STICKY
        }
        if (!AppPreferences.isScanning(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground("采集服务正在运行")
        startSyncLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        if (remoteInstance === this) remoteInstance = null
        cycleJob?.cancel()
        engine?.stop()
        loopJob?.cancel()
        fleetWatchJob?.cancel()
        scope.cancel()
        engine = null
        releaseKeepAliveLock()
        if (!AppPreferences.isScanning(this)) CooldownAlarmScheduler.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSyncLoop() {
        if (cycleJob?.isActive == true) return
        val repository = CollectorRepository(this)
        var engineRef: CollectionEngine? = null
        val engine = CollectionEngine(this, repository) {
            engineRef?.let { ref ->
                SyncManager(this@CollectorKeepAliveService, repository, ref).uploadLocalResultsOnly()
            } ?: 0
        }
        engineRef = engine
        this.engine = engine
        startFleetWatcher()
        cycleJob = scope.launch {
            try {
                runWorkRestCycle(repository, engine)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppPreferences.setScanning(this@CollectorKeepAliveService, false)
                AppPreferences.setRemoteStatus(this@CollectorKeepAliveService, "自动采集已暂停", error.message.orEmpty())
                AppPreferences.appendLog(this@CollectorKeepAliveService, "自动采集异常退出：${error.message}")
            } finally {
                withContext(NonCancellable) { engine.stopAndReleaseLocalTask() }
                if (!AppPreferences.isScanning(this@CollectorKeepAliveService)) {
                    CooldownAlarmScheduler.cancel(this@CollectorKeepAliveService)
                    stopSelf()
                }
            }
        }
    }

    private fun collectionEnabled(): Boolean = AppPreferences.isScanning(this)

    private fun saveCycle(state: BaiduWorkRestState) = AppPreferences.saveBaiduWorkRestState(this, state)

    private fun restStatus(message: String, error: String = "") {
        AppPreferences.setRemoteStatus(this, message, error)
        updateNotification(if (error.isBlank()) message else "$message：$error")
    }

    private fun restWaitStatus(message: String, error: String = "") {
        restStatus(message, error)
        val detail = if (error.isBlank()) message else "$message：$error"
        val now = SystemClock.elapsedRealtime()
        // Keep failures visible without evicting the useful history on every five-second retry.
        if (detail != lastRestWaitLog || now - lastRestWaitLogAt >= 60_000L) {
            AppPreferences.appendLog(this, "$detail；5秒后自动重试")
            lastRestWaitLog = detail
            lastRestWaitLogAt = now
        }
    }

    private fun recoveryFailureReason(error: Exception): String = when (error) {
        is HttpException -> if (error.code() == 401 || error.code() == 403) {
            "设备认证未通过（HTTP ${error.code()}），下次将重新认证"
        } else {
            "服务器请求失败（HTTP ${error.code()}）"
        }
        is SocketTimeoutException -> "连接服务器超时"
        is UnknownHostException -> "无法解析服务器地址"
        is ConnectException -> "无法连接采集服务器"
        is IOException -> "网络连接异常"
        is IllegalStateException -> error.message.orEmpty().take(160).ifBlank { "恢复状态检查未通过" }
        else -> "恢复检查异常（${error.javaClass.simpleName}）"
    }

    private suspend fun runWorkRestCycle(repository: CollectorRepository, engine: CollectionEngine) {
        val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
        var reopenAttempts = 0
        while (currentCoroutineContext().isActive && collectionEnabled()) {
            val now = SystemClock.elapsedRealtime()
            val saved = AppPreferences.baiduWorkRestState(this)
            val state = BaiduWorkRestPolicy.restore(saved, now, bootCount)
            if (saved != state) saveCycle(state)
            when (state.phase) {
                BaiduRestPhase.COLLECTING -> {
                    val remaining = BaiduWorkRestPolicy.remainingMs(state, now)
                    if (remaining == 0L) {
                        saveCycle(BaiduWorkRestPolicy.stopping(bootCount))
                        continue
                    }
                    // Recover a lease left by a killed process before claiming any new station.
                    if (!TaskLeaseReleaseClient.releasePending(this)) {
                        restWaitStatus("等待归还中断任务，暂不领取新站点", AppPreferences.remoteError(this))
                        delay(5_000L)
                        continue
                    }
                    if (!collectionEnabled()) break
                    AppPreferences.appendLog(this, "百度自动轮休：采集满2小时后释放当前任务并休息5分钟")
                    withTimeoutOrNull(BaiduWorkRestPolicy.remainingMs(state, SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
                        coroutineScope {
                            loopJob = launch { runCollectionLoop(repository, engine) }
                            loopJob?.join()
                        }
                    }
                    // withTimeout has joined all work, including upload/lease renewal cancellation.
                    if (collectionEnabled()) {
                        saveCycle(BaiduWorkRestPolicy.stopping(bootCount))
                        AppPreferences.appendLog(this, "采集已满2小时，停止当前采集并准备释放任务")
                    }
                }
                BaiduRestPhase.STOPPING -> {
                    engine.stopAndReleaseLocalTask()
                    if (!TaskLeaseReleaseClient.releasePending(this)) {
                        restWaitStatus("自动休息：任务尚未释放，等待重试", AppPreferences.remoteError(this))
                        delay(5_000L)
                        continue
                    }
                    if (!collectionEnabled()) break
                    restStatus("自动休息：正在关闭百度地图后台")
                    val result = ShizukuMapController.forceStopMap(this) {
                        engine.isRunning() || !collectionEnabled() ||
                            AppPreferences.baiduWorkRestState(this)?.phase != BaiduRestPhase.STOPPING
                    }
                    if (!collectionEnabled()) break
                    if (!result.success) {
                        restWaitStatus("自动休息：关闭百度地图未完成", result.message)
                        delay(5_000L)
                        continue
                    }
                    val switched = ChargingAccessibilityService.current()?.launchCollectorApp() == true
                    if (!collectionEnabled()) break
                    if (!switched) {
                        restWaitStatus("百度后台已关闭，等待无障碍服务返回采集App")
                        delay(5_000L)
                        continue
                    }
                    saveCycle(BaiduWorkRestPolicy.resting(SystemClock.elapsedRealtime(), bootCount))
                    AppPreferences.appendLog(this, "百度地图后台已关闭，已回到采集App，开始休息5分钟")
                }
                BaiduRestPhase.RESTING -> {
                    val remaining = BaiduWorkRestPolicy.remainingMs(state, now)
                    if (remaining == 0L) {
                        saveCycle(state.copy(phase = BaiduRestPhase.REOPENING))
                        continue
                    }
                    if (restAlarmDeadline != state.deadlineElapsedMs) {
                        CooldownAlarmScheduler.schedule(this, System.currentTimeMillis() + remaining)
                        restAlarmDeadline = state.deadlineElapsedMs
                    }
                    val seconds = (remaining + 999L) / 1000L
                    restStatus("自动休息中：剩余${seconds / 60}分${seconds % 60}秒，百度后台已关闭")
                    delay(minOf(1_000L, remaining))
                }
                BaiduRestPhase.REOPENING -> {
                    CooldownAlarmScheduler.cancel(this)
                    restAlarmDeadline = 0L
                    if (reopenAttempts == 0) {
                        AppPreferences.appendLog(this, "5分钟休息已结束，开始自动恢复：归还任务、检查服务器权限、打开百度地图")
                    }
                    reopenAttempts++
                    restStatus("休息已结束，正在自动恢复（第${reopenAttempts}次）")
                    if (!TaskLeaseReleaseClient.releasePending(this)) {
                        restWaitStatus("自动恢复：等待归还中断任务", AppPreferences.remoteError(this))
                        delay(5_000L)
                        continue
                    }
                    if (!collectionEnabled()) break
                    try {
                        // Authentication repair must never bypass a fleet-wide stop during the break.
                        FleetControlClient.requireAllowed(this, FleetControlClient.api(this))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (!collectionEnabled()) break
                        restWaitStatus("自动恢复：等待服务器确认允许继续采集", recoveryFailureReason(error))
                        delay(5_000L)
                        continue
                    }
                    if (!collectionEnabled()) break
                    val opened = try {
                        !ShizukuMapController.isBusy &&
                            ChargingAccessibilityService.current()?.launchAmap() == true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (!collectionEnabled()) break
                        restWaitStatus("自动恢复：打开百度地图失败", recoveryFailureReason(error))
                        delay(5_000L)
                        continue
                    }
                    if (!collectionEnabled()) break
                    if (!opened) {
                        val reason = when {
                            ShizukuMapController.isBusy -> "正在等待Shizuku操作结束"
                            ChargingAccessibilityService.current() == null -> "无障碍服务未连接"
                            else -> "启动百度地图未成功"
                        }
                        restWaitStatus("自动恢复：等待重新打开百度地图", reason)
                        delay(5_000L)
                        continue
                    }
                    saveCycle(BaiduWorkRestPolicy.collecting(SystemClock.elapsedRealtime(), bootCount))
                    reopenAttempts = 0
                    lastRestWaitLog = ""
                    AppPreferences.appendLog(this, "5分钟休息结束，百度地图已打开，开始下一段2小时采集")
                    restStatus("休息结束，继续百度采集")
                }
            }
        }
    }

    private suspend fun runCollectionLoop(repository: CollectorRepository, engine: CollectionEngine) {
        if (AppPreferences.isLocalQueueMode(this@CollectorKeepAliveService)) {
            updateNotification("本地任务采集启动")
            engine.startLocal("", "", "", com.tigercode.evcollector.engine.CollectionListener { _, _, _, _ -> })
            try {
                while (currentCoroutineContext().isActive && AppPreferences.isScanning(this@CollectorKeepAliveService)) {
                    updateNotification(if (engine.isRunning()) "本地任务采集中" else "本地任务已暂停")
                    delay(3000)
                }
            } finally {
                withContext(NonCancellable) { engine.stopAndReleaseLocalTask() }
            }
            return
        }
        while (currentCoroutineContext().isActive && AppPreferences.isScanning(this@CollectorKeepAliveService)) {
            if (!ChargingAccessibilityService.isConnected()) {
                val accessibilityEnabled = ChargingAccessibilityService.isEnabled(
                    this@CollectorKeepAliveService,
                )
                AppPreferences.setRemoteStatus(
                    this@CollectorKeepAliveService,
                    "等待无障碍服务",
                    if (accessibilityEnabled) {
                        "采集助手已开启，正在等待系统重新连接；若长时间无响应请关闭后重新开启一次"
                    } else {
                        "请在系统设置中开启采集助手"
                    },
                )
                updateNotification("等待无障碍服务")
                delay(5000)
                continue
            }
            // 在领取下一条远程任务前完成批次冷却，避免占着租约等待。
            val cooldownEndAt = engine.batchCooldownEndAt()
            val cooldownActive = cooldownEndAt > System.currentTimeMillis()
            if (cooldownActive) {
                CooldownAlarmScheduler.schedule(this@CollectorKeepAliveService, cooldownEndAt)
                if (shouldKeepCollectorForegroundDuringCooldown()) {
                    val switched = ChargingAccessibilityService.current()
                        ?.launchCollectorApp() == true
                    AppPreferences.appendLog(
                        this@CollectorKeepAliveService,
                        if (switched) {
                            "批次休息开始，已切换至采集 App 等待"
                        } else {
                            "批次休息开始，切换采集 App 失败"
                        },
                    )
                    updateNotification("批次休息中，采集 App 保持前台")
                }
            } else {
                CooldownAlarmScheduler.cancel(this@CollectorKeepAliveService)
            }
            engine.awaitReadyForNextTask()
            CooldownAlarmScheduler.cancel(this@CollectorKeepAliveService)
            if (cooldownActive && shouldKeepCollectorForegroundDuringCooldown()) {
                val switchedBack = ChargingAccessibilityService.current()
                    ?.launchAmap() == true
                AppPreferences.appendLog(
                    this@CollectorKeepAliveService,
                    if (switchedBack) {
                        "批次休息结束，已切换回百度地图"
                    } else {
                        "批次休息结束，切换回百度地图失败"
                    },
                )
                updateNotification("批次休息结束，正在返回百度采集")
            }
            if (!currentCoroutineContext().isActive || !AppPreferences.isScanning(this@CollectorKeepAliveService)) break
            updateNotification("正在同步服务端")
            // 有任务时快速进入下一轮；没有任务时保留较长轮询间隔，降低服务端请求频率。
            val nextSyncDelayMs = when (
                val outcome = SyncManager(
                    this@CollectorKeepAliveService,
                    repository,
                    engine,
                ).runOnce()
            ) {
                is SyncOutcome.Completed -> {
                    val message = "任务已完成，采集 ${outcome.stationCount} 个站点，等待下一条任务"
                    AppPreferences.setRemoteStatus(this@CollectorKeepAliveService, message)
                    updateNotification(message)
                    showStepStatus(message)
                    3_000L
                }
                is SyncOutcome.Skipped -> {
                    val message = "任务已跳过：${outcome.stationName}"
                    AppPreferences.setRemoteStatus(
                        this@CollectorKeepAliveService,
                        message,
                        outcome.reason,
                    )
                    updateNotification("任务已跳过，等待下一条任务")
                    showStepStatus(message)
                    3_000L
                }
                is SyncOutcome.DetailRiskReleased -> {
                    val message = "详情页风控，当前任务已释放，继续列表采集"
                    AppPreferences.setRemoteStatus(
                        this@CollectorKeepAliveService,
                        "详情页风控，继续采集",
                        outcome.message,
                    )
                    updateNotification(message)
                    showStepStatus(message)
                    8_000L
                }
                is SyncOutcome.HumanVerification -> {
                    val message = "检测到人机验证，当前任务已释放，返回列表继续采集"
                    AppPreferences.setRemoteStatus(
                        this@CollectorKeepAliveService,
                        "人机验证，继续采集",
                        outcome.message,
                    )
                    updateNotification(message)
                    showStepStatus(message)
                    8_000L
                }
                is SyncOutcome.RecoveryPaused -> {
                    restStatus("DeepSeek 恢复未完成，采集已暂停")
                    AppPreferences.setRemoteStatus(this@CollectorKeepAliveService, "DeepSeek 恢复未完成，采集已暂停", outcome.message)
                    AppPreferences.appendLog(this@CollectorKeepAliveService, outcome.message)
                    break
                }
                is SyncOutcome.Failed -> {
                    AppPreferences.setRemoteStatus(
                        this@CollectorKeepAliveService,
                        "同步失败",
                        outcome.message,
                    )
                    updateNotification("同步失败，等待重试")
                    showStepStatus("同步失败：${outcome.message}")
                    8_000L
                }
                is SyncOutcome.NoTask -> {
                    val message = "当前暂无待领取任务，正在等待新任务"
                    AppPreferences.setRemoteStatus(
                        this@CollectorKeepAliveService,
                        "等待任务",
                        message,
                    )
                    updateNotification("等待任务")
                    showStepStatus(message)
                    15_000L
                }
            }
            delay(nextSyncDelayMs)
        }
    }

    private fun startFleetWatcher() {
        if (fleetWatchJob?.isActive == true) return
        fleetWatchJob = scope.launch {
            val api = FleetControlClient.api(this@CollectorKeepAliveService)
            while (isActive && AppPreferences.isScanning(this@CollectorKeepAliveService)) {
                try {
                    val control = FleetControlClient.control(this@CollectorKeepAliveService, api)
                    if (control.stopRequired || !control.releaseComplete) {
                        engine?.stop()
                        FleetControlClient.stopAndAcknowledge(this@CollectorKeepAliveService, api, control.stopGeneration)
                        cycleJob?.cancelAndJoin()
                        stopSelf()
                        return@launch
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Covers task gaps, cooldown and accessibility reconnection waits.
                }
                delay(3_000L)
            }
        }
    }

    private fun acquireKeepAliveLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun shouldKeepCollectorForegroundDuringCooldown(): Boolean =
        Build.MODEL.contains("23013RK75C", ignoreCase = true)

    private fun releaseKeepAliveLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("百度充电站采集终端")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showStepStatus(message: String) {
        CollectionStatusNotifier.show(this, message)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "采集运行状态",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        @Volatile private var remoteInstance: CollectorKeepAliveService? = null

        fun isCollectingServiceRunning(): Boolean = remoteInstance?.cycleJob?.isActive == true

        suspend fun stopForRemote(context: Context) {
            val active = remoteInstance ?: return
            active.engine?.stop()
            active.fleetWatchJob?.cancelAndJoin()
            active.cycleJob?.cancelAndJoin()
            active.loopJob?.cancelAndJoin()
            active.stopSelf()
            kotlinx.coroutines.withTimeout(5_000L) {
                while (remoteInstance === active) delay(25L)
            }
        }

        private const val CHANNEL_ID = "collector_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.tigercode.evcollector.STOP_COLLECTOR"
        private const val ACTION_RESUME_AFTER_COOLDOWN =
            "com.tigercode.evcollector.RESUME_AFTER_COOLDOWN"
        private const val WAKE_LOCK_TAG = "evcollector:keep_alive"

        // 长期采集设备保持插电；这里给一个足够长的上限，服务重启时会重新获取。
        private const val WAKE_LOCK_TIMEOUT_MS = 24L * 60 * 60 * 1000

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CollectorKeepAliveService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CollectorKeepAliveService::class.java)
                    .setAction(ACTION_STOP)
            )
        }

        fun resumeAfterCooldown(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CollectorKeepAliveService::class.java)
                    .setAction(ACTION_RESUME_AFTER_COOLDOWN),
            )
        }
    }
}
