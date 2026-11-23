package com.tigercode.evcollector.recovery

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect

/** Only these standardized fields leave the device. */
internal data class DialogNodeHint(
    val nodeId: String,
    val label: String,
    val kind: String,
    val position: String,
    val dismiss: Boolean,
)

internal object BaiduDialogNodes {
    private val icons = setOf("×", "✕", "✖")
    private val dismissLabels = icons + setOf(
        "关闭", "关闭广告", "跳过广告", "关闭弹窗", "关闭弹层", "取消", "我知道了", "知道了", "暂不需要", "不需要",
        "暂不领取", "跳过", "暂不更新", "以后再说", "稍后再说", "下次再说", "暂不体验", "暂不参加", "暂不使用",
        "不用了", "先不了", "不了，谢谢", "close", "dismiss", "not now", "no thanks",
    )
    private val contextLabels = setOf("立即领取", "立即更新", "立即查看", "立即体验", "去看看", "查看详情", "了解更多")
    private val dialogId = Regex("dialog|popup|modal", RegexOption.IGNORE_CASE)
    private val spacing = Regex("\\s+")
    private const val MAX_CONTROLS = 6

    internal data class Control(val hint: DialogNodeHint, val node: NodeSnapshot)
    private data class Button(val node: NodeSnapshot, val label: String)

    fun controls(root: NodeSnapshot): List<Control> {
        if (!root.bounds.isValid) return emptyList()
        val (panel, buttons) = panels(root, root.bounds).singleOrNull() ?: return emptyList()
        val counts = buttons.groupingBy { it.label }.eachCount()
        return buttons.mapIndexed { index, (node, label) ->
            val kind = when {
                node.className.endsWith("ImageButton") || label in icons -> "ICON_BUTTON"
                node.className.endsWith("Button") -> "BUTTON"
                else -> "CLICKABLE_CONTROL"
            }
            val column = (3L * (node.bounds.centerX - panel.bounds.left) / panel.bounds.width).toInt()
            val row = (3L * (node.bounds.centerY - panel.bounds.top) / panel.bounds.height).toInt()
            val position = listOf("TOP", "MIDDLE", "BOTTOM")[row] + "_" + listOf("LEFT", "CENTER", "RIGHT")[column]
            Control(DialogNodeHint("n${index + 1}", label, kind, position, label in dismissLabels && counts[label] == 1), node)
        }
    }

    private fun panels(node: NodeSnapshot, screen: Rect): List<Pair<NodeSnapshot, List<Button>>> {
        if (!node.visibleToUser || node.password) return emptyList()
        val nested = node.children.flatMap { panels(it, screen) }
        if (nested.isNotEmpty()) return nested
        val nativeDialog = node.className.endsWith("Dialog")
        val compact = node.bounds.width >= screen.width / 3 && node.bounds.height >= screen.height / 12 &&
            node.bounds.height <= screen.height * 4L / 5 && contains(screen, node.bounds)
        if (node.clickable || node.scrollable || (!nativeDialog && !compact)) return emptyList()
        val explicit = nativeDialog || dialogId.containsMatchIn(node.viewId.substringAfterLast('/'))
        val buttons = node.children.asSequence().flatMap { buttons(it, node.bounds) }.take(MAX_CONTROLS + 1).toList()
        if (buttons.size !in 1..MAX_CONTROLS || buttons.none { it.label in dismissLabels }) return emptyList()
        // An unnamed panel needs separate opt-in and opt-out controls, not just a lone inline ×.
        if (!explicit && buttons.none { it.label in contextLabels }) return emptyList()
        return listOf(node to buttons)
    }

    private fun buttons(node: NodeSnapshot, panel: Rect): Sequence<Button> = sequence {
        if (!node.visibleToUser || node.password || !node.enabled || node.scrollable) return@sequence
        if (!node.clickable) {
            for (child in node.children) yieldAll(buttons(child, panel))
            return@sequence
        }
        if (!contains(panel, node.bounds) || node.bounds.width * 5L > panel.width * 4L ||
            node.bounds.height * 2L > panel.height) return@sequence
        val labels = labelsForButton(node) ?: return@sequence
        val label = labels.singleOrNull() ?: return@sequence
        if (label in dismissLabels || label in contextLabels) yield(Button(node, label))
    }

    private fun labelsForButton(node: NodeSnapshot): Set<String>? {
        val labels = mutableSetOf<String>()
        fun visit(current: NodeSnapshot, root: Boolean): Boolean {
            if (!current.visibleToUser) return true
            if (current.password || (!root && current.clickable)) return false
            listOf(current.text, current.contentDescription).map { it.trim().lowercase().replace(spacing, " ") }
                .filterTo(labels) { it.isNotEmpty() }
            return labels.size <= 1 && current.children.all { visit(it, false) }
        }
        return if (visit(node, true)) labels else null
    }

    private fun contains(outer: Rect, inner: Rect): Boolean = inner.isValid && outer.isValid &&
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom
}
