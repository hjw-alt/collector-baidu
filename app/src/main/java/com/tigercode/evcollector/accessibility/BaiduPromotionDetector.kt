package com.tigercode.evcollector.accessibility

import android.graphics.Bitmap
import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Only coupon promotions qualify: neither a search clear icon nor a price-sheet close. */
internal object BaiduPromotionDetector {
    data class CloseTarget(val bounds: Rect, val evidence: String, val visual: Boolean)
    data class Observation(val target: CloseTarget?, val imageChecked: Boolean = false)

    fun fromNodes(snapshot: NodeSnapshot, width: Int, height: Int): CloseTarget? {
        val nodes = mutableListOf<NodeSnapshot>()
        fun walk(node: NodeSnapshot) {
            nodes.add(node)
            node.children.forEach(::walk)
        }
        walk(snapshot)
        val labels = nodes.flatMap { listOf(it.text, it.contentDescription) }
            .joinToString(" ").replace(Regex("\\s+"), "")
        val couponPromotion = labels.contains("充电券包") &&
            (labels.contains("超值购") || labels.contains("购买") || labels.contains("满15减3"))
        if (!couponPromotion) return null
        return nodes.firstOrNull { node ->
            val bounds = node.bounds
            val closeLabel = listOf(node.text, node.contentDescription).any {
                it.trim().lowercase() in setOf("关闭", "关闭广告", "关闭弹窗", "关闭弹层", "close", "×", "✕", "✖")
            }
            val closeId = Regex("(?:^|[_/])(?:close|dismiss)(?:$|[_-])", RegexOption.IGNORE_CASE)
                .containsMatchIn(node.viewId)
            node.enabled && bounds.isValid && (closeLabel || closeId) &&
                bounds.width in (width * 0.02).toInt()..(width * 0.18).toInt() &&
                bounds.height in (width * 0.02).toInt()..(width * 0.18).toInt() &&
                bounds.centerX in (width * 0.35).toInt()..(width * 0.65).toInt() &&
                bounds.centerY in (height * 0.48).toInt()..(height * 0.88).toInt()
        }?.let { CloseTarget(it.bounds, "充电券包广告及关闭控件", visual = false) }
    }

    /**
     * Some Baidu promotions are one image and expose no close node. Require the
     * dimmed backdrop, white coupon cards, orange purchase bar, a white X AND
     * its grey circle. Coordinates come from the observed icon, never a fixed tap.
     */
    fun fromImage(source: Bitmap): CloseTarget? {
        val width = min(540, source.width)
        val height = (source.height.toDouble() * width / source.width).roundToInt()
        if (width < 180 || height < width) return null
        val image = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, 0, 0, width, height)
            fun red(p: Int) = (p ushr 16) and 255
            fun green(p: Int) = (p ushr 8) and 255
            fun blue(p: Int) = p and 255
            fun white(p: Int) = red(p) >= 225 && green(p) >= 225 && blue(p) >= 225
            fun grey(p: Int, threshold: Int): Boolean {
                val lo = minOf(red(p), green(p), blue(p))
                val hi = maxOf(red(p), green(p), blue(p))
                return lo >= threshold && hi - lo <= 35
            }
            fun dark(p: Int) = maxOf(red(p), green(p), blue(p)) < 150
            fun orange(p: Int) = red(p) >= 220 && green(p) in 45..185 &&
                blue(p) < 145 && red(p) - green(p) >= 55
            fun at(x: Int, y: Int) = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
            var dim = 0
            for (x in listOf((width * 0.06).toInt(), (width * 0.94).toInt())) {
                for (part in 2..8) if (dark(at(x, height * part / 10))) dim++
            }
            if (dim < 11) return null

