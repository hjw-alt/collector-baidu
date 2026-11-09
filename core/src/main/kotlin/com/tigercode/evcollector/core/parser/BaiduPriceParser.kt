package com.tigercode.evcollector.core.parser

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.PricePeriod
import com.tigercode.evcollector.core.model.Rect

object BaiduPriceParser {
    private val priceWithUnit = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]+)?)\s*/\s*(?:度|kWh|千瓦时)""", RegexOption.IGNORE_CASE)
    private val amountOnly = Regex("""^(?:参考价\s*)?[¥￥]\s*([0-9]+(?:\.[0-9]+)?)\s*$""")
    private val unitOnly = Regex("""^/\s*(?:度|kWh|千瓦时)$""", RegexOption.IGNORE_CASE)
    private val totalPrice = Regex("""^(?:参考价\s*)?[¥￥]\s*([0-9]+(?:\.[0-9]+)?)\s*(?:/\s*(?:度|kWh|千瓦时))?$""", RegexOption.IGNORE_CASE)
    private val electricityFee = Regex("""(?:充电费|电费)\s*[：:]\s*[¥￥]?\s*([0-9]+(?:\.[0-9]+)?)""")
    private val serviceFee = Regex("""服务费\s*[：:]\s*[¥￥]?\s*([0-9]+(?:\.[0-9]+)?)""")
    private val timePeriod = Regex("""([0-9]{2}:[0-9]{2})\s*[-–~至]\s*([0-9]{2}:[0-9]{2})""")

    private fun text(node: NodeSnapshot): String = node.text.ifBlank { node.contentDescription }.trim()

    // 不按文字去重：不同时段可以具有相同的总价、电费和服务费。
    private fun texts(snapshot: NodeSnapshot): List<String> = buildList {
        fun walk(node: NodeSnapshot) {
            text(node).takeIf { it.isNotBlank() }?.let { add(it) }
            node.children.forEach(::walk)
        }
        walk(snapshot)
    }

    /** 只返回价格弹层自己的子树；背景的“查看价格详情”仍可能出现在无障碍树里。 */
    fun findSheet(snapshot: NodeSnapshot): NodeSnapshot? {
        fun titlePath(node: NodeSnapshot): List<NodeSnapshot>? {
            if (text(node) == "价格详情" && node.bounds.isValid &&
                node.bounds.bottom > snapshot.bounds.top && node.bounds.top < snapshot.bounds.bottom) {
                return listOf(node)
            }
            for (child in node.children) {
                val path = titlePath(child) ?: continue
                return listOf(node) + path
            }
            return null
        }
        val path = titlePath(snapshot) ?: return null
        val candidates = path.asReversed().map { it to texts(it) }
        fun containsBackground(values: List<String>): Boolean =
            values.any { it.contains("查看价格详情") || it == "地点详情" }
        candidates.firstOrNull { (_, values) ->
            !containsBackground(values) && values.any {
                timePeriod.containsMatchIn(it) || electricityFee.containsMatchIn(it) ||
                    serviceFee.containsMatchIn(it) || it.contains("暂无价格") || it.contains("暂无电价")
            }
        }?.let { return it.first }
        candidates.firstOrNull { (node, values) ->
            node.className.endsWith("WebView") && !containsBackground(values)
        }?.let { return it.first }
        // 即使内容未加载，只要标题出现，也说明弹窗已打开，不能再点击背景入口。
        return path.last()
    }

    fun hasNoPrice(sheet: NodeSnapshot): Boolean = texts(sheet).any {
        it.contains("暂无价格") || it.contains("暂无电价")
    }

    /** 详情页只有当前价时保留当前价，不为它补造时间段。 */
    fun currentPrice(snapshot: NodeSnapshot): String {
        val nodes = mutableListOf<Pair<String, Rect>>()
        fun walk(node: NodeSnapshot) {
            val value = text(node)
            if (value.isNotBlank() && node.bounds.isValid) nodes.add(value to node.bounds)
            node.children.forEach(::walk)
        }
        walk(snapshot)
        val ordered = nodes.sortedWith(compareBy({ it.second.top }, { it.second.left }))
        fun isComponent(value: String): Boolean =
            value.contains("电费") || value.contains("服务费") || value.contains("停车费") || value.contains("占位费")
        ordered.firstNotNullOfOrNull { (value, _) ->
            if (isComponent(value)) null else priceWithUnit.find(value)?.groupValues?.get(1)
        }?.let { return it }
        // WebView 有时把“¥0.68”和“/度”拆成相邻节点，仅合并同一行紧邻的单位。
        for ((value, bounds) in ordered) {
            val amount = amountOnly.matchEntire(value)?.groupValues?.get(1) ?: continue
            val hasUnit = ordered.any { (other, otherBounds) ->
                unitOnly.matches(other) && otherBounds.left >= bounds.left &&
                    otherBounds.left <= bounds.right + bounds.height * 2 &&
                    minOf(bounds.bottom, otherBounds.bottom) - maxOf(bounds.top, otherBounds.top) >=
                    minOf(bounds.height, otherBounds.height) / 2
            }
            val componentLabel = ordered.any { (other, otherBounds) ->
                isComponent(other) && otherBounds.right <= bounds.left &&
                    bounds.left - otherBounds.right <= bounds.height * 2 &&
                    minOf(bounds.bottom, otherBounds.bottom) - maxOf(bounds.top, otherBounds.top) >=
                    minOf(bounds.height, otherBounds.height) / 2
            }
            if (hasUnit && !componentLabel) return amount
        }
        return ""
    }

    fun periods(sheet: NodeSnapshot): List<PricePeriod> {
        val values = texts(sheet)
        val start = values.indexOfFirst { it == "价格详情" }
        if (start < 0) return emptyList()
        data class Row(var total: String, var elec: String = "", var service: String = "", var time: String = "", var tag: String = "")
        val rows = mutableListOf<Row>()
        for (value in values.drop(start + 1)) {
            totalPrice.matchEntire(value)?.let { match ->
                rows.add(Row(total = match.groupValues[1]))
            }
            val row = rows.lastOrNull() ?: continue
            electricityFee.find(value)?.let { row.elec = it.groupValues[1] }
            serviceFee.find(value)?.let { row.service = it.groupValues[1] }
            timePeriod.find(value)?.let { row.time = "${it.groupValues[1]}-${it.groupValues[2]}" }
            if (value.contains("当前时段")) row.tag = "当前时段"
        }
        return rows.filter { it.time.isNotBlank() }.map {
            PricePeriod(time = it.time, totalPrice = it.total, elecFee = it.elec, serviceFee = it.service, tag = it.tag)
        }
    }
}
