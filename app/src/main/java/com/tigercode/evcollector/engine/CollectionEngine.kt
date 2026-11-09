package com.tigercode.evcollector.engine

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import com.tigercode.evcollector.AppPreferences
import com.tigercode.evcollector.CollectionStatusNotifier
import com.tigercode.evcollector.accessibility.ChargingAccessibilityService
import com.tigercode.evcollector.core.model.CityDistricts
import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.PricePeriod
import com.tigercode.evcollector.core.model.Rect
import com.tigercode.evcollector.core.model.StationDetail
import com.tigercode.evcollector.core.parser.BaiduChargingPileParser
import com.tigercode.evcollector.core.parser.BaiduPriceParser
import com.tigercode.evcollector.core.parser.BaiduStationIdentityMatcher
import com.tigercode.evcollector.core.parser.StationNameNormalizer
import com.tigercode.evcollector.data.CollectorRepository
import com.tigercode.evcollector.data.ScanTaskEntity
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

class StopRequestedException : IllegalStateException("已停止")

private class BaiduFeedbackPageException : IllegalStateException("百度地图发生异常，进入联系方式反馈页")

class SkippedStationException(val stationName: String) :
    IllegalStateException("无电价/桩信息: $stationName")

class StationNotFoundException(val stationName: String) :
    IllegalStateException("搜索结果中未找到目标站点: $stationName")

class HumanVerificationException(val phrase: String, val pageExcerpt: String = "") :
    IllegalStateException("检测到人机验证: $phrase")

class DetailPageRiskControlException(
    val stationName: String,
    val phrase: String,
    val pageExcerpt: String = "",
) : IllegalStateException("详情页触发风控($phrase): $stationName")

class SuggestionSearchNotStartedException(
    val stationName: String,
    val stage: String,
    val detail: String = "",
) : IllegalStateException("联想搜索未真正开始($stage): $stationName")

enum class CollectionState(val label: String) {
    IDLE("待机"),
    WAITING_SERVICE("等待无障碍服务"),
    PAUSED("限速暂停"),
    OPENING_AMAP("打开百度地图"),
    SCANNING_RESULTS("扫描搜索结果"),
    OPENING_DETAIL("进入站点详情"),
    READING_DETAIL("读取站点详情"),
    OPENING_PRICE("打开价格详情"),
    READING_PRICE("读取分时电价"),
    RETURNING("返回搜索页"),
    SAVING("保存站点"),
    DONE("完成"),
    STOPPED("已停止"),
    ERROR("异常"),
}

fun interface CollectionListener {
    fun onEvent(state: CollectionState, message: String, stationCount: Int, currentStation: String)
}

/**
 * 百度地图采集：站点详情 -> 电桩卡片空闲/忙碌汇总 -> 分时电价。
 */
