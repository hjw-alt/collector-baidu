package com.tigercode.evcollector.recovery

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.PageKind
import com.tigercode.evcollector.core.parser.BaiduCouponSheetDetector
import com.tigercode.evcollector.core.parser.PageAssessor

internal enum class RecoveryStage(val timeoutMs: Long) {
    OPEN_MAP(60_000), SEARCH_INPUT(90_000), SEARCH(120_000), OPEN_DETAIL(60_000),
    READ_DETAIL(75_000), PRICE(75_000),
}

internal enum class RecoveryCause { POPUP, UNEXPECTED_PAGE, STAGE_TIMEOUT }
internal enum class RecoveryAction(val label: String) {
    CLOSE_OVERLAY("关闭弹窗"), CLICK_NODE("点击弹窗节点"), BACK("返回上一页"),
}
internal enum class RecoveryDecision(val firstAction: RecoveryAction?) {
    PAUSE_AND_BACK(RecoveryAction.BACK), PAUSE_AND_CLOSE_OVERLAY(RecoveryAction.CLOSE_OVERLAY),
    CLICK_NODE(RecoveryAction.CLICK_NODE), NONE(null),
}

internal data class RecoveryRecommendation(val decision: RecoveryDecision, val nodeId: String? = null)
internal data class AiRecoveryScene(val windowId: Int, val snapshot: NodeSnapshot)

internal class BaiduAiRecoveryPausedException(
    message: String = "未确认回到百度地图首页，请检查地图页面后手动开始采集",
) : IllegalStateException(message)

internal class BaiduUiBlockedException(
    val causeCode: RecoveryCause,
    message: String,
) : IllegalStateException(message)

internal suspend fun <T> runBaiduAiFallback(
    collect: suspend () -> T,
    recover: suspend (RecoveryCause) -> Boolean,
): T = try {
    collect()
} catch (error: BaiduUiBlockedException) {
    if (!recover(error.causeCode)) throw error
    collect()
}

internal data class RecoveryEvidence(
    val stage: RecoveryStage,
    val cause: RecoveryCause,
    val page: PageKind,
    val signals: Set<String>,
    val allowedActions: Set<RecoveryDecision>,
    val dialogNodes: List<DialogNodeHint>,
)

internal object BaiduAiRecoveryPolicy {
    private val whitespace = Regex("\\s+")
    private val protectedPhrases = listOf(
        "人机验证", "安全验证", "请完成验证", "身份验证", "身份核验", "图形验证", "验证码", "拖动滑块", "滑块验证",
        "行为验证", "访问异常", "访问过于频繁", "请进行验证", "请验证", "captcha", "verifyyouarehuman",
        "请先登录", "立即登录", "登录百度账号", "登录后继续", "登录后使用", "手机号登录", "登录密码", "短信登录",
        "用户协议和隐私", "隐私政策并同意", "允许百度地图", "请求权限", "开启定位权限", "授权登录", "确认授权",
        "确认支付", "立即支付", "支付密码", "确认订单", "提交订单", "立即付款", "确认购买", "确认取消订单", "确认删除",
    )
    private val protectedLabels = setOf("登录", "注册/登录", "同意并继续", "同意并使用", "仅在使用中允许", "仅此一次", "不允许")
    private val closeLabels = setOf("关闭", "关闭广告", "跳过广告", "关闭弹窗", "关闭弹层", "close", "×", "✕", "✖")
    private val promoLabels = setOf("广告", "推广", "赞助", "跳过广告", "关闭广告")

