package com.tigercode.evcollector.core.parser

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import kotlin.math.abs

/** The coupon purchase sheet has an unlabelled X beside its purchase-history header. */
object BaiduCouponSheetDetector {
    private fun label(node: NodeSnapshot) = node.text.ifBlank { node.contentDescription }.trim()

    private fun nodes(root: NodeSnapshot): List<NodeSnapshot> = buildList {
        fun walk(node: NodeSnapshot) {
            add(node)
            node.children.forEach(::walk)
        }
        walk(root)
    }

    fun isVisible(snapshot: NodeSnapshot): Boolean = nodes(snapshot).any {
        label(it) == "充电优惠券套餐" && it.enabled && it.bounds.isValid &&
            it.bounds.bottom > snapshot.bounds.top && it.bounds.top < snapshot.bounds.bottom
    }

    fun closeBounds(snapshot: NodeSnapshot, width: Int, height: Int): Rect? {
        if (width <= 0 || height <= 0) return null
        val all = nodes(snapshot)
        val title = all.firstOrNull {
            label(it) == "充电优惠券套餐" && it.enabled && it.bounds.isValid &&
                it.bounds.left >= 0 && it.bounds.right <= width &&
                it.bounds.top >= height * 0.25 && it.bounds.bottom < height * 0.95
        } ?: return null
        // Use the actual sheet subtree: the station page behind it can also contain coupon text.
        val sheet = all.asReversed().firstOrNull { parent ->
            parent.children.any { it === title } && nodes(parent).any { label(it) == "购买记录" }
        } ?: return null
        val contents = nodes(sheet)
        val history = contents.firstOrNull {
            label(it) == "购买记录" && it.bounds.isValid &&
                abs(it.bounds.centerY - title.bounds.centerY) <= title.bounds.height
        } ?: return null
        if (contents.none { label(it) == "购买" && it.bounds.top > title.bounds.bottom }) return null
        val candidates = contents.filter { node ->
            val b = node.bounds
            val value = label(node)
            val closeLabel = value in setOf("关闭", "关闭弹窗", "关闭弹层", "×", "✕", "✖", "close")
            node.enabled && b.isValid && node.children.isEmpty() &&
                (value.isEmpty() || closeLabel) &&
                b.width >= width * 0.03 && b.width <= width * 0.14 &&
                b.height >= width * 0.03 && b.height <= width * 0.14 &&
                abs(b.width - b.height) <= maxOf(b.width, b.height) * 0.3 &&
                b.centerX >= width * 0.84 && b.right <= width &&
                b.left >= history.bounds.right - width * 0.02 &&
                abs(b.centerY - title.bounds.centerY) <= title.bounds.height &&
                b.top < title.bounds.bottom && b.bottom > title.bounds.top
        }
        return candidates.singleOrNull()?.bounds
    }
}
