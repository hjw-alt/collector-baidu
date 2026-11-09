package com.tigercode.evcollector.core.parser

import com.tigercode.evcollector.core.model.ChargingPileDetail
import com.tigercode.evcollector.core.model.ChargingPileDialogSnapshot
import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import com.tigercode.evcollector.core.model.StationDetail

/** Baidu exposes repeated status/power labels as flat WebView siblings. Keep their positions. */
object BaiduChargingPileParser {
    data class Card(
        val type: String,
        val available: String,
        val total: String,
        val power: String,
        val bounds: Rect,
    )

    private val typePattern = Regex("^(超充|快充|慢充)")
    // 已满 0/N 表示空闲为 0，忙碌数按总数减空闲数统计。
    private val countPattern = Regex("(?:空闲|已满|空)\\s*(\\d+)\\s*/\\s*(\\d+)")
    // 部分卡片只展示“共 20 个”，没有空闲数；空闲和忙碌保持未知。
    private val totalOnlyPattern = Regex("共\\s*(\\d+)\\s*(?:个|台|桩|枪)")
    private val powerPattern = Regex("([0-9]+(?:\\.[0-9]+)?(?:\\s*[-~至]\\s*[0-9]+(?:\\.[0-9]+)?)?)\\s*[kK][wW]")
    // 百度会展示“MA59G0...390101”这类缩略编号；保留后缀以免同前缀的桩被错误合并。
    // 记录页面实际显示值，不将省略号去掉后冒充完整设备编号。
    private val idPattern = Regex("""(?:设备)?编号\s*[:：]\s*([A-Za-z0-9_-]+(?:[.．…⋯]+[A-Za-z0-9_-]+)*)""")
    private val allPattern = Regex("^全部(?:\\s|空|共|[（(0-9]|$)")
    private val statuses = setOf("空闲", "使用中", "充电中", "占用", "已占用", "占用中", "故障", "离线", "未更新", "未知", "不可用", "维护中")

    private data class TextNode(val text: String, val bounds: Rect)

    private fun texts(root: NodeSnapshot): List<TextNode> = buildList {
        fun walk(node: NodeSnapshot) {
            val text = node.text.ifBlank { node.contentDescription }.trim()
            if (text.isNotEmpty()) add(TextNode(text, node.bounds))
            node.children.forEach(::walk)
        }
        walk(root)
    }

    fun isPilePage(root: NodeSnapshot): Boolean {
        val values = texts(root).map { it.text }
        return values.any { it == "充电桩详情" } ||
            (values.any { allPattern.containsMatchIn(it) } &&
                values.any { idPattern.containsMatchIn(it) || it.contains("额定功率") || countPattern.containsMatchIn(it) })
    }

    /** 明确的空详情页没有“全部”标签，须与仍在加载的页面区分。 */
    fun isEmptyPilePage(root: NodeSnapshot): Boolean {
        val values = texts(root).map { it.text.replace(Regex("\\s+"), "") }
        return values.any { it == "充电桩详情" } &&
            values.any { it == "暂无详情" || it.contains("更多详情接入中") } &&
            values.none { allPattern.containsMatchIn(it) || idPattern.containsMatchIn(it) }
    }

    fun findAllTab(root: NodeSnapshot): Rect? = texts(root)
        .filter { it.bounds.isValid && allPattern.containsMatchIn(it.text) }
        .minByOrNull { it.bounds.width.toLong() * it.bounds.height }
        ?.bounds

    /** Match each column separately, so two adjacent cards cannot exchange counts or power. */
    fun cards(root: NodeSnapshot): List<Card> {
        if (isPilePage(root)) return emptyList()
        val values = texts(root).filter { it.bounds.isValid }
        val anchors = values.filter { typePattern.containsMatchIn(it.text) }
            .distinctBy { typePattern.find(it.text)!!.value to it.bounds.top }
        val width = root.bounds.width.coerceAtLeast(1)
        val height = root.bounds.height.coerceAtLeast(1)
        return anchors.mapNotNull { anchor ->
            val type = typePattern.find(anchor.text)!!.value
            val band = maxOf(anchor.bounds.height, height / 40)
            val nextColumn = anchors.filter {
                it.bounds.left > anchor.bounds.left &&
                    kotlin.math.abs(it.bounds.top - anchor.bounds.top) <= band
            }.minOfOrNull { it.bounds.left }
            val nextRow = anchors.filter { it.bounds.top > anchor.bounds.top + band }
                .minOfOrNull { it.bounds.top } ?: Int.MAX_VALUE
            val left = anchor.bounds.left - width / 25
            val right = nextColumn?.minus(width / 25) ?: root.bounds.right
            val bottom = minOf(nextRow, anchor.bounds.bottom + height / 6)
            val column = values.filter {
                it.bounds.centerX in left until right &&
                    it.bounds.top >= anchor.bounds.top - band / 2 && it.bounds.top < bottom
            }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            val joined = column.joinToString(" ") { it.text }
            val count = countPattern.find(joined)
            val total = count?.groupValues?.get(2)
                ?: totalOnlyPattern.find(joined)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val power = powerPattern.find(joined)?.groupValues?.get(1)
                ?.replace(" ", "")?.let { "${it}kW" }.orEmpty()
            val tapBounds = column.firstOrNull {
                countPattern.containsMatchIn(it.text) || totalOnlyPattern.containsMatchIn(it.text)
            }?.bounds ?: anchor.bounds
            Card(type, count?.groupValues?.get(1).orEmpty(), total, power, tapBounds)
        }.distinctBy { it.type to it.bounds }
    }