    fun evidence(root: NodeSnapshot, stage: RecoveryStage, cause: RecoveryCause): RecoveryEvidence? {
        val labels = labels(root)
        val joined = labels.joinToString("")
        if (isProtected(root, labels, joined)) return null

        val page = PageAssessor.assess(root).kind
        val dialogNodes = BaiduDialogNodes.controls(root).map { it.hint }
        val signals = buildSet {
            if (dialogNodes.isNotEmpty()) add("DIALOG_NODES")
            if (labels.any { it in promoLabels } || "广告剩余" in joined || "开屏广告" in joined ||
                "领券优惠" in joined || "充电券包" in joined) add("PROMOTION")
            if ("正在加载" in joined || "正在检索" in joined || "加载中" in joined) add("LOADING")
            if ("网络异常" in joined || "加载失败" in joined || "请求失败" in joined) add("LOAD_ERROR")
            if ("版本更新" in joined || "立即更新" in joined) add("UPDATE_POPUP")
            if ("地点名称" in joined && "取消" in labels) add("PLACE_NAME_POPUP")
            if ("没有找到相关结果" in joined || "暂无结果" in joined || "换个搜索词" in joined) add("EMPTY_RESULTS")
        }
        // An empty result page alone is not an error or a recovery opportunity.
        if ("EMPTY_RESULTS" in signals && cause != RecoveryCause.POPUP && "PROMOTION" !in signals && page != PageKind.POPUP &&
            dialogNodes.isEmpty()) return null
        val actions = buildSet {
            add(RecoveryDecision.NONE)
            add(RecoveryDecision.PAUSE_AND_BACK)
            if (dialogNodes.any { it.dismiss }) add(RecoveryDecision.CLICK_NODE)
            if (dialogNodes.isEmpty() &&
                (cause == RecoveryCause.POPUP || page == PageKind.POPUP || "PROMOTION" in signals || "UPDATE_POPUP" in signals) &&
                closeTarget(root) != null) add(RecoveryDecision.PAUSE_AND_CLOSE_OVERLAY)
        }
        return RecoveryEvidence(stage, cause, page, signals, actions, dialogNodes)
    }

    fun hasNodeDialog(root: NodeSnapshot): Boolean = BaiduDialogNodes.controls(root).isNotEmpty()

    fun clickNodeTarget(expected: AiRecoveryScene, fresh: AiRecoveryScene, nodeId: String?): NodeSnapshot? {
        if (nodeId == null || expected != fresh || isProtected(fresh.snapshot)) return null
        return BaiduDialogNodes.controls(fresh.snapshot).singleOrNull { it.hint.nodeId == nodeId && it.hint.dismiss }?.node
    }

    fun isProtected(root: NodeSnapshot): Boolean {
        val labels = labels(root)
        return isProtected(root, labels, labels.joinToString(""))
    }

    fun hasUnrecognisedBlockingAd(root: NodeSnapshot): Boolean = labels(root).any {
        it == "跳过广告" || it == "开屏广告" || it.startsWith("广告剩余")
    }

    fun isHome(root: NodeSnapshot): Boolean {
        if (PageAssessor.assess(root).kind != PageKind.HOME || hasBlockingOverlay(root)) return false
        // A home search entry is not itself an editable field, and its ID can vary.
        return nodes(root).none { it.bounds.isValid &&
            (it.className.endsWith("EditText") || it.viewId.endsWith(":id/tvSearchBoxInput")) }
    }

    private fun hasBlockingOverlay(root: NodeSnapshot): Boolean {
        val labels = labels(root)
        val joined = labels.joinToString("")
        // A plain × also appears on inline home banners; don't mistake it for a modal.
        return isProtected(root, labels, joined) || labels.any { it in promoLabels || it == "关闭弹窗" || it == "关闭弹层" } ||
            "广告剩余" in joined || "开屏广告" in joined || "版本更新" in joined || "立即更新" in joined ||
            ("地点名称" in joined && "取消" in labels) || BaiduCouponSheetDetector.isVisible(root) || hasNodeDialog(root)
    }

    private fun isProtected(root: NodeSnapshot, labels: List<String>, joined: String): Boolean =
        protectedPhrases.any { it in joined } || labels.any { it in protectedLabels } ||
        nodes(root).any { it.password || it.viewId.contains("captcha", ignoreCase = true) || it.className.endsWith("SecureEditText") }

