package com.tigercode.evcollector.recovery

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BaiduHomeRecoveryTest {
    private fun page(text: String, window: Int = 1) = AiRecoveryScene(window, NodeSnapshot(
        bounds = Rect(0, 0, 540, 1080), children = listOf(NodeSnapshot(text = text)),
    ))
    private val detail = page("站点详情")
    private val home = AiRecoveryScene(1, NodeSnapshot(bounds = Rect(0, 0, 540, 1080), children = listOf(
        NodeSnapshot(viewId = "com.baidu.BaiduMap:id/maphome_searchbar_bg", bounds = Rect(12, 65, 520, 132)),
    )))

    private inner class Harness {
        var active = true
        var reads = 0
        var waits = 0
        var restarts = 0
        val actions = mutableListOf<RecoveryAction>()
        val steps = mutableListOf<BaiduHomeRecoveryStep>()
        var read: () -> AiRecoveryScene? = { detail }
        var apply: (AiRecoveryScene, RecoveryAction) -> Boolean = { _, _ -> false }
        var restart: suspend () -> Boolean = { false }
        var wait: suspend () -> Unit = {}
        var step: (BaiduHomeRecoveryStep) -> Unit = {}

        suspend fun recover(firstAction: RecoveryAction = RecoveryAction.BACK, initial: AiRecoveryScene = detail) =
            recoverBaiduHome(initial, firstAction,
                readScene = { reads++; read() },
                applyAction = { scene, action -> actions += action; apply(scene, action) },
                restartMap = { restarts++; restart() },
                canOperate = { active },
                awaitNextRead = { waits++; wait() },
                onStep = { steps += it; step(it) },
            )
    }

    @Test fun `an unchanged page gets a second return attempt without a restart`() = runBlocking {
        val h = Harness()
        h.apply = { _, _ -> true }
        h.read = { if (h.actions.size >= 2) home else detail }
        assertEquals(home, h.recover())
        assertEquals(listOf(RecoveryAction.BACK, RecoveryAction.BACK), h.actions)
        assertEquals(0, h.restarts)
        assertEquals(listOf(BaiduHomeRecoveryStep.RETRY_RETURN), h.steps)
    }

    @Test fun `a stale model click becomes a fresh return instead of clicking an old control`() = runBlocking {
        val h = Harness()
        h.read = { if (h.actions.isEmpty()) page("详情内容已刷新") else home }
        h.apply = { _, _ -> true }
        assertEquals(home, h.recover(RecoveryAction.CLICK_NODE))
        assertEquals(listOf(RecoveryAction.BACK), h.actions)
        assertEquals(0, h.restarts)
    }

    @Test fun `a rejected model click is not repeated on the second attempt`() = runBlocking {
        val h = Harness()
        h.read = { if (h.actions.size >= 2) home else detail }
        h.apply = { _, action -> action == RecoveryAction.BACK }
        assertEquals(home, h.recover(RecoveryAction.CLICK_NODE))
        assertEquals(listOf(RecoveryAction.CLICK_NODE, RecoveryAction.BACK), h.actions)
        assertEquals(0, h.restarts)
    }

    @Test fun `two failed returns restart once and resume only after stable home`() = runBlocking {
        val h = Harness()
        var restarted = false
        var homeReads = 0
        h.restart = { restarted = true; true }
        h.read = { if (restarted) home.also { homeReads++ } else detail }
        assertEquals(home, h.recover())
        assertEquals(1, h.restarts)
        assertEquals(2, homeReads)
        assertEquals(listOf(BaiduHomeRecoveryStep.RETRY_RETURN, BaiduHomeRecoveryStep.RESTART_MAP,
            BaiduHomeRecoveryStep.WAIT_RESTART_HOME), h.steps)
    }

    @Test fun `a restarted map restored to details can still return home`() = runBlocking {
        val h = Harness()
        var restarted = false
        var leftDetail = false
        h.restart = { restarted = true; true }
        h.apply = { _, _ -> restarted.also { if (it) leftDetail = true } }
        h.read = { if (leftDetail) home else detail }
        assertEquals(home, h.recover())
        assertEquals(1, h.restarts)
        assertEquals(3, h.actions.size)
    }

    @Test fun `a failed restart never resumes collection or repeats the restart`() = runBlocking {
        val h = Harness()
        assertNull(h.recover())
        assertEquals(1, h.restarts)
        assertFalse(h.steps.contains(BaiduHomeRecoveryStep.WAIT_RESTART_HOME))
    }

    @Test fun `a reopened map without home eventually stops with bounded work`() = runBlocking {
        val h = Harness()
        h.restart = { true }
        assertNull(h.recover())
        assertEquals(1, h.restarts)
        assertEquals(3, h.actions.size)
        assertTrue(h.waits < 100)
    }

    @Test fun `protected pages during either navigation attempt prevent restarting`() = runBlocking {
        for (protectOnRetry in listOf(false, true)) {
            val h = Harness()
            var protected = !protectOnRetry
            h.step = { if (it == BaiduHomeRecoveryStep.RETRY_RETURN) protected = true }
            h.read = { if (protected) page("请完成验证") else detail }
            assertNull(h.recover())
            assertEquals(0, h.restarts)
            assertTrue(h.actions.size <= 1)
        }
    }

    @Test fun `a protected page after restart blocks further navigation and resumption`() = runBlocking {
        val h = Harness()
        var restarted = false
        h.restart = { restarted = true; true }
        h.read = { if (restarted) page("登录后继续") else detail }
        assertNull(h.recover())
        assertEquals(1, h.restarts)
        assertEquals(2, h.actions.size)
    }

    @Test fun `manual stop at the restart boundary prevents invoking the restarter`() = runBlocking {
        val h = Harness()
        h.step = { if (it == BaiduHomeRecoveryStep.RESTART_MAP) h.active = false }
        assertNull(h.recover())
        assertEquals(0, h.restarts)
    }

    @Test fun `manual stop while restarting prevents home checks and resumption`() = runBlocking {
        val h = Harness()
        h.restart = { h.active = false; true }
        assertNull(h.recover())
        assertFalse(h.steps.contains(BaiduHomeRecoveryStep.WAIT_RESTART_HOME))
    }

    @Test fun `coroutine cancellation during restart propagates immediately`() = runBlocking {
        val h = Harness()
        val stopped = CancellationException("manual stop")
        h.restart = { throw stopped }
        assertSame(stopped, runCatching { h.recover() }.exceptionOrNull())
        assertFalse(h.steps.contains(BaiduHomeRecoveryStep.WAIT_RESTART_HOME))
    }

    @Test fun `startup requires two consecutive home reads in the same window`() = runBlocking {
        val h = Harness()
        var restarted = false
        var startupReads = 0
        h.restart = { restarted = true; true }
        h.read = { if (restarted) home.copy(windowId = ++startupReads % 2 + 1) else detail }
        assertNull(h.recover())
        assertEquals(2, h.actions.size)
        assertEquals(1, h.restarts)
    }

    @Test fun `transient startup without a readable tree can still confirm home`() = runBlocking {
        val h = Harness()
        var restarted = false
        var startupReads = 0
        h.restart = { restarted = true; true }
        h.read = { if (!restarted) detail else if (++startupReads < 4) null else home }
        assertEquals(home, h.recover())
        assertEquals(5, startupReads)
        assertEquals(1, h.restarts)
    }

    @Test fun `back accepts refreshed content but never another window home or protected pages`() {
        val refreshed = page("详情电桩数量已刷新")
        assertTrue(BaiduAiRecoveryPolicy.canApplyAction(detail, refreshed, RecoveryAction.BACK))
        for (action in listOf(RecoveryAction.CLICK_NODE, RecoveryAction.CLOSE_OVERLAY)) {
            assertFalse(BaiduAiRecoveryPolicy.canApplyAction(detail, refreshed, action))
        }
        for (unsafe in listOf(refreshed.copy(windowId = 2), home, page("确认支付"))) {
            assertFalse(BaiduAiRecoveryPolicy.canApplyAction(detail, unsafe, RecoveryAction.BACK))
        }
    }

    @Test fun `a restart recovery retries only the task which was interrupted`() = runBlocking {
        val h = Harness()
        var restarted = false
        var collects = 0
        h.restart = { restarted = true; true }
        h.read = { if (restarted) home else detail }
        val result = runBaiduAiFallback(collect = {
            if (++collects == 1) throw BaiduUiBlockedException(RecoveryCause.UNEXPECTED_PAGE, "blocked")
            "current task collected"
        }, recover = { h.recover() != null })
        assertEquals("current task collected", result)
        assertEquals(2, collects)
        assertEquals(1, h.restarts)
    }
}