    fun tabSummaries(root: NodeSnapshot): List<Card> = texts(root).mapNotNull { node ->
        val compact = node.text.replace(Regex("\\s+"), "")
        val type = typePattern.find(compact)?.value ?: return@mapNotNull null
        val count = countPattern.find(compact)
        val total = count?.groupValues?.get(2)
            ?: totalOnlyPattern.find(compact)?.groupValues?.get(1)
            ?: return@mapNotNull null
        Card(type, count?.groupValues?.get(1).orEmpty(), total, "", node.bounds)
    }.distinctBy { it.type }

    fun applySummaries(detail: StationDetail, cards: List<Card>): StationDetail {
        val byType = cards.associateBy { it.type }
        val fast = byType["快充"]
        val superCharge = byType["超充"]
        val slow = byType["慢充"]
        return detail.copy(
            fastAvailable = fast?.available ?: detail.fastAvailable,
            fastTotal = fast?.total ?: detail.fastTotal,
            fastPower = fast?.power?.ifBlank { detail.fastPower } ?: detail.fastPower,
            superAvailable = superCharge?.available ?: detail.superAvailable,
            superTotal = superCharge?.total ?: detail.superTotal,
            superPower = superCharge?.power?.ifBlank { detail.superPower } ?: detail.superPower,
            slowAvailable = slow?.available ?: detail.slowAvailable,
            slowTotal = slow?.total ?: detail.slowTotal,
            slowPower = slow?.power?.ifBlank { detail.slowPower } ?: detail.slowPower,
        )
    }

    fun parse(root: NodeSnapshot, summary: StationDetail): ChargingPileDialogSnapshot {
        val all = texts(root)
        val allLabel = all.firstOrNull { allPattern.containsMatchIn(it.text) }?.text.orEmpty()
        val total = Regex("共\\s*(\\d+)").find(allLabel)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("全部\\s*[（(]\\s*(\\d+)").find(allLabel)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        val values = all.filter { it.bounds.isValid }
            .distinctBy { it.text to it.bounds }
        val devices = values.filter { idPattern.containsMatchIn(it.text) }
            .sortedBy { it.bounds.top }
        val powersByType = listOf("超充" to summary.superPower, "快充" to summary.fastPower, "慢充" to summary.slowPower)
        val soleType = listOf("超充" to summary.superTotal, "快充" to summary.fastTotal, "慢充" to summary.slowTotal)
            .filter { (it.second.toIntOrNull() ?: 0) > 0 }.singleOrNull()?.first
        val piles = devices.mapIndexedNotNull { index, device ->
            val id = idPattern.find(device.text)!!.groupValues[1]
            val padding = maxOf(8, device.bounds.height / 3)
            val top = device.bounds.top - padding
            val bottom = devices.getOrNull(index + 1)?.bounds?.top?.minus(padding)
                ?: (device.bounds.bottom + maxOf(device.bounds.height * 2, root.bounds.height / 20))
            val row = values.filter { it.bounds.centerY in top until bottom }
            val status = row.firstOrNull { it.text in statuses }?.text.orEmpty()
            val power = row.firstNotNullOfOrNull { powerPattern.find(it.text)?.groupValues?.get(1) }
                ?.replace(" ", "").orEmpty()
            // A clipped last row is completed by the next overlapping screen, never by its neighbour.
            if (status.isBlank() || power.isBlank()) return@mapIndexedNotNull null
            val explicitType = row.firstOrNull { it.text in setOf("超充", "快充", "慢充", "超", "快", "慢") }?.text
            val matchingTypes = powersByType.filter {
                powerPattern.find(it.second)?.groupValues?.get(1)?.replace(" ", "") == power
            }.map { it.first }.distinct()
            val type = when (explicitType) {
                "超", "超充" -> "超充"
                "快", "快充" -> "快充"
                "慢", "慢充" -> "慢充"
                else -> matchingTypes.singleOrNull() ?: soleType.orEmpty()
            }
            ChargingPileDetail(deviceId = id, chargingType = type, status = status, ratedPower = "${power}kW")
        }.distinctBy(::identity)
        return ChargingPileDialogSnapshot(expectedTotal = total, piles = piles)
    }

    // 缩略编号不是完整设备标识：同站的 120kW 和 7kW 桩可能都显示 MA42E7...000101。
    // 用额定功率区分这类冲突，保留原始显示编号；状态变化和滚动位置不参与去重。
    private fun identity(pile: ChargingPileDetail): Pair<String, String> {
        val id = pile.deviceId.trim()
        val abbreviated = id.any { it in ".．…⋯" }
        val power = if (abbreviated) pile.ratedPower.filterNot { it.isWhitespace() }.lowercase() else ""
        return id to power
    }

    fun merge(
        existing: List<ChargingPileDetail>,
        incoming: List<ChargingPileDetail>,
    ): List<ChargingPileDetail> = (existing + incoming)
        .filter { it.deviceId.isNotBlank() }
        .groupBy(::identity)
        .values
        .map { samePile -> ChargingPileDialogParser.merge(emptyList(), samePile).single() }

    fun visibleSignature(root: NodeSnapshot): String = texts(root)
        .filter { it.bounds.isValid && idPattern.containsMatchIn(it.text) }
        .joinToString("|") { "${it.text}@${it.bounds.top}:${it.bounds.bottom}" }
}
