package com.tigercode.evcollector.recovery

import com.google.gson.Gson
import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class BaiduAiRecoveryTest {
    private val button = NodeSnapshot(text = "关闭广告", clickable = true, bounds = Rect(460, 82, 530, 130))
    private val stage = RecoveryStage.SEARCH

    private fun root(vararg nodes: NodeSnapshot) = NodeSnapshot(bounds = Rect(0, 0, 540, 1080), children = nodes.toList())

    private fun evidence(root: NodeSnapshot, cause: RecoveryCause = RecoveryCause.POPUP) =
        BaiduAiRecoveryPolicy.evidence(root, stage, cause)

    @Test fun `overlay actions require one explicitly identified close button`() {
        val close = RecoveryDecision.PAUSE_AND_CLOSE_OVERLAY
        assertTrue(evidence(root(button))!!.allowedActions.contains(close))
        assertFalse(evidence(root(button, button.copy(bounds = Rect(5, 10, 30, 40))))!!.allowedActions.contains(close))
        assertFalse(evidence(root(button.copy(text = "购买")))!!.allowedActions.contains(close))
        assertFalse(evidence(root(button.copy(clickable = false)))!!.allowedActions.contains(close))
        assertFalse(evidence(root(button.copy(bounds = Rect(530, 82, 610, 130))))!!.allowedActions.contains(close))
    }

    @Test fun `captcha login permissions and checkout are always excluded`() {
        for (protectedText in listOf("拖动滑块完成验证", "登录后继续", "仅在使用中允许", "确认支付")) {
            assertNull(evidence(root(NodeSnapshot(text = protectedText), button)))
        }
        assertNull(evidence(root(NodeSnapshot(viewId = "com.baidu.BaiduMap:id/captcha_panel"), button)))
    }

    @Test fun `a normal empty search result does not invoke recovery`() {
        assertNull(evidence(root(NodeSnapshot(text = "没有找到相关结果")), RecoveryCause.UNEXPECTED_PAGE))
    }

    @Test fun `request sends only classified signals and the fixed model id`() {
        val secretText = "sensitive test station description 98981"
        val data = evidence(root(NodeSnapshot(text = secretText, viewId = "private_view_id"), button))!!
        val body = DeepSeekRecoveryClient.requestBody(data)
        assertFalse(body.contains(secretText))
        assertFalse(body.contains("private_view_id"))
        assertTrue(body.contains("PROMOTION"))
        assertTrue(body.contains("deepseek-v4-flash"))
        assertTrue(body.contains("json_object"))
    }

    private fun reply(content: String, finishReason: String = "stop") = Gson().toJson(mapOf("choices" to listOf(
        mapOf("finish_reason" to finishReason, "message" to mapOf("content" to content)),
    )))

    @Test fun `only one exact allowlisted action is accepted`() {
        val allowed = setOf(RecoveryDecision.PAUSE_AND_BACK, RecoveryDecision.NONE)
        assertEquals(RecoveryRecommendation(RecoveryDecision.PAUSE_AND_BACK),
            DeepSeekRecoveryClient.parse(reply("""{"action":"PAUSE_AND_BACK"}"""), allowed))
        for (content in listOf(
            "", "not JSON", """{"action":"PAUSE_AND_CLOSE_OVERLAY"}""", """{"action":"REOPEN"}""", """{"action":"BACK"}""",
            """{"action":"TAP","x":5,"y":10}""", """{"action":"PAUSE_AND_BACK","action":"NONE"}""",
            """{"action":"PAUSE_AND_BACK","reason":"extra"}""", """{"action":1}""", """{action:"PAUSE_AND_BACK"}""",
            """{"action":"PAUSE_AND_BACK"}{"action":"NONE"}""",
        )) assertNull(content, DeepSeekRecoveryClient.parse(reply(content), allowed))
        assertNull(DeepSeekRecoveryClient.parse(reply("""{"action":"PAUSE_AND_BACK"}""", "length"), allowed))
    }

    private val homeSearch = NodeSnapshot(viewId = "com.baidu.BaiduMap:id/maphome_searchbar_bg", bounds = Rect(12, 65, 520, 132))
    private val home = AiRecoveryScene(1, root(homeSearch))
    private val navHome = AiRecoveryScene(1, root(NodeSnapshot(text = "首页"), NodeSnapshot(text = "打车"), NodeSnapshot(text = "我的")))
    private fun page(text: String) = AiRecoveryScene(1, root(NodeSnapshot(text = text)))

    private val claim = NodeSnapshot(text = "立即领取", className = "android.widget.Button", clickable = true,
        bounds = Rect(290, 540, 440, 600))
    private val decline = NodeSnapshot(className = "android.widget.Button", clickable = true,
        bounds = Rect(90, 540, 240, 600), children = listOf(NodeSnapshot(text = "暂不需要")))
    private fun panel(vararg nodes: NodeSnapshot, named: Boolean = true) = NodeSnapshot(
        viewId = if (named) "com.baidu.BaiduMap:id/customPromoDialog" else "",
        bounds = Rect(60, 320, 480, 620), children = nodes.toList(),
    )

    @Test fun `new dialog supplies only standardized node hints and only dismiss nodes are actionable`() {
        val privateText = "私人页面标题 13312345678"
        val popup = panel(claim, decline, NodeSnapshot(text = privateText), named = false)
        val snapshot = root(homeSearch, popup)
        assertTrue(BaiduAiRecoveryPolicy.hasNodeDialog(snapshot))
        assertFalse(BaiduAiRecoveryPolicy.isHome(snapshot))
        val data = evidence(snapshot, RecoveryCause.STAGE_TIMEOUT)!!
        assertTrue(RecoveryDecision.CLICK_NODE in data.allowedActions)
        assertEquals(listOf("n1", "n2"), data.dialogNodes.map { it.nodeId })
        assertEquals(listOf(false, true), data.dialogNodes.map { it.dismiss })
        val body = DeepSeekRecoveryClient.requestBody(data)
        assertFalse(body.contains(privateText))
        assertFalse(body.contains("com.baidu.BaiduMap:id"))
        assertFalse(body.contains("290"))
        assertTrue(body.contains("暂不需要"))
    }

    @Test fun `only a listed dismiss id on the unchanged dialog can be selected`() {
        val screen = AiRecoveryScene(1, root(homeSearch, panel(claim, decline)))
        val data = evidence(screen.snapshot)!!
        val ids = data.dialogNodes.filter { it.dismiss }.map { it.nodeId }.toSet()
        fun parse(content: String) = DeepSeekRecoveryClient.parse(reply(content), data.allowedActions, ids)
        assertEquals(RecoveryRecommendation(RecoveryDecision.CLICK_NODE, "n2"), parse("""{"nodeId":"n2","action":"CLICK_NODE"}"""))
        for (content in listOf(
            """{"action":"CLICK_NODE","nodeId":"n1"}""", """{"action":"CLICK_NODE","nodeId":"n9"}""",
            """{"action":"CLICK_NODE","nodeId":"n2","nodeId":"n1"}""", """{"action":"CLICK_NODE"}""",
            """{"action":"CLICK_NODE","nodeId":"n2","x":"12"}""", """{"action":"PAUSE_AND_BACK","nodeId":"n2"}""",
        )) assertNull(content, parse(content))
        assertEquals(decline, BaiduAiRecoveryPolicy.clickNodeTarget(screen, screen, "n2"))
        assertNull(BaiduAiRecoveryPolicy.clickNodeTarget(screen, screen, "n1"))
        assertNull(BaiduAiRecoveryPolicy.clickNodeTarget(screen, screen.copy(windowId = 2), "n2"))
        val changed = screen.copy(snapshot = root(homeSearch, panel(claim, decline.copy(enabled = false))))
        assertNull(BaiduAiRecoveryPolicy.clickNodeTarget(screen, changed, "n2"))
    }

    @Test fun `hidden ambiguous or sensitive dialog buttons are not offered for click`() {
        val lookalike = decline.copy(bounds = Rect(90, 470, 240, 530))
        val conflict = decline.copy(contentDescription = "领取奖励")
        val inlineBanner = root(homeSearch, decline)
        assertFalse(BaiduAiRecoveryPolicy.hasNodeDialog(inlineBanner))
        for (popup in listOf(panel(claim, decline.copy(visibleToUser = false)), panel(claim, decline, lookalike), panel(claim, conflict))) {
            assertFalse(evidence(root(popup))!!.allowedActions.contains(RecoveryDecision.CLICK_NODE))
        }
        assertNull(evidence(root(panel(claim, decline, NodeSnapshot(text = "请完成验证")))))
        assertNull(evidence(root(panel(claim, decline, NodeSnapshot(password = true)))))
    }

    @Test fun `node click is one action and cannot resume while the dialog is still visible`() = runBlocking {
        val screen = AiRecoveryScene(1, root(homeSearch, panel(claim, decline)))
        val stillBlocked = screen.copy(snapshot = root(homeSearch, panel(claim, decline, NodeSnapshot(text = "4s"))))
        for (afterClick in listOf(home, stillBlocked)) {
            val actions = mutableListOf<RecoveryAction>()
            val result = returnToBaiduHome(screen, RecoveryAction.CLICK_NODE, readScene = { afterClick },
                applyAction = { _, action -> actions.add(action) }, awaitNextRead = {})
            assertEquals(listOf(RecoveryAction.CLICK_NODE), actions)
            assertEquals(home.takeIf { afterClick == home }, result)
        }
    }

    @Test fun `home does not require a writable search box or the fixed resource id`() {
        assertTrue(BaiduAiRecoveryPolicy.isHome(home.snapshot))
        assertTrue(BaiduAiRecoveryPolicy.isHome(navHome.snapshot))
        val bannerClose = button.copy(text = "×")
        assertTrue(BaiduAiRecoveryPolicy.isHome(navHome.snapshot.copy(children = navHome.snapshot.children + bannerClose)))
        assertTrue(BaiduAiRecoveryPolicy.isHome(root(homeSearch.copy(enabled = false))))
        assertFalse(BaiduAiRecoveryPolicy.isHome(root(homeSearch, button)))
        assertFalse(BaiduAiRecoveryPolicy.isHome(root(homeSearch, NodeSnapshot(text = "正在检索", className = "android.widget.EditText", bounds = homeSearch.bounds))))
        assertFalse(BaiduAiRecoveryPolicy.isHome(root(homeSearch, NodeSnapshot(text = "立即登录"))))
    }

    @Test fun `changing pages is not recovery until the home is read twice`() = runBlocking {
        val initial = page("站点详情")
        val search = page("搜索结果")
        val actions = mutableListOf<Pair<AiRecoveryScene, RecoveryAction>>()
        var homeReads = 0
        val recovered = returnToBaiduHome(initial, RecoveryAction.BACK, readScene = {
            if (actions.size == 1) search else navHome.also { homeReads++ }
        }, applyAction = { scene, action -> actions.add(scene to action) }, awaitNextRead = {})
        assertEquals(navHome, recovered)
        assertEquals(listOf(initial to RecoveryAction.BACK, search to RecoveryAction.BACK), actions)
        assertEquals(2, homeReads)
    }

    @Test fun `close overlay may land on a subpage and still requires going home`() = runBlocking {
        val actions = mutableListOf<RecoveryAction>()
        val recovered = returnToBaiduHome(AiRecoveryScene(1, root(button)), RecoveryAction.CLOSE_OVERLAY,
            readScene = { if (actions.size == 1) page("详情") else home },
            applyAction = { _, action -> actions.add(action) }, awaitNextRead = {})
        assertEquals(home, recovered)
        assertEquals(listOf(RecoveryAction.CLOSE_OVERLAY, RecoveryAction.BACK), actions)
    }

    @Test fun `unchanged missing protected and unconfirmed home never resume`() = runBlocking {
        val initial = page("异常页")
        for (next in listOf(initial, null, page("请完成验证"))) {
            var actions = 0
            assertNull(returnToBaiduHome(initial, RecoveryAction.BACK, readScene = { next },
                applyAction = { _, _ -> actions++; true }, awaitNextRead = {}))
            assertEquals(1, actions)
        }
    }

    @Test fun `no back is sent after observing home even if the next read is uncertain`() = runBlocking {
        for (next in listOf(null, page("其他页面"), home.copy(windowId = 2))) {
            var reads = 0
            var actions = 0
            assertNull(returnToBaiduHome(page("异常页"), RecoveryAction.BACK,
                readScene = { if (reads++ == 0) navHome else next },
                applyAction = { _, _ -> actions++; true }, awaitNextRead = {}))
            assertEquals(1, actions)
            assertEquals(2, reads)
        }
        assertEquals(navHome, returnToBaiduHome(navHome, RecoveryAction.BACK, readScene = { navHome },
            applyAction = { _, _ -> error("must not back from home") }, awaitNextRead = {}))
    }

    @Test fun `return is capped and manual cancellation never resumes`() = runBlocking {
        val initial = page("异常页")
        var actions = 0
        assertNull(returnToBaiduHome(initial, RecoveryAction.BACK, readScene = { page("第 $actions 层") },
            applyAction = { _, _ -> actions++; true }, awaitNextRead = {}))
        assertEquals(5, actions)
        val stopped = CancellationException("manual stop")
        val result = runCatching {
            returnToBaiduHome(initial, RecoveryAction.BACK, readScene = { error("cancelled") },
                applyAction = { _, _ -> true }, awaitNextRead = { throw stopped })
        }
        assertSame(stopped, result.exceptionOrNull())
    }

    @Test fun `confirmed home with only navigation resumes the current station`() = runBlocking {
        val events = mutableListOf<String>()
        val result = runBaiduAiFallback(collect = {
            if (events.isEmpty()) {
                events += "blocked"
                throw BaiduUiBlockedException(RecoveryCause.UNEXPECTED_PAGE, "blocked")
            }
            events += "retry"
            "ok"
        }, recover = {
            returnToBaiduHome(page("异常页"), RecoveryAction.BACK,
                readScene = { events += "home"; navHome },
                applyAction = { _, action -> events.add(action.name) }, awaitNextRead = {}) != null
        })
        assertEquals("ok", result)
        assertEquals(listOf("blocked", "BACK", "home", "home", "retry"), events)
    }

    @Test fun `failed return to home pauses instead of retrying the station`() = runBlocking {
        var collects = 0
        val error = runCatching {
            runBaiduAiFallback<Nothing>(collect = { collects++; throw BaiduUiBlockedException(RecoveryCause.UNEXPECTED_PAGE, "blocked") },
                recover = { throw BaiduAiRecoveryPausedException() })
        }.exceptionOrNull()
        assertTrue(error is BaiduAiRecoveryPausedException)
        assertEquals(1, collects)
    }

    @Test fun `progress extends stage time only once per milestone`() {
        val watchdog = RecoveryStageWatchdog(60_000L, 0)
        watchdog.markProgress("confirmed-city-A", 30_000L)
        watchdog.markProgress("confirmed-city-A", 50_000L)
        assertFalse(watchdog.stalled(89_999L))
        assertTrue(watchdog.stalled(90_000L))
        watchdog.markProgress("confirmed-city-B", 90_000L)
        assertFalse(watchdog.stalled(90_000L))
    }

    @Test fun `recovery requests are throttled across adjacent tasks`() {
        val throttle = RecoveryRateLimit(180_000L)
        assertTrue(throttle.tryAcquire(0))
        assertFalse(throttle.tryAcquire(179_999))
        assertTrue(throttle.tryAcquire(180_000))
    }

    @Test fun `success and ordinary failures never call recovery`() = runBlocking {
        assertEquals("ok", runBaiduAiFallback(collect = { "ok" }, recover = { error("unexpected model call") }))
        val failure = IllegalStateException("station not found")
        val error = runCatching {
            runBaiduAiFallback<Nothing>(collect = { throw failure }, recover = { error("unexpected model call") })
        }.exceptionOrNull()
        assertSame(failure, error)
    }

    @Test fun `a blocking error permits a single recovery and single retry`() = runBlocking {
        val blocker = BaiduUiBlockedException(RecoveryCause.POPUP, "blocker")
        var collects = 0
        var recovers = 0
        val error = runCatching {
            runBaiduAiFallback<Nothing>(collect = { collects++; throw blocker }, recover = { recovers++; true })
        }.exceptionOrNull()
        assertSame(blocker, error)
        assertEquals(2, collects)
        assertEquals(1, recovers)
    }

    @Test fun `model timeout preserves original error and collection cancellation is propagated`() = runBlocking {
        val blocker = BaiduUiBlockedException(RecoveryCause.STAGE_TIMEOUT, "blocker")
        var collects = 0
        val error = runCatching {
            runBaiduAiFallback<Nothing>(collect = { collects++; throw blocker }, recover = {
                withTimeoutOrNull(20) { awaitCancellation() } ?: false
            })
        }.exceptionOrNull()
        assertSame(blocker, error)
        assertEquals(1, collects)
        val cancelled = CancellationException("stop collection")
        val cancellation = runCatching {
            runBaiduAiFallback<Nothing>(collect = { throw cancelled }, recover = { error("unexpected model call") })
        }.exceptionOrNull()
        assertSame(cancelled, cancellation)
    }
}