class CollectionEngine(
    private val context: Context,
    private val repository: CollectorRepository,
    private val uploadLocalResults: suspend () -> Int,
) {
    private companion object {
        const val MAX_DETAIL_SCROLLS = 3
        const val SEARCH_RESULT_TIMEOUT_MS = 6_000L
        const val SEARCH_LOADING_TIMEOUT_MS = 15_000L
        const val SEARCH_CITY_RECOVERY_TIMEOUT_MS = 4_000L
        const val SEARCH_INPUT_ATTEMPTS = 3
        const val SUGGESTION_TIMEOUT_MS = 4_000L
        val HENAN_SEARCH_LOCATIONS: Set<String> = buildSet {
            CityDistricts.cityDistricts.forEach { (city, districts) ->
                add("${city}市")
                addAll(districts)
            }
        }
    }

    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<CollectionListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runMutex = Mutex()

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var pauseUntil = 0L
    @Volatile private var activeLocalTask: ScanTaskEntity? = null

    fun addListener(listener: CollectionListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: CollectionListener) {
        listeners.remove(listener)
    }

    fun isRunning(): Boolean = running

    fun batchCooldownEndAt(): Long = pauseUntil

    suspend fun awaitReadyForNextTask() {
        if (System.currentTimeMillis() < pauseUntil) {
            emit(CollectionState.PAUSED, "站点处理完毕，稍候领取下一站")
        }
        while (System.currentTimeMillis() < pauseUntil) delay(500L)
    }

    fun stop() {
        stopRequested = true
        running = false
        emit(CollectionState.STOPPED, "已停止")
    }

    fun startLocal(city: String, district: String, keyword: String, listener: CollectionListener) {
        addListener(listener)
        if (running) return
        stopRequested = false
        running = true
        scope.launch {
            var done = 0
            try {
                while (!stopRequested) {
                    val next = repository.nextPendingTask() ?: break
                    val claimed = repository.claimTask(next)
                    activeLocalTask = claimed
                    emit(CollectionState.SCANNING_RESULTS, "采集 ${claimed.name}", done, claimed.name)
                    try {
                        runStationTask(
                            stationId = claimed.stationId,
                            stationName = claimed.name,
                            address = claimed.address,
                            latitude = claimed.latitude,
                            longitude = claimed.longitude,
                            remoteTaskId = "",
                        ) ?: throw IllegalStateException("未采集到站点")
                        done++
                        emit(CollectionState.DONE, "已完成 $done 个站点", done, claimed.name)
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (error is StopRequestedException) {
                            repository.releaseTask(claimed)
                            AppPreferences.appendLog(context, "已停止，任务已释放: ${claimed.name}")
                        } else if (error is SkippedStationException) {
                            repository.markSkipped(claimed, error.message ?: "跳过")
                            AppPreferences.appendLog(context, "已跳过: ${claimed.name}")
                            emit(CollectionState.ERROR, "跳过: ${claimed.name}", done, claimed.name)
                        } else {
                            repository.markFailed(claimed, error.message ?: "采集失败")
                            AppPreferences.appendLog(
                                context,
                                "本地任务失败: ${claimed.name} - ${error.message}",
                            )
                            emit(CollectionState.ERROR, "失败: ${claimed.name}", done, claimed.name)
                        }
                    } finally {
                        activeLocalTask = null
                    }
                    running = true
                    delay(Random.nextLong(2_000L, 5_000L))
                }
                emit(CollectionState.DONE, "本地任务处理完毕，共 $done 个", done)
            } finally {
                running = false
                activeLocalTask = null
            }
        }
    }

    suspend fun runRegion(city: String, district: String, keyword: String): List<StationDetail> = emptyList()

    suspend fun runStationListSnapshotTask(
        stationId: String,
        stationName: String,
        address: String,
        latitude: Double?,
        longitude: Double?,
        remoteTaskId: String,
    ): StationDetail? = runStationTask(stationId, stationName, address, latitude, longitude, remoteTaskId)

    suspend fun runStationTask(
        stationId: String,
        stationName: String,
        address: String,
        latitude: Double?,
        longitude: Double?,
        remoteTaskId: String,
    ): StationDetail? = withContext(Dispatchers.Main.immediate) {
        runMutex.withLock {
            stopRequested = false
            running = true
            val startedAt = System.currentTimeMillis()
            try {
                val detail = collectDetailWithRecovery(stationName, address)
                if (detail != null) {
                    saveDetail(stationName, address, latitude, longitude, stationId, remoteTaskId, detail)
                    emit(CollectionState.DONE, "已采集 ${detail.stationName}", 1, detail.stationName)
                    pauseUntil = System.currentTimeMillis() + Random.nextLong(4_000L, 9_000L)
                }
                detail
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error is SkippedStationException) {
                    AppPreferences.appendLog(context, "无电价/桩信息，跳过站点: $stationName")
                    emit(CollectionState.DONE, "无电价/桩信息，跳过: $stationName", 0, stationName)
                } else {
                    AppPreferences.appendLog(context, "采集失败: ${error.message}")
                    emit(CollectionState.ERROR, error.message ?: "采集异常")
                }
                throw error
            } finally {
                running = false
                AppPreferences.appendLog(
                    context,
                    "本次采集耗时 ${(System.currentTimeMillis() - startedAt) / 1000} 秒",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 页面流程
    // ------------------------------------------------------------------

    /** Match the safe-mode contact form, not an ordinary feedback button or network error. */
    private fun isBaiduFeedbackPage(snapshot: NodeSnapshot): Boolean {
        val text = collectTexts(snapshot).joinToString("").replace(Regex("\\s+"), "")
        return text.contains("发生异常") && text.contains("请填写您的联系方式") &&
            text.contains("等待我们联系您处理") && text.contains("电话") &&
            text.contains("微信") && text.contains("反馈")
    }

    private fun collectionSnapshot(service: ChargingAccessibilityService): NodeSnapshot? {
        val snapshot = service.freshSnapshot() ?: return null
        if (isBaiduFeedbackPage(snapshot)) throw BaiduFeedbackPageException()
        return snapshot
    }

    private suspend fun collectDetailWithRecovery(stationName: String, address: String): StationDetail {
        var recoveries = 0
        while (true) {
            checkStop()
            try {
                return collectDetail(stationName, address)
            } catch (error: BaiduFeedbackPageException) {
                if (recoveries >= 1) {
                    throw IllegalStateException("百度地图异常反馈页重开后再次出现，本任务停止自动恢复", error)
                }
                recoveries++
                val service = waitForService() ?: throw IllegalStateException("无障碍服务未连接")
                restartBaiduAfterFeedback(service, stationName)
            }
        }
    }

    private suspend fun restartBaiduAfterFeedback(
        service: ChargingAccessibilityService,
        stationName: String,
    ) {
        checkStop()
        emit(CollectionState.OPENING_AMAP, "检测到百度异常反馈页，退出百度地图", 0, stationName)
        if (!service.leaveBaiduForRecovery()) {
            throw IllegalStateException("百度异常反馈页退出失败")
        }
        delay(800L)
        checkStop()
        emit(CollectionState.OPENING_AMAP, "重新打开百度地图，恢复当前站点采集（1/1）", 0, stationName)
        if (!service.relaunchBaiduForRecovery(clearTask = true)) {
            throw IllegalStateException("百度异常反馈页退出后重新打开失败")
        }
        val openedAt = SystemClock.uptimeMillis()
        val deadline = openedAt + 12_000L
        var stableReads = 0
        var reopenedOnce = false
        while (SystemClock.uptimeMillis() < deadline) {
            delay(500L)
            checkStop()
            // Read without the collection guard while the old error window is closing.
            val snapshot = service.freshSnapshot()
            val hasMapWindow = service.hasAmapWindow()
            if (!hasMapWindow && !reopenedOnce && SystemClock.uptimeMillis() - openedAt >= 2_500L) {
                // Safe mode can terminate the first fresh launch and leave the launcher visible.
                reopenedOnce = true
                stableReads = 0
                checkStop()
                emit(CollectionState.OPENING_AMAP, "百度地图重开后退回桌面，补开一次", 0, stationName)
                if (!service.relaunchBaiduForRecovery(clearTask = false)) {
                    throw IllegalStateException("百度地图退回桌面后补开失败")
                }
                continue
            }
            if (hasMapWindow && snapshot != null && !isBaiduFeedbackPage(snapshot) && collectTexts(snapshot).isNotEmpty()) {
                stableReads++
                if (stableReads >= 2) {
                    recoverBaiduIfNeeded(service)
                    emit(CollectionState.SCANNING_RESULTS, "百度地图已重新打开，继续采集当前站点", 0, stationName)
                    return
                }
            } else {
                stableReads = 0
            }
        }
        throw IllegalStateException("百度地图重开后12秒内未恢复可读取的地图页面")
    }

    private suspend fun collectDetail(stationName: String, address: String): StationDetail {
        emit(CollectionState.WAITING_SERVICE, "检查无障碍服务", 0, stationName)
        val service = waitForService() ?: throw IllegalStateException("无障碍服务未连接")
        ensureBaiduForeground(service)
        // 1. 确认输入页、可编辑状态和焦点，再写入并回读核对完整站名。
        prepareSearchInput(service, stationName)
        // 2. 先等待地点联想；没有匹配项时，再提交完整搜索。
        val destination = openMatchingSuggestion(service, stationName)
            ?: submitSearchWithOneRetry(service, stationName)
            ?: throw StationNotFoundException(stationName)

        // 3. 搜索可直接打开详情；列表路径仍须重新核对位置后点击。
        val detailSnapshot = openSearchDestination(service, stationName, destination)
            ?: run {
                emit(CollectionState.OPENING_DETAIL, "详情页尚未打开，重新确认搜索后的页面", 0, stationName)
                val refreshed = waitForResult(service, stationName)
                    ?: throw IllegalStateException("详情页未打开，结果列表也未确认目标站点: $stationName")
                openSearchDestination(service, stationName, refreshed)
            }
            ?: throw IllegalStateException("详情页未打开: $stationName")

        // 4. 解析详情
        emit(CollectionState.READING_DETAIL, "读取站点详情", 0, stationName)
        var loadedSnapshot = detailSnapshot
        var hasData = hasPriceOrPile(loadedSnapshot)
        var scrollCount = 0
        while (!hasData && scrollCount < MAX_DETAIL_SCROLLS) {
            checkStop()
            emit(CollectionState.READING_DETAIL, "滚动查找电价和桩信息（${scrollCount + 1}/$MAX_DETAIL_SCROLLS）", 0, stationName)
            service.scrollContentSlow()
            delay(1_200L)
            loadedSnapshot = collectionSnapshot(service) ?: loadedSnapshot
            hasData = hasPriceOrPile(loadedSnapshot)
            scrollCount++
            AppPreferences.appendLog(context, "详情页无电价/桩信息，已向下滚动 ${scrollCount} 次")
        }
        if (!hasData) {
            throw SkippedStationException(stationName)
        }
        var detail = mergeStationDetail(
            parseDetail(detailSnapshot, stationName, address),
            parseDetail(loadedSnapshot, stationName, address),
        )

        // 5. 日常循环只读取卡片状态；已有静态逐桩明细由服务端保留。
        detail = collectChargingStatusIfPresent(service, detail)
        var resultPeriods: List<PricePeriod> = emptyList()

        // 6. 从当前详情页位置继续向下找分时电价，检查每次滚动后的页面。
        emit(CollectionState.OPENING_PRICE, "查找分时电价入口", 0, stationName)
        val priceLookup = openPriceSheet(service, stationName, detail)
        detail = priceLookup.detail
        if (priceLookup.opened) {
            val sheet = waitForPriceSheet(service, stationName, priceLookup.sheetAlreadyVisible)
            if (sheet != null) {
                emit(CollectionState.READING_PRICE, "读取分时电价", 0, stationName)
                val periods = BaiduPriceParser.periods(sheet)
                if (periods.isNotEmpty()) {
                    resultPeriods = periods
                }
            }
        }

        // 7. 返回搜索页
        emit(CollectionState.RETURNING, "返回搜索页", 1, stationName)
        returnToSearchPage(service)
        return if (resultPeriods.isEmpty()) detail
        else detail.copy(fastPrices = resultPeriods, priceTrendTitle = "价格详情")
    }

    private suspend fun waitForService(timeoutMs: Long = 15_000L): ChargingAccessibilityService? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            checkStop()
            ChargingAccessibilityService.current()?.let { return it }
            delay(300L)
        }
        return null
    }

    private suspend fun ensureBaiduForeground(service: ChargingAccessibilityService) {
        if (service.isAmapForeground()) {
            recoverBaiduIfNeeded(service)
            return
        }
        emit(CollectionState.OPENING_AMAP, "正在打开百度地图")
        service.launchAmap()
        delay(1_500L)
        if (!service.isAmapForeground()) {
            service.launchAmap()
            delay(2_000L)
        }
        recoverBaiduIfNeeded(service)
    }

    /** Only the Baidu startup recovery notice may authorize this confirmation. */
    private fun baiduRecoveryConfirm(snapshot: NodeSnapshot): NodeSnapshot? {
        fun find(node: NodeSnapshot, predicate: (NodeSnapshot) -> Boolean): NodeSnapshot? {
            if (predicate(node)) return node
            for (child in node.children) find(child, predicate)?.let { return it }
            return null
        }
        val notice = find(snapshot) {
            val text = it.text.ifBlank { it.contentDescription }.replace(" ", "")
            it.viewId == "com.baidu.BaiduMap:id/tv_notice" &&
                text.contains("清理数据") && text.contains("恢复地图默认设置") && text.contains("启动")
        } ?: return null
        if (!notice.bounds.isValid) return null
        return find(snapshot) {
            it.viewId == "com.baidu.BaiduMap:id/bt_confirm" && it.enabled && it.bounds.isValid &&
                it.text.trim() in setOf("确认", "确定")
        }
    }

    private suspend fun recoverBaiduIfNeeded(service: ChargingAccessibilityService): Boolean {
        val initial = collectionSnapshot(service) ?: return false
        var confirm = baiduRecoveryConfirm(initial) ?: return false
        checkStop()
        emit(CollectionState.OPENING_AMAP, "检测到百度地图异常恢复页，点击确认")
        if (!service.clickAt(confirm.bounds.centerX, confirm.bounds.centerY)) {
            throw IllegalStateException("百度地图异常恢复页确认点击失败")
        }
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + 10_000L
        var confirmClicks = 1
        var lastLaunchAt = startedAt
        while (SystemClock.uptimeMillis() < deadline) {
            delay(500L)
            checkStop()
            val now = SystemClock.uptimeMillis()
            // Confirmation may close the recovery activity instead of opening the map.
            if (!service.isAmapForeground()) {
                if (now - lastLaunchAt >= 1_000L) {
                    emit(CollectionState.OPENING_AMAP, "异常页已确认，正在重新打开百度地图")
                    service.launchAmap()
                    lastLaunchAt = now
                }
                continue
            }
            val snapshot = collectionSnapshot(service) ?: continue
            val remaining = baiduRecoveryConfirm(snapshot)
            if (remaining == null) {
                emit(CollectionState.SCANNING_RESULTS, "百度地图异常恢复页已退出，继续准备搜索")
                return true
            }
            if (confirmClicks < 2 && now - startedAt >= 2_500L) {
                confirm = remaining
                checkStop()
                emit(CollectionState.OPENING_AMAP, "百度地图异常恢复页仍在，重新点击确认")
                if (!service.clickAt(confirm.bounds.centerX, confirm.bounds.centerY)) {
                    throw IllegalStateException("百度地图异常恢复页再次确认失败")
                }
                confirmClicks++
            }
        }
        throw IllegalStateException("百度地图异常恢复页确认后10秒内未恢复")
    }

    private suspend fun prepareSearchInput(
        service: ChargingAccessibilityService,
        stationName: String,
    ) {
        var failure = "搜索输入页未打开"
        repeat(SEARCH_INPUT_ATTEMPTS) { attempt ->
            checkStop()
            emit(
                CollectionState.SCANNING_RESULTS,
                "确认搜索输入页（${attempt + 1}/$SEARCH_INPUT_ATTEMPTS）",
                0, stationName,
            )
            if (!ensureSearchPage(service)) {
                failure = "搜索输入页未打开"
            } else {
                emit(CollectionState.SCANNING_RESULTS, "输入页已打开，确认搜索框可编辑", 0, stationName)
                val editable = waitForSearchInput(service, 2_000L) { it.ready }
                if (editable == null) {
                    failure = "搜索框不可编辑"
                } else {
                    emit(CollectionState.SCANNING_RESULTS, "搜索框可编辑，确认输入焦点", 0, stationName)
                    if (!editable.focused) service.focusSearchInput()
                    val focused = waitForSearchInput(service, 2_000L) { it.ready && it.focused }
                    if (focused == null) {
                        failure = "搜索框未获得焦点"
                    } else {
                        emit(CollectionState.SCANNING_RESULTS, "写入站名并核对输入内容", 0, stationName)
                        val actionAccepted = service.setFocusedText(stationName)
                        AppPreferences.appendLog(context, "搜索词写入动作已提交=$actionAccepted，等待回读核对")
                        // Acceptance of ACTION_SET_TEXT is not proof of a successful write.
                        // Require two fresh, matching reads before the search button is clicked.
                        val verified = waitForSearchInput(service, 2_000L, consecutiveMatches = 2) {
                            it.ready && it.focused && it.text == stationName
                        }
                        if (verified != null) {
                            emit(CollectionState.SCANNING_RESULTS, "搜索词已核对，与任务站名一致", 0, stationName)
                            return
                        }
                        failure = "搜索词写入后回读不一致"
                    }
                }
            }
            emit(
                CollectionState.SCANNING_RESULTS,
                if (attempt + 1 < SEARCH_INPUT_ATTEMPTS) "$failure，重新准备输入框"
                else "$failure，输入准备失败",
                0, stationName,
            )
            if (attempt + 1 < SEARCH_INPUT_ATTEMPTS) delay(400L)
        }
        throw IllegalStateException("$failure（已尝试${SEARCH_INPUT_ATTEMPTS}次）: $stationName")
    }

    private data class SuggestionMatch(val label: String, val point: Pair<Int, Int>, val score: Int)

    /** 只读取原生地点联想标题及同一行地址，不点击酒店、AI推荐或右侧操作按钮。 */
    private fun suggestionMatches(snapshot: NodeSnapshot, stationName: String, visibleBottom: Int): List<SuggestionMatch> {
        val matches = mutableListOf<SuggestionMatch>()
        fun nodesWithId(node: NodeSnapshot, suffix: String): List<NodeSnapshot> = buildList {
            if (node.viewId.endsWith(suffix)) add(node)
            node.children.forEach { addAll(nodesWithId(it, suffix)) }
        }
        fun walk(node: NodeSnapshot, ancestors: List<NodeSnapshot>) {
            if (node.className.contains("WebView", ignoreCase = true)) return
            if (node.viewId.endsWith(":id/tv_listitem_multiline_title") && node.enabled && node.bounds.isValid &&
                node.bounds.top > snapshot.bounds.top && node.bounds.bottom < minOf(visibleBottom - 12, snapshot.bounds.bottom)
            ) {
                val row = ancestors.asReversed().firstOrNull {
                    it.bounds.isValid && it.bounds.height <= snapshot.bounds.height / 4 &&
                        nodesWithId(it, ":id/tv_listitem_multiline_title").size == 1 &&
                        nodesWithId(it, ":id/l1c2").isNotEmpty()
                }
                if (row != null) {
                    val label = node.text.ifBlank { node.contentDescription }.trim()
                    val location = nodesWithId(row, ":id/l1c2").joinToString(" ") { it.text.ifBlank { it.contentDescription } }
                    val score = stationNameMatchScore(stationName, label, location)
                    val actionLeft = (nodesWithId(row, ":id/iv_listitem_multiline_taxi") +
                        nodesWithId(row, ":id/iv_listitem_multiline_right_new"))
                        .filter { it.bounds.isValid }.minOfOrNull { it.bounds.left } ?: snapshot.bounds.right
                    val left = maxOf(node.bounds.left + 8, snapshot.bounds.left + 16)
                    val right = minOf(node.bounds.right - 8, actionLeft - 16,
                        snapshot.bounds.left + (snapshot.bounds.width * 0.68f).toInt())
                    if (score > 0 && right - left >= 24) {
                        matches += SuggestionMatch(label, (left + right) / 2 to node.bounds.centerY, score)
                    }
                }
            }
            node.children.forEach { walk(it, ancestors + node) }
        }
        walk(snapshot, emptyList())
        return matches.sortedWith(compareByDescending<SuggestionMatch> { it.score }.thenBy { it.point.second })
    }

    private suspend fun openMatchingSuggestion(
        service: ChargingAccessibilityService,
        stationName: String,
    ): SearchDestination? {
        emit(CollectionState.SCANNING_RESULTS, "站名已更新，等待2秒刷新新的联想列表", 0, stationName)
        val startedAt = SystemClock.uptimeMillis()
        // 新词写入成功时，旧词的联想列表可能仍停留在页面上；刷新等待期间禁止匹配和点击。
        delay(2_000L)
        checkStop()
        emit(CollectionState.SCANNING_RESULTS, "读取新的地点联想，优先查找匹配站点", 0, stationName)
        var previous: SuggestionMatch? = null
        var chosen: SuggestionMatch? = null
        while (SystemClock.uptimeMillis() - startedAt < SUGGESTION_TIMEOUT_MS) {
            checkStop()
            val input = service.searchInputState()
            if (input == null || !input.ready || input.text != stationName) {
                throw IllegalStateException("等待联想时搜索词发生变化，保留任务重试: $stationName")
            }
            val snapshot = collectionSnapshot(service)
            val match = snapshot?.takeUnless(::isSearchLoading)?.let { suggestionMatches(it, stationName, service.searchSuggestionVisibleBottom()).firstOrNull() }
            if (match != null && previous?.label == match.label &&
                sameResultPosition(previous.point, match.point)
            ) {
                chosen = match
                break
            }
            previous = match
            delay(300L)
        }
        if (chosen == null) {
            emit(CollectionState.SCANNING_RESULTS, "联想中无匹配地点，改用搜索按钮", 0, stationName)
            return null
        }
        checkStop()
        val current = collectionSnapshot(service)?.let { suggestionMatches(it, stationName, service.searchSuggestionVisibleBottom()).firstOrNull() }
        val input = service.searchInputState()
        if (input?.let { it.ready && it.text == stationName } != true || current == null ||
            current.label != chosen.label || !sameResultPosition(chosen.point, current.point)
        ) {
            emit(CollectionState.SCANNING_RESULTS, "联想位置已变化，改用搜索按钮", 0, stationName)
            return null
        }
        emit(CollectionState.SCANNING_RESULTS, "点击联想站名：${current.label}", 0, stationName)
        AppPreferences.appendLog(context, "联想匹配: $stationName -> ${current.label}，标题点击位置=${current.point}")
        if (!service.clickAt(current.point.first, current.point.second)) {
            emit(CollectionState.SCANNING_RESULTS, "联想点击未提交，改用搜索按钮", 0, stationName)
            return null
        }
        emit(CollectionState.OPENING_DETAIL, "已点击联想站名，等待列表或目标详情", 0, stationName)
        val clickedAt = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - clickedAt < SEARCH_LOADING_TIMEOUT_MS) {
            checkStop()
            val snapshot = collectionSnapshot(service)
            val onInput = service.searchInputState() != null
            if (!onInput && snapshot != null && !isSearchLoading(snapshot)) {
                if (directDetailTitle(snapshot, stationName) != null) {
                    emit(CollectionState.OPENING_DETAIL, "联想已直接打开目标详情", 0, stationName)
                    return SearchDestination.DirectDetail(snapshot)
                }
                if (searchResultList(snapshot) != null) {
                    emit(CollectionState.SCANNING_RESULTS, "联想已打开结果列表，核对目标站点", 0, stationName)
                    return findResultAcrossCities(service, stationName)
                }
            }
            if (onInput && SystemClock.uptimeMillis() - clickedAt >= 2_500L) {
                emit(CollectionState.SCANNING_RESULTS, "联想点击后仍在输入页，改用搜索按钮", 0, stationName)
                return null
            }
            delay(300L)
        }
        throw IllegalStateException("联想点击后未确认结果或目标详情，保留任务重试: $stationName")
    }

    private suspend fun waitForSearchInput(
        service: ChargingAccessibilityService,
        timeoutMs: Long,
        consecutiveMatches: Int = 1,
        predicate: (ChargingAccessibilityService.SearchInputState) -> Boolean,
    ): ChargingAccessibilityService.SearchInputState? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var matches = 0
        while (SystemClock.uptimeMillis() < deadline) {
            checkStop()
            val state = service.searchInputState()
            if (state == null && recoverBaiduIfNeeded(service)) {
                matches = 0
                continue
            }
            matches = if (state != null && predicate(state)) matches + 1 else 0
            if (matches >= consecutiveMatches) return state
            delay(200L)
        }
        return null
    }

    private suspend fun ensureSearchPage(service: ChargingAccessibilityService): Boolean {
        repeat(5) {
            checkStop()
            if (recoverBaiduIfNeeded(service)) return@repeat
            // Do not click again when the real input page is already open.
            if (service.searchInputState() != null) return true
            val snapshot = collectionSnapshot(service)
            if (snapshot == null) {
                delay(300L)
                return@repeat
            }
            val texts = collectTexts(snapshot)
            val onDetail = texts.any { it.contains("查看价格详情") || it.contains("地点详情") }
            val onPriceSheet = texts.any { it.trim() == "价格详情" }
            val onPileSheet = texts.any { it.trim() == "电桩详情" }
            if (onPriceSheet || onPileSheet || onDetail) {
                service.globalBack()
                delay(700L)
                return@repeat
            }
            // Both the results page and the home page have a top search bar.
            // A dispatched tap only starts navigation; re-read until an input appears.
            emit(CollectionState.SCANNING_RESULTS, "点击顶部搜索输入框", 0)
            val clicked = service.clickTopSearchBar()
            AppPreferences.appendLog(context, "打开搜索输入页：顶部输入框点击已提交=$clicked")
            if (waitForSearchInput(service, 1_600L) { true } != null) return true
            // Retry the physical input area on result pages too; waiting alone
            // cannot open a bar whose accessibility node is not clickable.
            emit(CollectionState.SCANNING_RESULTS, "输入页未打开，再点一次顶部输入框", 0)
            val fallbackClicked = service.clickTopSearchBar(forceCoordinate = true)
            AppPreferences.appendLog(context, "打开搜索输入页：坐标补点已提交=$fallbackClicked")
            if (waitForSearchInput(service, 1_200L) { true } != null) return true
        }
        return service.searchInputState() != null
    }


    /** 只在已显示的检索列表中选结果，排除输入联想页和地图上的站名。 */
    private fun searchResultList(snapshot: NodeSnapshot): NodeSnapshot? {
        var result: NodeSnapshot? = null
        fun walk(node: NodeSnapshot) {
            if (!node.bounds.isValid) return
            if (node.className.contains("WebView", ignoreCase = true)) {
                val label = node.text.ifBlank { node.contentDescription }.trim()
                val texts = collectTexts(node)
                if (label == "检索列表页" || (texts.any { it.trim() == "地点" } &&
                        texts.any { it.trim() == "攻略" })) {
                    result = node
                }
            }
            node.children.forEach(::walk)
        }
        walk(snapshot)
        return result
    }

    private fun sameResultPosition(first: Pair<Int, Int>, second: Pair<Int, Int>): Boolean =
        kotlin.math.abs(first.first - second.first) <= 8 &&
            kotlin.math.abs(first.second - second.second) <= 8

    private sealed interface SearchDestination {
        data class ListResult(val point: Pair<Int, Int>) : SearchDestination
        data class DirectDetail(val snapshot: NodeSnapshot) : SearchDestination
    }

    private suspend fun openSearchDestination(
        service: ChargingAccessibilityService,
        stationName: String,
        destination: SearchDestination,
    ): NodeSnapshot? = when (destination) {
        is SearchDestination.DirectDetail -> destination.snapshot
        is SearchDestination.ListResult -> {
            val point = destination.point
            AppPreferences.appendLog(context, "命中结果 ${point.first},${point.second}: $stationName")
            emit(CollectionState.OPENING_DETAIL, "已确认搜索结果，准备打开站点详情", 0, stationName)
            openResultAndWaitDetail(service, stationName, point)
        }
    }

    /** 只取详情 WebView 内的站名，不能拿顶部搜索词或地图标注证明进入了目标详情。 */
    private fun directDetailTitle(snapshot: NodeSnapshot, stationName: String): Pair<String, Rect>? {
        if (isSearchLoading(snapshot) || searchResultList(snapshot) != null) return null
        var title: Pair<String, Rect>? = null
        fun walk(node: NodeSnapshot) {
            if (node.className.contains("WebView", ignoreCase = true) && node.bounds.isValid) {
                val nodes = collectTextNodes(node)
                val header = nodes.firstOrNull { it.first.trim() == "地点详情" }
                if (header != null) {
                    val candidate = nodes.firstOrNull {
                        it.second.top >= header.second.bottom &&
                            it.second.top < minOf(snapshot.bounds.bottom, header.second.bottom + snapshot.bounds.height / 4) &&
                            it.second.height in 24..180 && it.second.width >= snapshot.bounds.width / 4 &&
                            (it.first.contains("充电") ||
                                StationNameNormalizer.normalize(it.first) == StationNameNormalizer.normalize(stationName))
                    }?.takeIf { (name, bounds) ->
                        val location = nodes.filter {
                            it.second.top >= bounds.bottom &&
                                it.second.top < minOf(snapshot.bounds.bottom, bounds.bottom + snapshot.bounds.height / 4) &&
                                !it.first.contains("充电")
                        }.joinToString(" ") { it.first }
                        directDetailNameMatches(stationName, name, location)
                    }
                    if (candidate != null) title = candidate
                }
            }
            node.children.forEach(::walk)
        }
        walk(snapshot)
        return title
    }

    /** 已确认详情结构后，允许无品牌任务与括号内完整专名相等的短站名。 */
    private fun directDetailNameMatches(stationName: String, candidateName: String, locationText: String): Boolean {
        if (stationNameMatchScore(stationName, candidateName, locationText) > 0) return true
        if (hasSpecificPart(stationName) || !hasSpecificPart(candidateName) ||
            hasNameEllipsis(stationName) || hasNameEllipsis(candidateName)
        ) return false
        // 只移除末尾充电站类型；不能吞掉专名内部文字、功率、站号或一期/二期。
        val suffixes = listOf("重卡充电站", "充电站", "充电桩", "快充站", "超充站")
        fun fullIdentity(name: String): String {
            val value = StationNameNormalizer.normalize(name)
            val suffix = suffixes.firstOrNull { value.endsWith(it) } ?: return ""
            return value.removeSuffix(suffix)
        }
        val targetIdentity = fullIdentity(stationName)
        val candidateIdentity = fullIdentity(wrappedSpecificName(candidateName))
        return targetIdentity.length >= 3 && targetIdentity != "新能源" &&
            targetIdentity == candidateIdentity
    }

    private suspend fun openResultAndWaitDetail(
        service: ChargingAccessibilityService,
        stationName: String,
        point: Pair<Int, Int>,
    ): NodeSnapshot? {
        checkStop()
        val snapshot = collectionSnapshot(service)
        if (snapshot != null && service.searchInputState() == null && directDetailTitle(snapshot, stationName) != null) {
            emit(CollectionState.OPENING_DETAIL, "目标详情已直接打开，继续读取", 0, stationName)
            return snapshot
        }
        val currentPoint = snapshot?.let { findResultPoint(it, stationName) }
        val confirmedPoint = if (currentPoint != null && sameResultPosition(point, currentPoint)) {
            currentPoint
        } else {
            emit(CollectionState.SCANNING_RESULTS, "列表仍在变化，重新确认目标站点位置", 0, stationName)
            when (val destination = waitForResult(service, stationName) ?: return null) {
                is SearchDestination.DirectDetail -> return destination.snapshot
                is SearchDestination.ListResult -> destination.point
            }
        }
        checkStop()
        if (!service.clickAt(confirmedPoint.first, confirmedPoint.second)) return null
        emit(CollectionState.OPENING_DETAIL, "已提交列表项点击，等待确认详情页打开", 0, stationName)
        delay(Random.nextLong(1_200L, 1_800L))
        return waitForDetail(service, stationName, 8_000L)
    }

    private fun isHenanSearchLocation(name: String): Boolean =
        name.filterNot { it.isWhitespace() }.removePrefix("河南省") in HENAN_SEARCH_LOCATIONS

    private data class SearchCity(val name: String, val bounds: Rect)
    private data class SearchCityOptions(val currentCity: String, val cities: List<SearchCity>)

    private fun preferredSearchLocations(stationName: String): List<String> {
        val name = stationName.filterNot { it.isWhitespace() }
        val explicitLocations = HENAN_SEARCH_LOCATIONS.filter { it.length >= 2 && it in name }
            .sortedWith(compareBy<String> { name.indexOf(it) }.thenByDescending { it.length })
        return buildList {
            addAll(explicitLocations)
            // 县区名称明确时，同时把所属地市排在其他地点之前。
            CityDistricts.cityDistricts.forEach { (city, districts) ->
                // 偃师、孟津常省略“区”；仅采用已确认简称，避免通用词误判城市。
                val knownAliases = setOf("偃师", "孟津")
                if (districts.any { district ->
                        district in explicitLocations ||
                            district.removeSuffix("区").let { it in knownAliases && it in name }
                    }) add("${city}市")
            }
            // 兼容“安阳某某充电站”等未写“市”的任务名称。
            addAll(CityDistricts.cityDistricts.keys.filter { it.length >= 2 && it in name }
                .sortedBy { name.indexOf(it) }.map { "${it}市" })
        }.distinct()
    }

    private fun preferredSearchCity(stationName: String, options: SearchCityOptions): String? {
        val available = listOf(options.currentCity) + options.cities.map { it.name }
        return preferredSearchLocations(stationName).firstNotNullOfOrNull { location ->
            available.firstOrNull {
                isHenanSearchLocation(it) &&
                    it.filterNot { char -> char.isWhitespace() }.removePrefix("河南省") == location
            }
        }
    }

    private fun searchCityOptions(snapshot: NodeSnapshot): SearchCityOptions? {
        val resultList = searchResultList(snapshot) ?: return null
        val nodes = collectTextNodes(resultList)
        val tabTop = nodes.filter { it.first.trim() in setOf("地点", "攻略") }
            .minOfOrNull { it.second.top }
        val headerNodes = nodes.filter { tabTop == null || it.second.bottom <= tabTop }
        // WebView 可能把整句提示拆开；先拼接识别提示，再定位提示下方的城市按钮。
        val header = headerNodes.joinToString("") { it.first.filterNot(Char::isWhitespace) }
        val localResults = header.contains("以下为本市结果") && header.contains("切换其他城市")
        val prompt = headerNodes.filter {
            val text = it.first.filterNot(Char::isWhitespace)
            text.contains("以下城市") || text.contains("还可以切换") ||
                (localResults && (text.contains("本市结果") || text.contains("切换其他城市") ||
                    text.contains("是否切换")))
        }.minByOrNull { it.second.height }
        val explicitCurrentCity = Regex("已切换为(\\p{IsHan}{1,14}?(?:市|县|区|地区|自治州|盟))结果")
            .find(header)?.groupValues?.get(1).orEmpty()
        val cityName = Regex("^\\p{IsHan}{1,14}(?:市|县|区|地区|自治州|盟)$")
        val currentCityBottom = headerNodes.filter { it.first.trim() == explicitCurrentCity }
            .minOfOrNull { it.second.bottom }
        val cityRowTop = prompt?.second?.bottom ?: currentCityBottom
        val cities = if (tabTop != null && (cityRowTop != null || localResults)) headerNodes.filter {
            cityName.matches(it.first.trim()) && (cityRowTop == null || it.second.top >= cityRowTop)
        }.map { SearchCity(it.first.trim(), it.second) }.distinctBy { it.name } else emptyList()
        // 首次搜索的“本市结果”提示没有“已切换为XX结果”，城市栏首项就是当前城市。
        // 仅在这类明确提示下采用首项，不能把任意地名误当当前城市；后续切换仍须核对明确标题。
        val currentCity = explicitCurrentCity.ifBlank {
            if (localResults) cities.firstOrNull()?.name.orEmpty() else ""
        }
        val hasCityHint = localResults || prompt != null || currentCity.isNotEmpty() ||
            header.contains("已切换为") || header.contains("切换至以下") ||
            header.contains("本市结果") || header.contains("切换其他城市")
        if (!hasCityHint) return null
        return SearchCityOptions(currentCity, cities)
    }

    /** 无匹配时刷新城市栏；单帧没读到入口不能直接判定站点不存在。 */
    private suspend fun confirmSearchCityOptions(
        service: ChargingAccessibilityService,
        stationName: String,
        initialSnapshot: NodeSnapshot,
        expectCityOptions: Boolean,
    ): NodeSnapshot {
        val initialOptions = searchCityOptions(initialSnapshot)
        if (initialOptions != null && initialOptions.currentCity.isNotBlank() && initialOptions.cities.isNotEmpty()) {
            emit(CollectionState.SCANNING_RESULTS,
                "已识别城市入口：当前${initialOptions.currentCity}，可选${initialOptions.cities.joinToString("、") { it.name }}", 0, stationName)
            return initialSnapshot
        }
        emit(CollectionState.SCANNING_RESULTS, "当前结果无匹配，刷新并复核城市切换入口", 0, stationName)
        val deadline = SystemClock.elapsedRealtime() + SEARCH_CITY_RECOVERY_TIMEOUT_MS
        var snapshot = initialSnapshot
        var sawCityHint = expectCityOptions || initialOptions != null
        var previousFingerprint: String? = null
        var stableReads = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            checkStop()
            service.refreshSearchResultNodes()
            delay(500L)
            checkStop()
            snapshot = collectionSnapshot(service)
                ?: throw IllegalStateException("城市入口重读时页面不可读，保留任务重试: $stationName")
            if (isSearchLoading(snapshot)) {
                stableReads = 0
                previousFingerprint = null
                continue
            }
            // 刷新期间可能迟到目标结果或直接进入详情，交由原流程连续核对后再点击。
            if (searchResultList(snapshot) == null || directDetailTitle(snapshot, stationName) != null) return snapshot
            val options = searchCityOptions(snapshot)
            if (options != null) {
                sawCityHint = true
                if (options.currentCity.isNotBlank() && options.cities.isNotEmpty()) {
                    emit(CollectionState.SCANNING_RESULTS,
                        "已读到城市入口：当前${options.currentCity}，可选${options.cities.joinToString("、") { it.name }}", 0, stationName)
                    return snapshot
                }
            }
            val fingerprint = searchResultFingerprint(snapshot)
            if (options == null && fingerprint.isNotEmpty() && unreadableSearchCards(snapshot).isEmpty()) {
                stableReads = if (fingerprint == previousFingerprint) stableReads + 1 else 1
                previousFingerprint = fingerprint
            } else {
                stableReads = 0
                previousFingerprint = null
            }
        }
        if (isSearchLoading(snapshot) || sawCityHint || stableReads < 3) {
            throw IllegalStateException("城市入口或搜索结果尚未读全，不能跳过，保留任务重试: $stationName")
        }
        emit(CollectionState.SCANNING_RESULTS, "刷新后连续确认结果稳定，当前页面无城市切换入口", 0, stationName)
        return snapshot
    }

    private fun isSearchLoading(snapshot: NodeSnapshot): Boolean {
        // “正在检索笔记”是列表页脚，不能当作整页检索中的遮罩。
        val loading = Regex("^(?:正在检索|正在搜索|正在加载|检索中|搜索中|加载中)(?:中)?[.…⋯。\\s]*(?:[,，]?\\s*请稍[候等后][.…⋯。\\s]*)?$")
        return collectTextNodes(snapshot).any { (text, bounds) ->
            bounds.bottom > snapshot.bounds.top && bounds.top < snapshot.bounds.bottom &&
                bounds.right > snapshot.bounds.left && bounds.left < snapshot.bounds.right &&
                loading.matches(text.trim())
        }
    }

    private fun searchResultFingerprint(snapshot: NodeSnapshot): String {
        val list = searchResultList(snapshot) ?: return ""
        val nodes = collectTextNodes(list)
        val tabBottom = nodes.filter { it.first.trim() in setOf("地点", "攻略") }
            .maxOfOrNull { it.second.bottom } ?: return ""
        return nodes.filter {
            it.second.top >= tabBottom && it.second.top < snapshot.bounds.bottom &&
                !it.first.contains("正在检索笔记") && !it.first.contains("没有找到想要的结果吗")
        }.joinToString("|") { it.first }
    }

    /** 百度有时只暴露某条结果的空容器，不能据此判断该站不存在。 */
    private fun searchResultCards(snapshot: NodeSnapshot): List<NodeSnapshot> {
        val list = searchResultList(snapshot) ?: return emptyList()
        val tabBottom = collectTextNodes(list).filter { it.first.trim() in setOf("地点", "攻略") }
            .maxOfOrNull { it.second.bottom } ?: return emptyList()
        val cards = mutableListOf<NodeSnapshot>()
        fun walk(node: NodeSnapshot) {
            val rows = node.children.filter {
                it.bounds.isValid && it.bounds.width >= snapshot.bounds.width * 0.75f &&
                    it.bounds.height in 100..(snapshot.bounds.height / 5) &&
                    it.bounds.top >= tabBottom && it.bounds.bottom <= snapshot.bounds.bottom &&
                    collectTexts(it).none { text -> text.contains("点击查看全部") }
            }
            // 用同组可读卡片的“到这去”识别结果行，排除普通空白布局、占位和页脚。
            val readableRows = rows.filter { row -> collectTexts(row).any { it.trim() == "到这去" } }
            if (rows.size >= 2 && readableRows.isNotEmpty()) {
                cards += rows
            } else {
                // “单条主结果 + 分类推荐”没有并排的同级结果行，主结果自身仍可确认地址归属。
                cards += readableRows
            }
            node.children.forEach(::walk)
        }
        walk(list)
        return cards.distinctBy { it.bounds }
    }

    private fun unreadableSearchCards(snapshot: NodeSnapshot): List<Rect> =
        searchResultCards(snapshot).filter { collectTexts(it).isEmpty() }.map { it.bounds }

    private suspend fun recoverSearchResultText(
        service: ChargingAccessibilityService,
        snapshot: NodeSnapshot,
        stationName: String,
    ) {
        emit(CollectionState.SCANNING_RESULTS, "结果卡片文字未读全，刷新并小幅滚动后重新读取", 0, stationName)
        service.refreshSearchResultNodes()
        delay(350L)
        checkStop()
        val refreshed = collectionSnapshot(service) ?: return
        if (isSearchLoading(refreshed) || unreadableSearchCards(refreshed).isEmpty()) return
        val list = searchResultList(refreshed) ?: return
        // 在列表内上移再移回，触发 WebView 更新文字节点，不能直接盲点第一条。
        val tabBottom = collectTextNodes(list).filter { it.first.trim() in setOf("地点", "攻略") }
            .maxOfOrNull { it.second.bottom } ?: return
        val top = tabBottom + 40
        val bottom = minOf(list.bounds.bottom, service.screenHeightPx() - 180) - 40
        if (bottom - top < 220) return
        val x = (snapshot.bounds.width * 0.45f).toInt()
        val startY = (top + bottom) / 2 + 70
        val endY = startY - 140
        service.swipe(x, startY, x, endY, 450L)
        delay(650L)
        checkStop()
        val afterScroll = collectionSnapshot(service) ?: return
        if (isSearchLoading(afterScroll) || searchResultList(afterScroll) == null) return
        service.swipe(x, endY, x, startY, 450L)
        delay(650L)
        checkStop()
        service.refreshSearchResultNodes()
    }

    /** 所有已确认无匹配的出口统一补搜一次，避免城市列表耗尽时绕过补搜。 */
    private suspend fun submitSearchWithOneRetry(
        service: ChargingAccessibilityService,
        stationName: String,
    ): SearchDestination? {
        for (attempt in 1..2) {
            checkStop()
            if (attempt == 2) {
                emit(CollectionState.SCANNING_RESULTS,
                    "首次搜索无匹配且无可继续切换的城市，重新打开搜索框再搜一次", 0, stationName)
            }
            // 第二次必须重新打开输入页，并重新写入及回读站名，不能在结果页直接补点。
            if (attempt == 2 || service.searchInputState()?.let { it.ready && it.text == stationName } != true) {
                prepareSearchInput(service, stationName)
            }
            checkStop()
            val action = if (attempt == 1) "点搜索并查找结果" else "再次点击搜索，等待新结果"
            emit(CollectionState.SCANNING_RESULTS,
                "$action（第${attempt}/2次，查找上限${SEARCH_RESULT_TIMEOUT_MS / 1_000}秒）", 0, stationName)
            if (!service.clickSearchSubmitButton()) {
                throw IllegalStateException("第${attempt}次搜索按钮点击失败: $stationName")
            }
            delay(Random.nextLong(2_200L, 3_200L))
            val destination = findResultAcrossCities(service, stationName)
            if (destination != null) return destination
        }
        emit(CollectionState.SCANNING_RESULTS,
            "第二次搜索及可切换城市仍无匹配，跳过本站", 0, stationName)
        return null
    }

    private suspend fun findResultAcrossCities(
        service: ChargingAccessibilityService,
        stationName: String,
    ): SearchDestination? {
        val visitedCities = linkedSetOf<String>()
        val offeredCities = linkedSetOf<String>()
        val excludedCities = linkedSetOf<String>()
        var requiredCity: String? = null
        while (true) {
            checkStop()
            val destination = waitForResult(
                service, stationName, requiredCity,
                prioritizeNamedCity = requiredCity == null && visitedCities.isEmpty(),
            )
            if (destination != null) return destination
            val beforeCityCheck = collectionSnapshot(service)
                ?: throw IllegalStateException("无法确认当前城市搜索结果: $stationName")
            val snapshot = confirmSearchCityOptions(
                service, stationName, beforeCityCheck,
                expectCityOptions = offeredCities.isNotEmpty() || requiredCity != null,
            )
            if (isSearchLoading(snapshot)) throw IllegalStateException("搜索结果仍在加载，不能跳过: $stationName")
            if (searchResultList(snapshot) == null) {
                // 最后一轮查找后也可能发生自动跳转，重新确认页面而不是上报无匹配。
                return waitForResult(service, stationName, requiredCity)
                    ?: throw IllegalStateException("搜索页面发生变化，未确认目标详情，保留任务重试: $stationName")
            }
            val options = searchCityOptions(snapshot)
            // 重读城市栏期间结果也可能更新，仍需按原规则连续两次核对目标。
            if ((options == null || options.currentCity.isBlank() || isHenanSearchLocation(options.currentCity)) &&
                findResultPoint(snapshot, stationName) != null) {
                val recovered = waitForResult(service, stationName, requiredCity,
                    prioritizeNamedCity = requiredCity == null && visitedCities.isEmpty())
                if (recovered != null) return recovered
                // 等待期间页面可能变化，重新读取，不能使用等待前的城市坐标。
                if (collectionSnapshot(service)?.let(::searchCityOptions) != options) continue
            }
            // 首次出现切换入口时，先到任务名称对应地点，不先扫描其他城市。
            if (requiredCity == null && visitedCities.isEmpty() && options != null &&
                options.currentCity.isNotBlank()) {
                val preferredCity = preferredSearchCity(stationName, options)
                    ?.takeIf { it != options.currentCity }
                if (preferredCity != null) {
                    // 保存当前已出现的其他河南入口，目标地点无匹配时继续查找。
                    offeredCities += options.cities.map { it.name }.filter(::isHenanSearchLocation)
                    emit(CollectionState.SCANNING_RESULTS,
                        "任务名称含对应地名，优先切换至$preferredCity", 0, stationName)
                    val directDetail = switchSearchCity(service, stationName, preferredCity)
                    if (directDetail != null) return SearchDestination.DirectDetail(directDetail)
                    requiredCity = preferredCity
                    continue
                }
            }
            // 省外结果不参与匹配，也不必为读取省外卡片耗时；转向河南地点。
            val currentOutsideHenan = options?.currentCity?.takeIf { it.isNotBlank() }
                ?.let { !isHenanSearchLocation(it) } == true
            if (!currentOutsideHenan && unreadableSearchCards(snapshot).isNotEmpty()) {
                throw IllegalStateException("搜索列表仍有未读卡片，不能跳过: $stationName")
            }
            if (options == null) {
                if (offeredCities.isNotEmpty()) {
                    throw IllegalStateException("城市切换入口尚未加载，不能判断所有城市均无匹配: $stationName")
                }
                return null
            }
            if (options.currentCity.isBlank() || options.cities.isEmpty()) {
                throw IllegalStateException("尚未确认当前城市及可切换城市: $stationName")
            }
            if (requiredCity != null && options.currentCity != requiredCity) {
                throw IllegalStateException("城市切换尚未生效，不能跳过: $stationName")
            }
            if (isHenanSearchLocation(options.currentCity)) visitedCities += options.currentCity
            val newlyExcluded = options.cities.map { it.name }
                .filter { !isHenanSearchLocation(it) && excludedCities.add(it) }
            if (newlyExcluded.isNotEmpty()) {
                emit(CollectionState.SCANNING_RESULTS, "仅在河南省内切换，忽略地点：${newlyExcluded.joinToString("、")}", 0, stationName)
            }
            offeredCities += options.cities.map { it.name }.filter(::isHenanSearchLocation)
            val preferredLocations = preferredSearchLocations(stationName)
            val nextCity = offeredCities.filter { it !in visitedCities }
                .minByOrNull { city ->
                    preferredLocations.indexOf(city.filterNot { it.isWhitespace() }.removePrefix("河南省"))
                        .takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
            if (nextCity == null) {
                val message = if (visitedCities.isEmpty()) "当前没有可切换的河南省内地点" else
                    "已查找${visitedCities.joinToString("、")}，当前可切换的河南地点均无匹配站点"
                emit(CollectionState.SCANNING_RESULTS, message, 0, stationName)
                return null
            }
            emit(CollectionState.SCANNING_RESULTS, "${options.currentCity}未匹配，切换至$nextCity", 0, stationName)
            val directDetail = switchSearchCity(service, stationName, nextCity)
            if (directDetail != null) return SearchDestination.DirectDetail(directDetail)
            requiredCity = nextCity
        }
    }

    private suspend fun switchSearchCity(
        service: ChargingAccessibilityService,
        stationName: String,
        city: String,
    ): NodeSnapshot? {
        checkStop()
        check(isHenanSearchLocation(city)) { "禁止切换至河南省外或未确认归属的地点: $city" }
        val before = collectionSnapshot(service)
            ?: throw IllegalStateException("切换城市前无法读取搜索页: $city")
        if (isSearchLoading(before)) throw IllegalStateException("搜索仍在加载，暂不能切换至$city")
        val target = searchCityOptions(before)?.cities?.firstOrNull { it.name == city }
            ?: throw IllegalStateException("未找到可点击的城市入口: $city")
        val oldFingerprint = searchResultFingerprint(before)
        if (!service.clickAt(target.bounds.centerX, target.bounds.centerY)) {
            throw IllegalStateException("城市入口点击失败: $city")
        }
        emit(CollectionState.SCANNING_RESULTS, "已点击$city，等待检索结束及新结果出现", 0, stationName)
        val deadline = SystemClock.elapsedRealtime() + SEARCH_LOADING_TIMEOUT_MS
        var previousFingerprint: String? = null
        var reportedLoading = false
        var previousDetailTitle: Pair<String, Rect>? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            checkStop()
            val snapshot = collectionSnapshot(service)
            val detailTitle = snapshot?.let { directDetailTitle(it, stationName) }
            if (snapshot != null && detailTitle != null && service.searchInputState() == null) {
                val previous = previousDetailTitle
                if (previous != null && previous.first == detailTitle.first &&
                    sameResultPosition(previous.second.centerX to previous.second.centerY,
                        detailTitle.second.centerX to detailTitle.second.centerY)) {
                    emit(CollectionState.OPENING_DETAIL, "切换城市后直接进入目标详情，站名已核对", 0, stationName)
                    return snapshot
                }
                previousDetailTitle = detailTitle
                previousFingerprint = null
                delay(350L)
                continue
            }
            previousDetailTitle = null
            if (snapshot != null && isSearchLoading(snapshot)) {
                previousFingerprint = null
                if (!reportedLoading) {
                    emit(CollectionState.SCANNING_RESULTS, "$city 正在检索，等待加载完成", 0, stationName)
                    reportedLoading = true
                }
            } else if (snapshot != null && searchCityOptions(snapshot)?.currentCity == city) {
                val fingerprint = searchResultFingerprint(snapshot)
                // 城市标题可能先更新：须等加载结束或列表内容更新，再连续两次确认结果稳定。
                // 两个城市也可能返回相同结果；已观察到完整加载过程时允许内容相同。
                if (fingerprint.isNotEmpty() && (reportedLoading || fingerprint != oldFingerprint)) {
                    if (fingerprint == previousFingerprint) {
                        emit(CollectionState.SCANNING_RESULTS, "已确认切换至$city，新城市结果已加载", 0, stationName)
                        return null
                    }
                    previousFingerprint = fingerprint
                } else {
                    previousFingerprint = null
                }
            } else {
                previousFingerprint = null
            }
            delay(350L)
        }
        throw IllegalStateException("切换至$city 后检索加载超时，保留任务重试: $stationName")
    }

    private suspend fun waitForResult(
        service: ChargingAccessibilityService,
        stationName: String,
        requiredCity: String? = null,
        prioritizeNamedCity: Boolean = false,
    ): SearchDestination? {
        val startedAt = SystemClock.elapsedRealtime()
        val hardDeadline = startedAt + SEARCH_LOADING_TIMEOUT_MS + SEARCH_RESULT_TIMEOUT_MS
        var resultDeadline: Long? = null
        var loadingStartedAt: Long? = startedAt
        var previousPoint: Pair<Int, Int>? = null
        var previousDetailTitle: Pair<String, Rect>? = null
        var sawTarget = false
        var reportedLoading = false
        var attemptedTextRecovery = false
        var expectedCardCount = 0
        emit(CollectionState.SCANNING_RESULTS, "等待搜索结果，识别列表或直接打开的详情页", 0, stationName)
        while (SystemClock.elapsedRealtime() < hardDeadline) {
            checkStop()
            val now = SystemClock.elapsedRealtime()
            val snapshot = collectionSnapshot(service)
            val resultList = snapshot?.let { searchResultList(it) }
            val detailTitle = snapshot?.let { directDetailTitle(it, stationName) }
            if (snapshot != null && detailTitle != null && service.searchInputState() == null) {
                val previous = previousDetailTitle
                if (previous != null && previous.first == detailTitle.first &&
                    sameResultPosition(previous.second.centerX to previous.second.centerY,
                        detailTitle.second.centerX to detailTitle.second.centerY)) {
                    emit(CollectionState.OPENING_DETAIL, "搜索后直接进入目标详情，详情站名连续两次核对一致", 0, stationName)
                    return SearchDestination.DirectDetail(snapshot)
                }
                previousDetailTitle = detailTitle
                previousPoint = null
                delay(350L)
                continue
            }
            previousDetailTitle = null
            val ready = snapshot != null && resultList != null && !isSearchLoading(snapshot) &&
                service.searchInputState() == null &&
                (requiredCity == null || searchCityOptions(snapshot)?.currentCity == requiredCity)
            if (ready) {
                val cityOptions = searchCityOptions(snapshot)
                if (prioritizeNamedCity && cityOptions != null && cityOptions.currentCity.isNotBlank()) {
                    val preferredCity = preferredSearchCity(stationName, cityOptions)
                    if (preferredCity != null && preferredCity != cityOptions.currentCity) {
                        return null
                    }
                }
                val currentLocation = cityOptions?.currentCity.orEmpty()
                if (currentLocation.isNotBlank() && !isHenanSearchLocation(currentLocation)) {
                    emit(CollectionState.SCANNING_RESULTS, "当前地点${currentLocation}不在河南省内，查找河南切换入口", 0, stationName)
                    return null
                }
                loadingStartedAt = null
                reportedLoading = false
                val deadline = resultDeadline ?: (now + SEARCH_RESULT_TIMEOUT_MS).also { resultDeadline = it }
                val point = findResultPoint(snapshot, stationName)
                if (point != null) sawTarget = true
                val previous = previousPoint
                if (point != null && previous != null && sameResultPosition(previous, point)) {
                    findResultPoint(snapshot, stationName, logMatch = true)
                    emit(CollectionState.SCANNING_RESULTS, "结果列表已出现，目标位置连续两次核对一致", 0, stationName)
                    return SearchDestination.ListResult(point)
                }
                previousPoint = point
                val cards = searchResultCards(snapshot)
                val unreadableCards = cards.filter { collectTexts(it).isEmpty() }.map { it.bounds }
                if (unreadableCards.isNotEmpty()) expectedCardCount = maxOf(expectedCardCount, cards.size)
                // 重读时若卡片移出了可读区域，也不能将其当作已读完。
                val incompleteCards = unreadableCards.isNotEmpty() || cards.size < expectedCardCount
                if (point == null && unreadableCards.isNotEmpty() && !attemptedTextRecovery) {
                    attemptedTextRecovery = true
                    previousPoint = null
                    AppPreferences.appendLog(context, "搜索列表存在空文字卡片: ${unreadableCards.joinToString()}，刷新后重读")
                    recoverSearchResultText(service, snapshot, stationName)
                    continue
                }
                val noResults = collectTextNodes(resultList).any {
                    it.second.top < snapshot.bounds.bottom && it.second.bottom > snapshot.bounds.top &&
                        it.first.trim() in setOf("没有找到", "没有找到相关结果", "暂无结果", "无搜索结果")
                }
                if (point == null && noResults && !incompleteCards) return null
                if (now >= deadline) {
                    if (incompleteCards) {
                        throw IllegalStateException("搜索结果卡片文字仍未读全，不能判断目标不存在，保留任务重试: $stationName")
                    }
                    if (sawTarget) throw IllegalStateException("搜索结果位置尚未稳定，未点击目标站点: $stationName")
                    return null
                }
            } else {
                previousPoint = null
                resultDeadline = null
                val loadingSince = loadingStartedAt ?: now.also { loadingStartedAt = it }
                if (!reportedLoading) {
                    emit(CollectionState.SCANNING_RESULTS, "正在检索或页面尚未确认，等待列表或目标详情", 0, stationName)
                    reportedLoading = true
                }
                if (now - loadingSince >= SEARCH_LOADING_TIMEOUT_MS) break
            }
            delay(350L)
        }
        throw IllegalStateException("搜索后未确认结果列表或目标详情，保留任务重试: $stationName")
    }

    /** 在结果列表下半屏寻找与目标站名匹配的卡片。 */
    private fun findResultPoint(
        snapshot: NodeSnapshot,
        stationName: String,
        logMatch: Boolean = false,
    ): Pair<Int, Int>? {
        if (isSearchLoading(snapshot)) return null
        val resultList = searchResultList(snapshot) ?: return null
        val target = StationNameNormalizer.normalize(stationName)
        if (target.isEmpty()) return null
        // 站名可能也出现在“已切换至…仍然在…搜索”的链接中。
        // 真正的结果卡片在“地点/攻略”栏下方，不能点击提示链接。
        var listTop = maxOf(340, resultList.bounds.top)
        fun findTabs(node: NodeSnapshot) {
            val label = node.text.ifBlank { node.contentDescription }.trim()
            if (node.bounds.isValid && label in setOf("地点", "攻略")) {
                listTop = maxOf(listTop, node.bounds.bottom)
            }
            node.children.forEach(::findTabs)
        }
        findTabs(resultList)
        val screenWidth = snapshot.bounds.width.takeIf { it > 0 } ?: 1080
        val screenHeight = snapshot.bounds.height.takeIf { it > 0 } ?: 2400
        val safeLeft = maxOf(16, (screenWidth * 0.02f).toInt())
        val maxClickY = screenHeight * 0.85f
        var best: Pair<Int, Int>? = null
        var bestLabel = ""
        var bestLocation = ""
        var bestScore = 0
        val resultCards = searchResultCards(snapshot)
        var bestHeight = Int.MAX_VALUE
        val excludedLabels = listOf("打车", "跨城", "到这去", "导航", "路线", "收藏", "分享", "周边", "报错")
        fun walk(node: NodeSnapshot) {
            val label = node.text.ifBlank { node.contentDescription }.trim()
            // Ignore narrow/off-screen placeholders such as the x=5 node from the failure log.
            val inTitleArea = node.bounds.centerX >= safeLeft &&
                node.bounds.centerX <= screenWidth * 0.75f &&
                node.bounds.width >= 32 && node.bounds.height >= 12
            val excluded = excludedLabels.any { label.contains(it) }
            // 跳过整块列表容器：高度过大时它的中心点会落到别的行上
            val isLargeContainer = node.bounds.height > 320
            if (label.isNotEmpty() && node.enabled && node.bounds.isValid && !excluded && !isLargeContainer &&
                inTitleArea && node.bounds.top >= listTop && node.bounds.centerY <= maxClickY
            ) {
                // 地址只能来自本标题所属的结果卡片，不能借用其它行的城市。
                val card = resultCards.filter {
                    it.bounds.left <= node.bounds.left && it.bounds.right >= node.bounds.right &&
                        it.bounds.top <= node.bounds.top && it.bounds.bottom >= node.bounds.bottom
                }.minByOrNull { it.bounds.height }
                val location = card?.let { row ->
                    collectTextNodes(row).filter {
                        it.second.top >= node.bounds.bottom && !it.first.contains("充电")
                    }.joinToString(" ") { it.first }
                }.orEmpty()
                val score = stationNameMatchScore(stationName, label, location)
                val listBonus = if (node.bounds.top >= (snapshot.bounds.height * 0.4f).toInt()) 200 else 0
                val weighted = score + listBonus
                // 同分时选更小的节点（单行标题），避免命中整块容器
                if (score > 0 && (weighted > bestScore ||
                    (weighted == bestScore && node.bounds.height < bestHeight))
                ) {
                    bestScore = weighted
                    bestHeight = node.bounds.height
                    best = node.bounds.centerX to node.bounds.centerY
                    bestLabel = label
                    bestLocation = location
                }
            }
            node.children.forEach(::walk)
        }
        walk(resultList)
        if (best != null && logMatch) {
            AppPreferences.appendLog(context, "搜索结果匹配: $stationName -> $bestLabel（得分 $bestScore）")
            if (bestLocation.isNotBlank()) {
                AppPreferences.appendLog(context, "匹配结果所在行地址: ${bestLocation.take(180)}")
            }
        }
        return best
    }

    private suspend fun waitForDetail(
        service: ChargingAccessibilityService,
        stationName: String,
        timeoutMs: Long = 12_000L,
    ): NodeSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            checkStop()
            val snapshot = collectionSnapshot(service)
            if (snapshot != null) {
                val texts = collectTexts(snapshot)
                val excerpt = texts.take(12).joinToString("|").take(300)
                if (texts.any { it.contains("人机验证") || it.contains("安全验证") || it.contains("拖动滑块") }) {
                    throw HumanVerificationException("人机验证", excerpt)
                }
                // 仍在结果列表时不能认作已进详情，更不能上报“无电价/桩信息”。
                if (searchResultList(snapshot) == null && service.searchInputState() == null) {
                    val hasEntry = texts.any { it.contains("查看价格详情") }
                    val hasDetailChrome = texts.any { it.contains("地点详情") } ||
                        texts.any { it.trim() == "添加行程" } ||
                        texts.any { it.trim() == "写评论" }
                    if (hasEntry || hasDetailChrome) {
                        emit(CollectionState.OPENING_DETAIL, "已确认站点详情页打开", 0, stationName)
                        return snapshot
                    }
                }
            }
            delay(700L)
        }
        return null
    }

    private fun stationNameMatchScore(stationName: String, label: String, locationText: String = ""): Int {
        val target = StationNameNormalizer.normalize(stationName)
        if (target.isEmpty()) return 0
        val targetBrand = brandOf(stationName)
        val targetCore = specificCore(stationName)
        val value = StationNameNormalizer.normalize(label)
        return when {
            value.length < 4 -> 0
            value == target && value.length >= 6 -> 1000
            value.length >= 6 && target.startsWith(value) -> 800 + value.length
            target.length >= 6 && value.startsWith(target) -> 900 + target.length
            else -> {
                val candidateBrand = brandOf(label)
                val candidateCore = specificCore(label)
                val brandOk = targetBrand.isNotEmpty() && candidateBrand.isNotEmpty() &&
                    (targetBrand == candidateBrand ||
                        targetBrand.startsWith(candidateBrand) ||
                        candidateBrand.startsWith(targetBrand))
                val coreOk = targetCore.length >= 4 && candidateCore.length >= 4 &&
                    (targetCore.startsWith(candidateCore) ||
                        candidateCore.startsWith(targetCore) ||
                        targetCore.contains(candidateCore) ||
                        candidateCore.contains(targetCore))
                // 百度有时只显示括号内的实际站名，不显示任务名的品牌外壳。
                // 此时按实际站名比较；普通短前缀不能替代完整站名，只有
                // 页面明确显示省略号时才允许截断前缀，避免误点相近站点。
                val targetHasWrapper = hasSpecificPart(stationName)
                val candidateHasWrapper = hasSpecificPart(label)
                val targetIdentity = targetCore.removeSuffix("站")
                val candidateIdentity = candidateCore.removeSuffix("站")
                // 同品牌且完整括号站名一致时，允许“极速”这类较短的实际站名。
                // 只去掉括号开头重复的品牌；保留超充描述、站号和功率等区别。
                val targetSpecificName = wrappedSpecificName(stationName)
                val candidateSpecificName = wrappedSpecificName(label)
                val brandedSpecificMatch = targetHasWrapper && candidateHasWrapper &&
                    targetBrand == candidateBrand && targetCore.length >= 2 && candidateCore.length >= 2 &&
                    targetSpecificName.length >= 4 && targetSpecificName == candidateSpecificName
                val unwrappedCoreMatch = targetHasWrapper != candidateHasWrapper &&
                    targetIdentity.length >= 4 && candidateIdentity.length >= 4 &&
                    (targetIdentity == candidateIdentity ||
                        (hasNameEllipsis(stationName) && candidateIdentity.startsWith(targetIdentity)) ||
                        (hasNameEllipsis(label) && targetIdentity.startsWith(candidateIdentity)))
                // 任务括号内可能带城市前缀，而百度直接展示实际站名：
                // “焦作智航新能源充电站” -> “智航新能源超级充电站”。
                // 仅当同一结果行地址明确属于该城市，且剩余核心完整相等时放行。
                val localCoreMatch = targetHasWrapper != candidateHasWrapper &&
                    (cityPrefixedIdentityMatches(targetIdentity, candidateIdentity, locationText) ||
                        addressSupportedIdentityMatches(targetIdentity, candidateIdentity, locationText))
                when {
                    genericWrapperSpecificMatches(stationName, label) -> 675 + targetCore.length
                    repeatedChargingBrandMatches(stationName, label) ->
                        655 + minOf(targetCore.length, candidateCore.length)
                    brandedSpecificMatch -> 650 + targetSpecificName.length
                    BaiduStationIdentityMatcher.matchesAddressSupportedAdministrativePrefix(
                        stationName, label, locationText,
                    ) -> 649 + minOf(targetCore.length, candidateCore.length)
                    BaiduStationIdentityMatcher.matchesAddressSupportedLocalityVariant(
                        stationName, label, locationText,
                    ) -> 649 + minOf(targetCore.length, candidateCore.length)
                    BaiduStationIdentityMatcher.matchesDescriptorVariant(stationName, label) ->
                        648 + minOf(targetCore.length, candidateCore.length)
                    BaiduStationIdentityMatcher.matchesVillageStationVariant(stationName, label) ->
                        646 + minOf(targetCore.length, candidateCore.length)
                    BaiduStationIdentityMatcher.matchesAffordablePileVariant(stationName, label) ->
                        646 + minOf(targetCore.length, candidateCore.length)
                    brandedStationSuffixMatches(stationName, label) ->
                        645 + minOf(targetCore.length, candidateCore.length)
                    addressSupportedBusinessQualifierMatches(stationName, label, locationText) ->
                        640 + minOf(targetCore.length, candidateCore.length)
                    addressSupportedAbbreviatedSiteMatches(stationName, label, locationText) ->
                        635 + minOf(targetCore.length, candidateCore.length)
                    unwrappedBrandIdentityMatches(stationName, label, locationText) ->
                        625 + minOf(targetCore.length, candidateCore.length)
                    brandOk && coreOk -> 600 + minOf(targetCore.length, candidateCore.length)
                    unwrappedCoreMatch -> 550 + minOf(targetIdentity.length, candidateIdentity.length)
                    localCoreMatch -> 500 + minOf(targetIdentity.length, candidateIdentity.length)
                    else -> 0
                }
            }
        }
    }

    /** 外层“品牌充电汽车充电站”重复了充电描述；括号内完整站名仍须一致。 */
    private fun repeatedChargingBrandMatches(stationName: String, candidateName: String): Boolean {
        if (!hasSpecificPart(stationName) || !hasSpecificPart(candidateName) ||
            hasNameEllipsis(stationName) || hasNameEllipsis(candidateName)
        ) return false
        val targetBrand = brandOf(stationName)
        val candidateBrand = brandOf(candidateName)
        if (targetBrand == candidateBrand) return false
        fun collapseRepeatedCharging(brand: String): String =
            if (brand.endsWith("充电充电站")) brand.removeSuffix("充电充电站") + "充电站" else brand
        val brand = collapseRepeatedCharging(targetBrand)
        if (brand.removeSuffix("充电站").length < 2 ||
            brand != collapseRepeatedCharging(candidateBrand)
        ) return false
        fun identity(name: String): String {
            val open = name.indexOfFirst { it == '(' || it == '（' }
            val close = name.indexOfLast { it == ')' || it == '）' }
            return StationNameNormalizer.normalize(name.substring(open + 1, close)).removeSuffix("充电站")
        }
        val targetIdentity = identity(stationName)
        // 只消除末尾附加的“充电站”；保留快充、站号、功率及全部地点文字。
        return targetIdentity.length >= 4 && targetIdentity == identity(candidateName)
    }

    /** 同品牌下，括号末尾可重复品牌或简写成“电站”，完整地点专名仍须相等。 */
    private fun brandedStationSuffixMatches(stationName: String, candidateName: String): Boolean {
        if (!hasSpecificPart(stationName) || !hasSpecificPart(candidateName) ||
            hasNameEllipsis(stationName) || hasNameEllipsis(candidateName) ||
            brandOf(stationName) != brandOf(candidateName)
        ) return false
        val brand = brandOf(stationName)
        val stationSuffix = listOf("重卡充电站", "充电站", "充电桩", "快充站", "超充站")
            .firstOrNull { brand.endsWith(it) } ?: return false
        val brandStem = brand.removeSuffix(stationSuffix)
        if (brandStem.length < 2) return false
        val brandedSuffixes = listOf(brandStem + "充电站", brandStem + "电站")
            .sortedByDescending { it.length }
        fun identity(name: String): Pair<String, Boolean>? {
            val specific = wrappedSpecificName(name)
            val repeatedBrandSuffix = brandedSuffixes.firstOrNull { specific.endsWith(it) }
            if (repeatedBrandSuffix != null) return specific.removeSuffix(repeatedBrandSuffix) to true
            val suffix = listOf(
                "重卡快速充电站", "重卡充电站", "快速充电站", "超级充电站",
                "充电站", "充电桩", "快充站", "超充站", "电站",
            ).firstOrNull { specific.endsWith(it) } ?: return null
            return specific.removeSuffix(suffix) to false
        }
        val target = identity(stationName) ?: return false
        val candidate = identity(candidateName) ?: return false
        // 仅处理一侧末尾明确重复本品牌的情形；不全局删除“电站”或品牌词。
        // “新野龙背盈通物流云快充电站”与“新野龙背盈通物流电站”专名完整相等。
        return (target.second || candidate.second) && target.first.length >= 4 &&
            target.first == candidate.first
    }

    /** 同品牌站名只差“大型”时，须由该结果自身地址提供完整场所名佐证。 */
    private fun addressSupportedBusinessQualifierMatches(
        stationName: String,
        candidateName: String,
        locationText: String,
    ): Boolean {
        if (!hasSpecificPart(stationName) || !hasSpecificPart(candidateName) ||
            hasNameEllipsis(stationName) || hasNameEllipsis(candidateName) || locationText.isBlank() ||
            brandOf(stationName) != brandOf(candidateName)
        ) return false
        val target = wrappedSpecificName(stationName)
        val candidate = wrappedSpecificName(candidateName)
        val longerName = when {
            target != candidate && target.replaceFirst("大型", "") == candidate -> stationName
            target != candidate && candidate.replaceFirst("大型", "") == target -> candidateName
            else -> return false
        }
        // 保留其它名称差异；不能借用其它结果的地址或搜索框里的任务名。
        val fullIdentity = specificCore(longerName).removeSuffix("站")
        val shortIdentity = fullIdentity.replaceFirst("大型", "")
        return shortIdentity.length >= 4 &&
            StationNameNormalizer.normalize(locationText).contains(fullIdentity)
    }

    /** 同品牌结果可简称乡镇站点，但省略的场所名必须由该行地址完整佐证。 */
    private fun addressSupportedAbbreviatedSiteMatches(
        stationName: String,
        candidateName: String,
        locationText: String,
    ): Boolean {
        if (!hasSpecificPart(stationName) || !hasSpecificPart(candidateName) ||
            hasNameEllipsis(stationName) || hasNameEllipsis(candidateName) ||
            locationText.isBlank() || brandOf(stationName) != brandOf(candidateName)
        ) return false
        fun siteName(name: String): String {
            val specific = wrappedSpecificName(name)
            val suffix = listOf(
                "重卡快速充电站", "重卡充电站", "快速充电站", "超级充电站",
                "充电站", "充电桩", "快充站", "超充站",
            ).firstOrNull { specific.endsWith(it) } ?: return ""
            return specific.removeSuffix(suffix)
        }
        val targetSite = siteName(stationName)
        val candidateSite = siteName(candidateName)
        if (targetSite.isEmpty() || candidateSite.isEmpty() || targetSite == candidateSite) return false
        val longer = if (targetSite.length > candidateSite.length) targetSite else candidateSite
        val shorter = if (targetSite.length > candidateSite.length) candidateSite else targetSite
        // 简称必须是完整乡镇名；功率、站号、一期/二期等不能作为省略部分被吞掉。
        if (!Regex("^\\p{IsHan}{2,12}$").matches(shorter) || !longer.startsWith(shorter)) return false
        val remainder = longer.removePrefix(shorter)
        val administrativeSuffix = listOf("街道", "镇", "乡")
            .firstOrNull { remainder.startsWith(it) } ?: return false
        val place = remainder.removePrefix(administrativeSuffix)
        if (place.length < 4 || !Regex("^\\p{IsHan}+$").matches(place) ||
            Regex("一期|二期|三期|四期|[一二三四五六七八九十]+号").containsMatchIn(place)
        ) return false
        val address = StationNameNormalizer.normalize(locationText)
        val locality = Regex("(?:^|省|市|县|区)${Regex.escape(shorter)}(?:镇|乡|街道)")
            .find(address) ?: return false
        // “裕华加油站”在地址中可写成“裕华石油加油站”；保留完整商户专名。
        val addressPlace = address.substring(locality.range.last + 1).replace("石油加油站", "加油站")
        val expectedPlace = place.replace("石油加油站", "加油站")
        return addressPlace.contains(expectedPlace)
    }

    /** 无括号任务可以采用结果明确提供的品牌分词；行政前缀须由同一行地址支持。 */
    private fun unwrappedBrandIdentityMatches(
        stationName: String,
        candidateName: String,
        locationText: String,
    ): Boolean {
        if (hasSpecificPart(stationName) || !hasSpecificPart(candidateName)) return false
        val candidateBrand = brandOf(candidateName)
        val suffix = listOf("重卡充电站", "充电站", "充电桩", "快充站", "超充站")
            .firstOrNull { candidateBrand.endsWith(it) } ?: return false
        val brandStem = candidateBrand.removeSuffix(suffix)
        if (brandStem.length < 2 || !StationNameNormalizer.normalize(stationName).startsWith(brandStem)) {
            return false
        }
        val targetIdentity = specificCore(stationName).removePrefix(brandStem).removeSuffix("站")
        val candidateIdentity = specificCore(candidateName).removeSuffix("站")
        if (targetIdentity.length < 4 || candidateIdentity.length < 4) return false
        if (targetIdentity == candidateIdentity) return true
        val longer = if (targetIdentity.length > candidateIdentity.length) targetIdentity else candidateIdentity
        val shorter = if (targetIdentity.length > candidateIdentity.length) candidateIdentity else targetIdentity
        if (!longer.endsWith(shorter)) return false
        val administrativePrefix = longer.removeSuffix(shorter)
        // 只允许多出一个已知的河南行政区名称，且该名称在本结果行地址中完整出现。
        // 品牌、道路编号、功率、站号和一期/二期都必须保留并相等。
        return administrativePrefix in HENAN_SEARCH_LOCATIONS &&
            StationNameNormalizer.normalize(locationText).contains(administrativePrefix)
    }

    /** 通用充电类型只是外壳；按完整实际站名匹配，不限制结果的外层品牌。 */
    private fun genericWrapperSpecificMatches(stationName: String, candidateName: String): Boolean {
        val targetHead = brandOf(stationName)
        // 只放行明确的通用名称；含品牌、地名、功率或站号的前缀仍按原规则核对。
        val genericHeads = setOf(
            "充电站", "重卡充电站", "新能源充电站", "新能源重卡充电站", "重卡新能源充电站",
            "超充站", "快充站", "重卡超充站", "重卡快充站", "充电桩", "重卡充电桩",
            "新能源超充站", "新能源快充站", "新能源重卡超充站", "新能源重卡快充站",
            "重卡新能源超充站", "重卡新能源快充站", "新能源充电桩", "新能源重卡充电桩",
        )
        if (targetHead !in genericHeads || !hasSpecificPart(stationName)) return false
        fun specificName(name: String): String {
            if (!hasSpecificPart(name)) return StationNameNormalizer.normalize(name)
            val open = name.indexOfFirst { it == '(' || it == '（' }
            val close = name.indexOfLast { it == ')' || it == '）' }
            return StationNameNormalizer.normalize(name.substring(open + 1, close))
        }
        val targetSpecific = specificName(stationName)
        val targetIdentity = specificCore(stationName).removeSuffix("站")
        if (targetSpecific.length < 4 || targetIdentity.length < 2) return false
        if (targetSpecific == specificName(candidateName)) return true
        if (hasNameEllipsis(stationName) || hasNameEllipsis(candidateName)) return false
        // 嵌套括号、充电类型及末尾“站”可有展示差异：
        // “飞砂走石重卡无梁服务区站”与“飞砂走石重卡充电站(无梁服务区)”属于同一完整专名。
        // 保留服务区、地点、站号、功率和一期/二期；只作完整相等，不作包含或短前缀匹配。
        val candidateIdentity = specificCore(candidateName).removeSuffix("站")
        if (targetIdentity.length < 4) return false
        if (targetIdentity == candidateIdentity) return true
        // “新能源重卡充电站(宝丰杨庄站)” -> “新电途充电站(宝丰杨庄新能源重卡站)”。
        // 仅允许任务外层已有的“新能源”移到实际站名首尾；不删除专名内部的词，
        // 也不放宽地点、站号、功率、服务区及一期/二期的完整相等要求。
        if (!targetHead.contains("新能源") || targetSpecific.contains("新能源")) return false
        val relocatedIdentity = candidateIdentity.removePrefix("新能源").removeSuffix("新能源")
        return relocatedIdentity == targetIdentity
    }

    /** 保留完整括号站名，仅消除开头重复出现的外层品牌。 */
    private fun wrappedSpecificName(name: String): String {
        val open = name.indexOfFirst { it == '(' || it == '（' }
        val close = name.indexOfLast { it == ')' || it == '）' }
        if (open < 0 || close <= open) return ""
        val inner = StationNameNormalizer.normalize(name.substring(open + 1, close))
        val brand = brandOf(name)
        val stationSuffix = listOf("重卡充电站", "充电站", "充电桩", "快充站", "超充站")
            .firstOrNull { brand.endsWith(it) }
        val brandName = stationSuffix?.let { brand.removeSuffix(it) } ?: brand
        if (brandName.length < 2) return ""
        val repeatedBrand = listOf(brand, brandName).distinct().sortedByDescending { it.length }
            .firstOrNull { inner.startsWith(it) }
        return repeatedBrand?.let { inner.removePrefix(it) } ?: inner
    }

    private fun cityPrefixedIdentityMatches(target: String, candidate: String, locationText: String): Boolean {
        if (target.length < 4 || candidate.length < 4 || locationText.isBlank()) return false
        val longer = if (target.length > candidate.length) target else candidate
        val shorter = if (target.length > candidate.length) candidate else target
        if (longer == shorter || !longer.endsWith(shorter)) return false
        val prefix = longer.removeSuffix(shorter)
        if (!Regex("^\\p{IsHan}{2,12}$").matches(prefix)) return false
        val cityStem = prefix.removeSuffix("自治州").removeSuffix("地区").removeSuffix("市").removeSuffix("盟")
        if (cityStem.length < 2) return false
        // 必须是地址中的行政市名；不能把品牌、道路名或一期/二期差别当成可省略前缀。
        val cityInAddress = Regex("(?:^|[\\s,，]|省|自治区|特别行政区)${Regex.escape(cityStem)}(?:市|自治州|地区|盟)")
        return cityInAddress.containsMatchIn(locationText)
    }

    /** 仅凭同一结果行的地址，消除行政“市”和末尾“停车场”的展示差异。 */
    private fun addressSupportedIdentityMatches(target: String, candidate: String, locationText: String): Boolean {
        if (target.length < 4 || candidate.length < 4 || locationText.isBlank()) return false
        val supportedCityStems = mutableSetOf<String>()
        fun normalizeCitySuffix(identity: String): String {
            val city = Regex("^(\\p{IsHan}{2,12}?)市").find(identity) ?: return identity
            val stem = city.groupValues[1]
            val cityInAddress = Regex(
                "(?:^|[\\s,，]|省|自治区|特别行政区|市|自治州|地区)${Regex.escape(stem)}市"
            )
            if (!cityInAddress.containsMatchIn(locationText)) return identity
            supportedCityStems += stem
            return stem + identity.substring(city.value.length)
        }
        val targetLocal = normalizeCitySuffix(target)
        val candidateLocal = normalizeCitySuffix(candidate)
        if (targetLocal == candidateLocal) return true

        val longer = if (targetLocal.length > candidateLocal.length) targetLocal else candidateLocal
        val shorter = if (targetLocal.length > candidateLocal.length) candidateLocal else targetLocal
        // 必须只差末尾“停车场”，不能吞掉“君乐充”、站号、一期/二期或功率。
        if (!longer.endsWith("停车场") || longer.removeSuffix("停车场") != shorter) return false
        // 城市可能只出现在地址的行政层级中；停车场的专名仍须完整出现在该行地址。
        val cityPrefix = supportedCityStems.filter { longer.startsWith(it) }.maxByOrNull { it.length }
        val parkingName = cityPrefix?.let { longer.removePrefix(it) } ?: longer
        if (parkingName.removeSuffix("停车场").length < 3) return false
        return StationNameNormalizer.normalize(locationText).contains(parkingName)
    }

    private fun brandOf(name: String): String {
        val head = name.substringBefore('(').substringBefore('（')
        return StationNameNormalizer.normalize(head)
    }

    private fun hasSpecificPart(name: String): Boolean {
        val open = name.indexOfFirst { it == '(' || it == '（' }
        val close = name.indexOfLast { it == ')' || it == '）' }
        return open >= 0 && close > open
    }

    private fun hasNameEllipsis(name: String): Boolean =
        name.contains("...") || name.contains("…") || name.contains("⋯") || name.contains("．．．")

    private fun specificCore(name: String): String {
        val raw = name.trim()
        val open = raw.indexOfFirst { it == '(' || it == '（' }
        val close = raw.indexOfLast { it == ')' || it == '）' }
        val hasSpecificPart = open >= 0 && close > open
        val inner = if (hasSpecificPart) raw.substring(open + 1, close) else raw
        var value = StationNameNormalizer.normalize(inner)
        val genericTerms = listOf("重卡充电站", "超级充电站", "充电站", "充电桩", "快充站", "超充站", "充电", "快充", "超充", "重卡")
        if (hasSpecificPart) {
            var brandStem = brandOf(raw)
            genericTerms.forEach { brandStem = brandStem.replace(it, "") }
            // 高德括号内可能重复品牌，如“克莱因星星充电站400kW重卡站”。
            // 只消除本名称品牌后紧跟充电描述的片段，保留地名中的独立品牌词。
            if (brandStem.length >= 2) {
                listOf("充电站", "充电桩", "快充站", "超充站", "充电", "快充", "超充").forEach {
                    value = value.replace(brandStem + it, "")
                }
            }
        }
        genericTerms.forEach { value = value.replace(it, "") }
        return value
    }

    private fun checkStop() {
        if (stopRequested) throw StopRequestedException()
    }

    private fun hasPriceOrPile(snapshot: NodeSnapshot): Boolean {
        val texts = collectTexts(snapshot)
        return texts.any { it.contains("查看价格详情") } ||
            BaiduChargingPileParser.cards(snapshot).isNotEmpty() ||
            BaiduPriceParser.currentPrice(snapshot).isNotBlank() ||
            texts.any { Regex("空闲\\s*[0-9]+\\s*/\\s*[0-9]+").containsMatchIn(it) }
    }

    private fun parseDetail(snapshot: NodeSnapshot, fallbackName: String, fallbackAddress: String): StationDetail {
        val nodes = collectTextNodes(snapshot)
        val texts = nodes.map { it.first }
        val tags = buildList {
            if (texts.any { it.contains("24小时营业") }) add("24小时营业")
            if (texts.any { it.contains("充电快") }) add("充电快")
            if (texts.any { it.contains("扫码充电") }) add("扫码充电")
        }
        val viewCount = texts.firstNotNullOfOrNull { Regex("近一月\\s*([0-9,]+)\\s*人充过").find(it)?.groupValues?.get(1) }.orEmpty()
        val recent = texts.firstOrNull { Regex(".{0,4}小时内有人充过").containsMatchIn(it) }.orEmpty()
        val businessHours = if (texts.any { it.contains("24小时营业") }) "24小时营业" else ""

        val pileCards = BaiduChargingPileParser.cards(snapshot)

        val currentPrice = BaiduPriceParser.currentPrice(snapshot)

        val nameTop = (snapshot.bounds.height.takeIf { it > 0 } ?: 2400) * 0.30f
        val name = nodes.filter { it.second.top < nameTop.toInt() && it.first.contains("充电站") }
            .maxByOrNull { it.first.length }?.first ?: fallbackName
        val address = texts.firstOrNull { it.contains("国道") || it.contains("省道") || it.contains("街道") || it.contains("路") }
            ?.takeIf { it.length >= 6 } ?: fallbackAddress

        val operator = run {
            val idx = name.indexOf("充电站")
            if (idx > 0) name.substring(0, idx).trim() else ""
        }

        return BaiduChargingPileParser.applySummaries(StationDetail(
            stationName = name,
            tags = tags,
            businessHours = businessHours,
            address = address,
            operator = operator,
            currentPrice = currentPrice,
            viewCount = viewCount,
            favoriteCount = recent,
            sourcePage = "BAIDU_DETAIL",
            collectionMode = "DETAIL",
        ), pileCards)
    }

    private fun mergeStationDetail(existing: StationDetail, incoming: StationDetail): StationDetail = existing.copy(
        tags = (existing.tags + incoming.tags).distinct(),
        businessHours = incoming.businessHours.ifBlank { existing.businessHours },
        currentPrice = incoming.currentPrice.ifBlank { existing.currentPrice },
        viewCount = incoming.viewCount.ifBlank { existing.viewCount },
        favoriteCount = incoming.favoriteCount.ifBlank { existing.favoriteCount },
        fastAvailable = incoming.fastAvailable.ifBlank { existing.fastAvailable },
        fastTotal = incoming.fastTotal.ifBlank { existing.fastTotal },
        fastPower = incoming.fastPower.ifBlank { existing.fastPower },
        superAvailable = incoming.superAvailable.ifBlank { existing.superAvailable },
        superTotal = incoming.superTotal.ifBlank { existing.superTotal },
        superPower = incoming.superPower.ifBlank { existing.superPower },
        slowAvailable = incoming.slowAvailable.ifBlank { existing.slowAvailable },
        slowTotal = incoming.slowTotal.ifBlank { existing.slowTotal },
        slowPower = incoming.slowPower.ifBlank { existing.slowPower },
    )

    private suspend fun collectChargingStatusIfPresent(
        service: ChargingAccessibilityService,
        initialDetail: StationDetail,
    ): StationDetail {
        var detail = initialDetail
        var previousSignature = ""
        var unchanged = 0
        emit(CollectionState.READING_DETAIL, "读取电桩空闲/忙碌状态", 0, detail.stationName)
        for (attempt in 0..MAX_DETAIL_SCROLLS) {
            checkStop()
            val snapshot = collectionSnapshot(service) ?: throw IllegalStateException("读取站点详情失败")
            detail = mergeStationDetail(detail, parseDetail(snapshot, detail.stationName, detail.address))
            val cards = BaiduChargingPileParser.cards(snapshot)
            if (cards.isNotEmpty()) {
                val summary = cards.joinToString("；") { card ->
                    val total = card.total.toIntOrNull()
                    val idle = card.available.toIntOrNull()
                    if (total != null && idle != null && idle in 0..total) {
                        "${card.type}空闲$idle/忙碌${total - idle}/共$total"
                    } else {
                        "${card.type}共${card.total}，空闲/忙碌未知"
                    }
                }
                emit(CollectionState.READING_DETAIL, "已读取：$summary，继续查找分时电价", 0, detail.stationName)
                return detail
            }
            // Price follows the pile cards. Keep its entry visible for price-only stations.
            if (visiblePriceEntry(service, snapshot) != null) break
            val signature = collectTextNodes(snapshot).joinToString("|") { "${it.first}@${it.second.top}" }
            unchanged = if (signature.isNotBlank() && signature == previousSignature) unchanged + 1 else 0
            if (unchanged >= 2 || attempt == MAX_DETAIL_SCROLLS) break
            previousSignature = signature
            emit(CollectionState.READING_DETAIL, "滚动查找电桩状态卡片（${attempt + 1}/$MAX_DETAIL_SCROLLS）", 0, detail.stationName)
            service.scrollContentSlow()
            delay(1_100L)
        }
        emit(CollectionState.READING_DETAIL, "未找到电桩状态卡片，继续查找分时电价", 0, detail.stationName)
        return detail
    }

    private fun visiblePriceEntry(service: ChargingAccessibilityService, snapshot: NodeSnapshot): Rect? {
        if (BaiduPriceParser.findSheet(snapshot) != null) return null
        val safeTop = (service.screenHeightPx() * 0.10f).toInt()
        val safeBottom = (service.screenHeightPx() * 0.90f).toInt()
        val screenWidth = service.screenWidthPx()
        return collectTextNodes(snapshot).firstOrNull { (text, bounds) ->
            text.contains("查看价格详情") && bounds.top >= safeTop && bounds.bottom <= safeBottom &&
                bounds.centerX in 8 until (screenWidth - 8)
        }?.second
    }

    private data class PriceSheetLookup(
        val detail: StationDetail,
        val opened: Boolean,
        val sheetAlreadyVisible: Boolean = false,
    )

    private suspend fun openPriceSheet(
        service: ChargingAccessibilityService,
        stationName: String,
        initialDetail: StationDetail,
    ): PriceSheetLookup {
        var detail = initialDetail
        val maxScrolls = MAX_DETAIL_SCROLLS
        // 检查当前位置，以及每次向下滚动后的页面，包括最后一次滚动后。
        for (scrollCount in 0..maxScrolls) {
            checkStop()
            val snapshot = collectionSnapshot(service)
                ?: throw IllegalStateException("读取站点详情失败，无法查找分时电价")
            if (BaiduPriceParser.findSheet(snapshot) != null) {
                emit(CollectionState.READING_PRICE, "价格弹层已打开，读取价格内容", 0, stationName)
                return PriceSheetLookup(detail, opened = true, sheetAlreadyVisible = true)
            }
            // 继续下滑时新出现的当前价也必须保留，包括没有详情链接的站点。
            detail = mergeStationDetail(detail, parseDetail(snapshot, stationName, detail.address))
            val entry = visiblePriceEntry(service, snapshot)
            if (entry != null) {
                emit(CollectionState.OPENING_PRICE, "找到分时电价入口，打开价格详情", 0, stationName)
                checkStop()
                if (!service.clickAt(entry.centerX, entry.centerY)) {
                    throw IllegalStateException("点击查看价格详情失败")
                }
                emit(CollectionState.OPENING_PRICE, "已点击查看价格详情，等待价格弹层", 0, stationName)
                delay(1_200L)
                return PriceSheetLookup(detail, opened = true)
            }
            if (scrollCount == maxScrolls) break
            emit(CollectionState.OPENING_PRICE, "向下查找分时电价（${scrollCount + 1}/$maxScrolls）", 0, stationName)
            checkStop()
            if (!service.scrollContentSlow()) {
                if (detail.currentPrice.isNotBlank()) break
                throw IllegalStateException("向下查找分时电价时滑动失败")
            }
            delay(1_200L)
        }
        val message = if (detail.currentPrice.isNotBlank()) {
            "未找到价格详情入口，已读取页面电价 ¥${detail.currentPrice}/度"
        } else {
            "向下查找分时电价结束，未找到可点击入口（最多${MAX_DETAIL_SCROLLS}次）"
        }
        emit(CollectionState.READING_PRICE, message, 0, stationName)
        return PriceSheetLookup(detail, opened = false)
    }

    private suspend fun waitForPriceSheet(
        service: ChargingAccessibilityService,
        stationName: String,
        sheetAlreadyVisible: Boolean,
    ): NodeSnapshot? {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + 15_000L
        var retryClicks = 0
        var sheetObserved = sheetAlreadyVisible
        fun readySheet(snapshot: NodeSnapshot?): NodeSnapshot? {
            val sheet = snapshot?.let { BaiduPriceParser.findSheet(it) } ?: return null
            if (!sheetObserved) {
                emit(CollectionState.READING_PRICE, "价格弹层已打开，等待电价内容", 0, stationName)
            }
            sheetObserved = true
            if (BaiduPriceParser.periods(sheet).isNotEmpty() || BaiduPriceParser.hasNoPrice(sheet)) {
                emit(CollectionState.READING_PRICE, "价格弹层内容已识别", 0, stationName)
                return sheet
            }
            return null
        }
        while (System.currentTimeMillis() < deadline) {
            checkStop()
            readySheet(collectionSnapshot(service))?.let { return it }
            val elapsed = System.currentTimeMillis() - startedAt
            if (!sheetObserved && elapsed > 3_000L && retryClicks < 2) {
                // 补点前再取一次快照；一旦见过弹层标题，本次等待中都禁止补点背景。
                checkStop()
                val retrySnapshot = collectionSnapshot(service)
                readySheet(retrySnapshot)?.let { return it }
                if (!sheetObserved && retrySnapshot != null) {
                    val entry = visiblePriceEntry(service, retrySnapshot)
                    if (entry != null) {
                        checkStop()
                        retryClicks++
                        val clicked = service.clickAt(entry.centerX, entry.centerY)
                        val message = if (clicked) "尚未识别到价格弹层，补点第 $retryClicks 次"
                            else "价格详情补点第 $retryClicks 次失败，继续等待"
                        emit(CollectionState.OPENING_PRICE, message, 0, stationName)
                        delay(1_000L)
                    }
                }
            }
            delay(700L)
        }
        val message = if (sheetObserved) "价格弹层已打开，但15秒内未读到时段，保留页面电价"
            else "价格弹层15秒内未识别到，保留页面电价"
        emit(CollectionState.READING_PRICE, message, 0, stationName)
        return null
    }

    private suspend fun returnToSearchPage(service: ChargingAccessibilityService) {
        repeat(3) {
            service.globalBack()
            delay(900L)
            if (service.searchInputState() != null) return
        }
    }

    // ------------------------------------------------------------------
    // 数据落库
    // ------------------------------------------------------------------

    private suspend fun saveDetail(
        fallbackName: String,
        fallbackAddress: String,
        latitude: Double?,
        longitude: Double?,
        stationId: String,
        remoteTaskId: String,
        detail: StationDetail,
    ) {
        val stationName = detail.stationName.ifBlank { fallbackName }
        emit(CollectionState.SAVING, "正在保存站点采集结果", 0, stationName)
        val sourceStationId = stationId.ifBlank {
            "baidu-" + Integer.toHexString(StationNameNormalizer.normalize(stationName).hashCode())
        }
        val now = System.currentTimeMillis()
        val payload = linkedMapOf<String, Any?>(
            "stationName" to stationName,
            "remoteTaskId" to remoteTaskId,
            "sourceStationId" to sourceStationId,
            "requestedStationName" to fallbackName,
            "sourceAddress" to fallbackAddress,
            "sourceLatitude" to latitude,
            "sourceLongitude" to longitude,
            "tags" to detail.tags,
            "businessHours" to detail.businessHours,
            "address" to detail.address.ifBlank { fallbackAddress },
            "operator" to detail.operator,
            "currentPrice" to detail.currentPrice,
            "viewCount" to detail.viewCount,
            "favoriteCount" to detail.favoriteCount,
            "fastAvailable" to detail.fastAvailable,
            "fastTotal" to detail.fastTotal,
            "fastPower" to detail.fastPower,
            "superAvailable" to detail.superAvailable,
            "superTotal" to detail.superTotal,
            "superPower" to detail.superPower,
            "slowAvailable" to detail.slowAvailable,
            "slowTotal" to detail.slowTotal,
            "slowPower" to detail.slowPower,
            "priceTrendTitle" to detail.priceTrendTitle,
            "fastPrices" to detail.fastPrices,
            "slowPrices" to detail.slowPrices,
            // Only live card aggregates are refreshed; static pile rows stay on the server.
            "pileStatusSource" to "DETAIL_CARD",
            "latitude" to latitude,
            "longitude" to longitude,
            "searchQuery" to fallbackName,
            "collectedAt" to now,
            "sourcePage" to "BAIDU_DETAIL",
            "collectionMode" to "DETAIL",
        )
        val resultJson = gson.toJson(payload)
        val task = ScanTaskEntity(
            stationId = sourceStationId,
            name = stationName,
            address = detail.address.ifBlank { fallbackAddress },
            latitude = latitude,
            longitude = longitude,
            payloadJson = resultJson,
            status = "running",
            attempts = 0,
            maxAttempts = 3,
            leaseToken = UUID.randomUUID().toString(),
            leaseExpiresAt = now + 180_000L,
            resultJson = null,
            lastError = "",
            createdAt = now,
            updatedAt = now,
        )
        val localTask = activeLocalTask
        if (localTask != null) {
            repository.saveResult(localTask, resultJson, markLocalTaskSucceeded = true)
        } else {
            repository.saveResult(task, resultJson, markLocalTaskSucceeded = false)
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun collectTexts(snapshot: NodeSnapshot): List<String> {
        val values = mutableListOf<String>()
        fun walk(node: NodeSnapshot) {
            val text = node.text.ifBlank { node.contentDescription }.trim()
            if (text.isNotEmpty()) values.add(text)
            node.children.forEach(::walk)
        }
        walk(snapshot)
        return values.distinct()
    }

    private fun collectTextNodes(snapshot: NodeSnapshot): List<Pair<String, com.tigercode.evcollector.core.model.Rect>> {
        val nodes = mutableListOf<Pair<String, com.tigercode.evcollector.core.model.Rect>>()
        fun walk(node: NodeSnapshot) {
            val text = node.text.ifBlank { node.contentDescription }.trim()
            if (text.isNotEmpty() && node.bounds.isValid) nodes.add(text to node.bounds)
            node.children.forEach(::walk)
        }
        walk(snapshot)
        return nodes
            .distinctBy { it.first to it.second.top }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
    }

    private fun emit(state: CollectionState, message: String, count: Int = 0, station: String = "") {
        AppPreferences.appendLog(context, message)
        CollectionStatusNotifier.show(context, message)
        if (state == CollectionState.STOPPED) CollectionStatusNotifier.dismissAfter(3_000L)
        listeners.forEach { it.onEvent(state, message, count, station) }
    }
}