            val left = (width * 0.42).toInt()
            val right = (width * 0.58).toInt()
            val top = (height * 0.55).toInt()
            val bottom = (height * 0.86).toInt()
            val visited = BooleanArray(width * height)
            val queue = IntArray((right - left + 1) * (bottom - top + 1))
            for (y in top..bottom) for (x in left..right) {
                val start = y * width + x
                if (visited[start] || !white(pixels[start])) continue
                var head = 0
                var tail = 1
                queue[0] = start
                visited[start] = true
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                while (head < tail) {
                    val p = queue[head++]
                    val px = p % width
                    val py = p / width
                    minX = minOf(minX, px)
                    maxX = maxOf(maxX, px)
                    minY = minOf(minY, py)
                    maxY = maxOf(maxY, py)
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx !in left..right || ny !in top..bottom) continue
                        val next = ny * width + nx
                        if (!visited[next] && white(pixels[next])) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
                val boxWidth = maxX - minX + 1
                val boxHeight = maxY - minY + 1
                if (boxWidth !in (width * 0.018).toInt()..(width * 0.065).toInt() ||
                    abs(boxWidth - boxHeight) > maxOf(2, boxWidth / 4) || tail < boxWidth * 2
                ) continue
                val cx = (minX + maxX) / 2
                val cy = (minY + maxY) / 2
                fun nearbyWhite(px: Int, py: Int): Boolean = (-1..1).any { dy ->
                    (-1..1).any { dx -> white(at(px + dx, py + dy)) }
                }
                var diagonalHits = 0
                for (part in listOf(-3, -2, 0, 2, 3)) {
                    val offset = part * boxWidth / 8
                    if (nearbyWhite(cx + offset, cy + offset)) diagonalHits++
                    if (nearbyWhite(cx + offset, cy - offset)) diagonalHits++
                }
                if (diagonalHits < 9) continue
                // A filled square or ordinary letter must not count as an X.
                val gap = boxWidth * 3 / 8
                if (listOf(cx + gap to cy, cx - gap to cy, cx to cy + gap, cx to cy - gap)
                        .count { (px, py) -> nearbyWhite(px, py) } > 1) continue

                val radius = ((width * 0.03).toInt()..(width * 0.06).toInt()).firstOrNull { r ->
                    if (r < boxWidth * 0.9 || r > boxWidth * 1.7) return@firstOrNull false
                    var ring = 0
                    var outside = 0
                    for (part in 0 until 24) {
                        val angle = part * Math.PI / 12
                        val rx = cx + (cos(angle) * r).roundToInt()
                        val ry = cy + (sin(angle) * r).roundToInt()
                        if ((-1..1).any { dy -> (-1..1).any { dx -> grey(at(rx + dx, ry + dy), 115) } }) ring++
                        if (dark(at(cx + (cos(angle) * (r + 5)).roundToInt(),
                                cy + (sin(angle) * (r + 5)).roundToInt()))) outside++
                    }
                    ring >= 21 && outside >= 21
                } ?: continue

                var purchaseY = -1
                val step = maxOf(2, width / 180)
                for (row in (cy - (height * 0.20).toInt()).coerceAtLeast(0)..cy - (height * 0.04).toInt() step step) {
                    var hits = 0
                    var samples = 0
                    for (column in (width * 0.25).toInt()..(width * 0.75).toInt() step step) {
                        samples++
                        if (orange(at(column, row))) hits++
                    }
                    if (hits >= samples * 0.75) purchaseY = row
                }
                if (purchaseY < 0) continue
                var couponRows = 0
                var sampledRows = 0
                for (row in (height * 0.25).toInt() until purchaseY - (height * 0.025).toInt() step step) {
                    var hits = 0
                    var samples = 0
                    for (column in (width * 0.21).toInt()..(width * 0.79).toInt() step step) {
                        samples++
                        if (white(at(column, row))) hits++
                    }
                    sampledRows++
                    if (hits >= samples * 0.65) couponRows++
                }
                if (sampledRows == 0 || couponRows < sampledRows * 0.20) continue
                val scale = source.width.toDouble() / width
                return CloseTarget(
                    Rect(((cx - radius) * scale).roundToInt(), ((cy - radius) * scale).roundToInt(),
                        ((cx + radius) * scale).roundToInt(), ((cy + radius) * scale).roundToInt()),
                    "充电券包广告图像：暗色遮罩、券卡、购买条及圆圈×", visual = true,
                )
            }
            return null
        } finally {
            if (image !== source) image.recycle()
        }
    }
}