    private fun labels(root: NodeSnapshot): List<String> = nodes(root).flatMap { sequenceOf(it.text, it.contentDescription) }
        .map { it.trim().lowercase().replace(whitespace, "") }.filter { it.isNotEmpty() }.toList()

    fun closeTarget(root: NodeSnapshot): NodeSnapshot? = nodes(root).filter { node ->
        node.enabled && node.clickable && node.bounds.isValid &&
            node.bounds.left >= root.bounds.left && node.bounds.top >= root.bounds.top &&
            node.bounds.right <= root.bounds.right && node.bounds.bottom <= root.bounds.bottom &&
            node.bounds.width <= root.bounds.width / 3 && node.bounds.height <= root.bounds.height / 4 &&
            listOf(node.text, node.contentDescription).any { it.trim().lowercase() in closeLabels }
    }.singleOrNull()

    private fun nodes(root: NodeSnapshot): Sequence<NodeSnapshot> = sequence {
        yield(root)
        for (child in root.children) yieldAll(nodes(child))
    }
}

/** Once home is seen, only read again; never Back out of a possible home screen. */
internal suspend fun returnToBaiduHome(
    initial: AiRecoveryScene,
    firstAction: RecoveryAction,
    readScene: () -> AiRecoveryScene?,
    applyAction: (AiRecoveryScene, RecoveryAction) -> Boolean,
    awaitNextRead: suspend () -> Unit,
): AiRecoveryScene? {
    suspend fun confirmHome(seen: AiRecoveryScene): AiRecoveryScene? {
        awaitNextRead()
        return readScene()?.takeIf { it.windowId == seen.windowId && BaiduAiRecoveryPolicy.isHome(it.snapshot) }
    }

    var scene = initial
    var action = firstAction
    var backs = 0
    while (true) {
        if (BaiduAiRecoveryPolicy.isProtected(scene.snapshot)) return null
        if (BaiduAiRecoveryPolicy.isHome(scene.snapshot)) return confirmHome(scene)
        if (action == RecoveryAction.BACK) {
            if (backs == 5) return null
            backs++
        }
        if (!applyAction(scene, action)) {
            // The action guard may have seen home arrive just before dispatch.
            val current = readScene()?.takeIf { BaiduAiRecoveryPolicy.isHome(it.snapshot) } ?: return null
            return confirmHome(current)
        }

        var next: AiRecoveryScene? = null
        for (poll in 0 until 8) {
            awaitNextRead()
            val fresh = readScene() ?: continue
            if (BaiduAiRecoveryPolicy.isProtected(fresh.snapshot)) return null
            if (BaiduAiRecoveryPolicy.isHome(fresh.snapshot)) return confirmHome(fresh)
            // Do not stack return events on the same page or during an animation.
            if (poll >= 2 && fresh != scene &&
                (action != RecoveryAction.CLICK_NODE || !BaiduAiRecoveryPolicy.hasNodeDialog(fresh.snapshot))) {
                next = fresh
                break
            }
        }
        scene = next ?: return null
        action = RecoveryAction.BACK
    }
}

/** Shared across the foreground service and activity engines. */
internal class RecoveryRateLimit(private val cooldownMs: Long = 3 * 60_000L) {
    private var lastAt: Long? = null

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        if (lastAt?.let { now - it < cooldownMs } == true) return false
        lastAt = now
        return true
    }
}

internal class RecoveryStageWatchdog(private val timeoutMs: Long, startedAt: Long) {
    private var lastProgressAt = startedAt
    private val knownProgress = mutableSetOf<String>()

    fun markProgress(marker: String, now: Long) {
        if (knownProgress.add(marker)) lastProgressAt = now
    }

    fun stalled(now: Long): Boolean = now - lastProgressAt >= timeoutMs
}
