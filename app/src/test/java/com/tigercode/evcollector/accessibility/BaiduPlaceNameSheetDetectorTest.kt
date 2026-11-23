package com.tigercode.evcollector.accessibility

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class BaiduPlaceNameSheetDetectorTest {
    private fun fixture(name: String = "/baidu-place-name-sheet.xml"): NodeSnapshot {
        val input = checkNotNull(javaClass.getResourceAsStream(name))
        val root = input.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement }
        fun read(element: Element): NodeSnapshot {
            val coordinates = Regex("-?\\d+").findAll(element.getAttribute("bounds")).map { it.value.toInt() }.toList()
            val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            return NodeSnapshot(
                text = element.getAttribute("text"), contentDescription = element.getAttribute("content-desc"),
                viewId = element.getAttribute("resource-id"), className = element.getAttribute("class"),
                bounds = if (coordinates.size == 4) Rect(coordinates[0], coordinates[1], coordinates[2], coordinates[3]) else Rect(),
                clickable = element.getAttribute("clickable") == "true", enabled = element.getAttribute("enabled") != "false",
                children = children.map(::read),
            )
        }
        return read(root).children.single()
    }

    private fun map(node: NodeSnapshot, transform: (NodeSnapshot) -> NodeSnapshot): NodeSnapshot =
        transform(node.copy(children = node.children.map { map(it, transform) }))

    @Test fun capturedMenuWithZeroBoundsCancelMustBeHandled() {
        val target = BaiduPromotionDetector.fromNodes(fixture(), 1080, 2280)
        assertNotNull("The captured place-name menu must expose a safe cancel action", target)
        assertFalse(target!!.visual)
        assertEquals(Rect(), target.bounds)
        assertEquals(BaiduPromotionDetector.CloseAction.CANCEL_PLACE_NAME, target.action)
        assertTrue(BaiduPlaceNameSheetDetector.isVisible(fixture(), 1080, 2068))
    }

    @Test fun validCancelBoundsStillUseSemanticCancel() {
        val page = map(fixture()) { if (it.text == "取消") it.copy(bounds = Rect(0, 2140, 1080, 2280)) else it }
        val target = BaiduPromotionDetector.fromNodes(page, 1080, 2280)
        assertEquals(Rect(0, 2140, 1080, 2280), target?.bounds)
        assertEquals(BaiduPromotionDetector.CloseAction.CANCEL_PLACE_NAME, target?.action)
    }

    @Test fun ordinaryCancelAndCopyLabelsDoNotQualifyWithoutDetailContext() {
        val page = map(fixture()) { if (it.text == "地点详情") it.copy(text = "搜索结果") else it }
        assertFalse(BaiduPlaceNameSheetDetector.isVisible(page, 1080, 2280))
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2280))
    }

    @Test fun copyAndCancelInDifferentParentsDoNotBecomeACloseTarget() {
        val page = map(fixture()) { n ->
            if (n.children.size == 3 && n.children[1].text == "复制地点名称")
                n.copy(children = listOf(n.children[0], n.children[1].copy(text = "其他操作"))) else n
        }.let { it.copy(children = it.children + NodeSnapshot(text = "复制地点名称") + NodeSnapshot(text = "取消", clickable = true)) }
        assertFalse(BaiduPlaceNameSheetDetector.isVisible(page, 1080, 2280))
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2280))
    }

    @Test fun missingCancelRemainsBlockingWithoutClickingCopyOrTitle() {
        val page = map(fixture()) { n -> n.copy(children = n.children.filterNot { it.text == "取消" }) }
        assertTrue(BaiduPlaceNameSheetDetector.isVisible(page, 1080, 2280))
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2280))
    }

    @Test fun disabledOrNonClickableCancelRemainsBlocking() {
        for (cancel in listOf(NodeSnapshot(text = "取消", enabled = false, clickable = true, className = "android.view.View"),
            NodeSnapshot(text = "取消", clickable = false, className = "android.view.View"))) {
            val page = map(fixture()) { if (it.text == "取消") cancel else it }
            assertTrue(BaiduPlaceNameSheetDetector.isVisible(page, 1080, 2280))
            assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2280))
        }
    }

    @Test fun multipleCancelsOrMultipleMenusAreAmbiguous() {
        val duplicateCancel = map(fixture()) { n ->
            if (n.children.size == 3 && n.children[1].text == "复制地点名称")
                n.copy(children = n.children + n.children.last()) else n
        }
        val page = fixture()
        val duplicateMenu = page.copy(children = page.children + page.children.last())
        for (candidate in listOf(duplicateCancel, duplicateMenu)) {
            assertTrue(BaiduPlaceNameSheetDetector.isVisible(candidate, 1080, 2280))
            assertNull(BaiduPromotionDetector.fromNodes(candidate, 1080, 2280))
        }
    }

    @Test fun invalidNonEmptyCancelRectangleCannotBecomeGestureOrSemanticTarget() {
        val page = map(fixture()) { if (it.text == "取消") it.copy(bounds = Rect(1200, 2140, 1600, 2280)) else it }
        assertTrue(BaiduPlaceNameSheetDetector.isVisible(page, 1080, 2280))
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2280))
    }

    @Test fun backgroundCancelDoesNotOverrideExactMenuCancel() {
        val page = fixture().let { it.copy(children = it.children + NodeSnapshot(text = "取消", clickable = true, bounds = Rect(20, 300, 200, 400))) }
        assertEquals(BaiduPromotionDetector.CloseAction.CANCEL_PLACE_NAME, BaiduPromotionDetector.fromNodes(page, 1080, 2280)?.action)
    }

    @Test fun dismissedSheetDoesNotTriggerAgain() {
        val page = map(fixture()) { n -> n.copy(children = n.children.filterNot { it.children.any { c -> c.text == "复制地点名称" } }) }
        assertFalse(BaiduPlaceNameSheetDetector.isVisible(page, 1080, 2280))
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2280))
    }

    @Test fun appSnapshotUsesFullWindowAndAllowsWebViewRounding() {
        // This MIUI reports display metrics 1080x2068 for a 1080x2280 window,
        // and WebView children extend three pixels beyond its right/bottom edges.
        val page = fixture("/baidu-place-name-sheet-app.xml")
        val target = BaiduPromotionDetector.fromNodes(page, 1080, 2068)
        assertNotNull("The collector's own full-window snapshot must be supported", target)
        assertEquals(Rect(0, 2142, 1083, 2283), target?.bounds)
        assertEquals(BaiduPromotionDetector.CloseAction.CANCEL_PLACE_NAME, target?.action)
    }

    @Test fun majorOutOfWindowBoundsAreNotARoundingDifference() {
        val page = map(fixture("/baidu-place-name-sheet-app.xml")) {
            if (it.children.size == 3 && it.children[1].text == "复制地点名称")
                it.copy(bounds = Rect(0, 1872, 1300, 2500)) else it
        }
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2068))
    }
}
