package com.tigercode.evcollector.recovery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class BaiduHomeRecoveryStep { RETRY_RETURN, RESTART_MAP, WAIT_RESTART_HOME }

/** Two navigation attempts, then one process restart. Only a confirmed home permits collection. */
internal suspend fun recoverBaiduHome(
    initial: AiRecoveryScene,
    firstAction: RecoveryAction,
    readScene: () -> AiRecoveryScene?,
    applyAction: (AiRecoveryScene, RecoveryAction) -> Boolean,
    restartMap: suspend () -> Boolean,
    canOperate: () -> Boolean,
    awaitNextRead: suspend () -> Unit,
    onStep: (BaiduHomeRecoveryStep) -> Unit = {},
): AiRecoveryScene? {
    var protectedPageSeen = BaiduAiRecoveryPolicy.isProtected(initial.snapshot)
    fun active(): Boolean = !protectedPageSeen && canOperate()
    fun readSafeScene(): AiRecoveryScene? {
        if (!active()) return null
        val fresh = readScene() ?: return null
        if (BaiduAiRecoveryPolicy.isProtected(fresh.snapshot)) {
            protectedPageSeen = true
            return null
        }
        return fresh
    }
    suspend fun awaitReadableScene(): AiRecoveryScene? {
        repeat(8) {
            currentCoroutineContext().ensureActive()
            if (!active()) return null
            readSafeScene()?.let { return it }
            if (!active()) return null
            awaitNextRead()
        }
        return null
    }
    suspend fun navigate(scene: AiRecoveryScene, action: RecoveryAction): AiRecoveryScene? =
        returnToBaiduHome(
            initial = scene,
            firstAction = action,
            readScene = ::readSafeScene,
            applyAction = { expected, next -> active() && applyAction(expected, next) },
            awaitNextRead = {
                awaitNextRead()
                currentCoroutineContext().ensureActive()
            },
        )?.takeIf { active() }

    repeat(2) { attempt ->
        currentCoroutineContext().ensureActive()
        if (!active()) return null
        if (attempt == 1) {
            onStep(BaiduHomeRecoveryStep.RETRY_RETURN)
            awaitNextRead()
        }
        val fresh = awaitReadableScene()
        if (!active()) return null
        if (fresh != null) {
            // Never reuse a model-selected control after its page has changed.
            val action = if (attempt == 0 && fresh == initial) firstAction else RecoveryAction.BACK
            navigate(fresh, action)?.let { return it }
        }
    }
    currentCoroutineContext().ensureActive()
    if (!active()) return null
    onStep(BaiduHomeRecoveryStep.RESTART_MAP)
    if (!active() || !restartMap()) return null
    currentCoroutineContext().ensureActive()
    if (!active()) return null
    onStep(BaiduHomeRecoveryStep.WAIT_RESTART_HOME)
    var previousHome: AiRecoveryScene? = null
    var latest: AiRecoveryScene? = null
    // Startup can temporarily expose no tree or a splash screen. Give it up to 20 seconds.
    repeat(40) {
        awaitNextRead()
        currentCoroutineContext().ensureActive()
        if (!active()) return null
        val fresh = readSafeScene()
        if (!active()) return null
        latest = fresh
        if (fresh != null && BaiduAiRecoveryPolicy.isHome(fresh.snapshot)) {
            if (previousHome?.windowId == fresh.windowId) return fresh
            previousHome = fresh
        } else {
            previousHome = null
        }
    }
    // Some map versions restore a detail page on launch; allow one bounded return from there.
    return latest?.let { navigate(it, RecoveryAction.BACK) }
}
