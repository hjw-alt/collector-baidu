package com.tigercode.evcollector.accessibility

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect

/** The place-name action sheet observed on Baidu's charging-station detail page. */
internal object BaiduPlaceNameSheetDetector {
    data class CancelableSheet(
        val panel: NodeSnapshot,
        val cancel: NodeSnapshot,
        val viewportWidth: Int,
        val viewportHeight: Int,
    )

    // The captured WebView extends three pixels past the actual window edges.
    private const val EDGE_ROUNDING_PX = 4
    private const val COPY = "复制地点名称"
    private const val CANCEL = "取消"

    private fun hasLabel(node: NodeSnapshot, label: String): Boolean =
        node.text.trim() == label || node.contentDescription.trim() == label

    private fun descendants(root: NodeSnapshot): List<NodeSnapshot> = buildList {
        fun walk(node: NodeSnapshot) {
            add(node)
            node.children.forEach(::walk)
        }
        walk(root)
    }

    private fun panels(snapshot: NodeSnapshot, width: Int, height: Int): List<NodeSnapshot> {
        val nodes = descendants(snapshot)
        if (nodes.none { hasLabel(it, "地点详情") }) return emptyList()
        // Application display metrics exclude system bars on this MIUI device;
        // accessibility bounds use the full window's screen coordinate space.
        val viewportWidth = maxOf(width, snapshot.bounds.right)
        val viewportHeight = maxOf(height, snapshot.bounds.bottom)
        return nodes.filter { isPanel(it, viewportWidth, viewportHeight) }
    }

    private fun isPanel(panel: NodeSnapshot, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || panel.className != "android.view.View" || !panel.enabled) return false
        val b = panel.bounds
        if (!b.isValid || b.left < -EDGE_ROUNDING_PX || b.right > width + EDGE_ROUNDING_PX || b.bottom > height + EDGE_ROUNDING_PX ||
            b.top < height * 0.55 || b.width < width * 0.80 || b.height > height * 0.40 ||
            panel.children.size !in 2..4) return false
        val title = panel.children[0]
        val copy = panel.children[1]
        if (title.text.isBlank() || hasLabel(title, COPY) || hasLabel(title, CANCEL) ||
            !hasLabel(copy, COPY) || panel.children.drop(2).any { !hasLabel(it, CANCEL) }) return false
        // Both visible rows must belong to this bottom sheet, not unrelated background controls.
        return listOf(title, copy).all {
            val r = it.bounds
            it.className == "android.view.View" && r.isValid && r.width >= width * 0.80 &&
                r.left >= b.left && r.right <= b.right && r.top >= b.top && r.bottom <= b.bottom
        }
    }

    fun isVisible(snapshot: NodeSnapshot, width: Int, height: Int): Boolean =
        panels(snapshot, width, height).isNotEmpty()

    fun cancelableSheet(snapshot: NodeSnapshot, width: Int, height: Int): CancelableSheet? {
        val viewportWidth = maxOf(width, snapshot.bounds.right)
        val viewportHeight = maxOf(height, snapshot.bounds.bottom)
        val panel = panels(snapshot, viewportWidth, viewportHeight).singleOrNull() ?: return null
        val cancel = cancelForPanel(panel, viewportWidth, viewportHeight) ?: return null
        return CancelableSheet(panel, cancel, viewportWidth, viewportHeight)
    }

    fun cancelForPanel(panel: NodeSnapshot, width: Int, height: Int): NodeSnapshot? {
        if (!isPanel(panel, width, height)) return null
        val cancel = panel.children.filter { hasLabel(it, CANCEL) }.singleOrNull() ?: return null
        if (!cancel.enabled || !cancel.clickable || cancel.children.isNotEmpty() ||
            cancel.className != "android.view.View") return null
        val b = cancel.bounds
        // MIUI/WebView exposes the visible Cancel with [0,0][0,0]. It is a semantic
        // ACTION_CLICK target, never a gesture at the invalid rectangle's centre.
        val safeBounds = b == Rect() || (b.isValid && b.left >= -EDGE_ROUNDING_PX && b.right <= width + EDGE_ROUNDING_PX &&
            b.top >= panel.children[1].bounds.top && b.bottom <= height + EDGE_ROUNDING_PX &&
            b.width >= width * 0.50 && b.height <= height * 0.15)
        return cancel.takeIf { safeBounds }
    }
}
