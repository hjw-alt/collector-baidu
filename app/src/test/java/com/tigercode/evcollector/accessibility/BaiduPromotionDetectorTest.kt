package com.tigercode.evcollector.accessibility

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class BaiduPromotionDetectorTest {
    private val closeId = "com.baidu.BaiduMap:id/cancel_update"
    private val updateId = "com.baidu.BaiduMap:id/direct_update"
    private val closeBounds = Rect(495, 1780, 584, 1869)

    private val imageCardClose = Rect(497, 1704, 583, 1790)
    private val imageCardCta = Rect(197, 1425, 882, 1540)

    private fun fixture(file: String = "baidu-native-update-dialog.xml"): NodeSnapshot {
        val input = checkNotNull(javaClass.getResourceAsStream("/$file"))
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

    @Test fun capturedNativeUpdateTargetsCancelInsteadOfUpdate() {
        val page = fixture()
        val target = BaiduPromotionDetector.fromNodes(page, 1080, 2400)
        assertNotNull("The visible update popup must have a close target", target)
        assertEquals(closeBounds, target!!.bounds)
        assertFalse(target.visual)
        assertNotEquals(Rect(228, 1487, 852, 1592), target.bounds)
    }

    @Test fun cancelIdAloneCannotQualifyUnrelatedPage() {
        val page = NodeSnapshot(bounds = Rect(0, 0, 1080, 2400), children = listOf(
            NodeSnapshot(viewId = closeId, clickable = true, bounds = closeBounds)))
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2400))
    }

    @Test fun otherPackageIdsDoNotQualify() {
        val page = map(fixture()) { it.copy(viewId = it.viewId.replace("com.baidu.BaiduMap", "com.example.other")) }
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2400))
    }

    @Test fun missingUpdateActionDoesNotQualify() {
        val page = map(fixture()) { if (it.viewId == updateId) it.copy(viewId = "") else it }
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2400))
    }

    @Test fun unsafeCloseControlsAreRejected() {
        val changes: List<(NodeSnapshot) -> NodeSnapshot> = listOf(
            { it.copy(enabled = false) }, { it.copy(clickable = false) }, { it.copy(bounds = Rect()) },
            { it.copy(bounds = Rect(1200, 1780, 1289, 1869)) },
            { it.copy(bounds = Rect(228, 1487, 852, 1592)) },
        )
        for (change in changes) {
            val page = map(fixture()) { if (it.viewId == closeId) change(it) else it }
            assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2400))
        }
    }

    @Test fun scaledAndShiftedDialogUsesObservedBounds() {
        val page = map(fixture()) { n ->
            val b = n.bounds
            n.copy(bounds = Rect(b.left / 2 + 200, b.top / 2, b.right / 2 + 200, b.bottom / 2))
        }
        assertEquals(Rect(447, 890, 492, 934), BaiduPromotionDetector.fromNodes(page, 1080, 2400)?.bounds)
    }

    @Test fun missingCloseNeverFallsBackToInstallButton() {
        val page = map(fixture()) { if (it.viewId == closeId) it.copy(viewId = "") else it }
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2400))
    }
    @Test fun blockingUpdateIsStillVisibleWhenCloseIsMissing() {
        val page = map(fixture()) { if (it.viewId == closeId) it.copy(viewId = "") else it }
        assertTrue(BaiduPromotionDetector.isUpdateDialogVisible(page))
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2400))
    }

    @Test fun multipleCloseCandidatesAreAmbiguous() {
        val page = map(fixture()) { n ->
            if (n.viewId == "com.baidu.BaiduMap:id/customPanel") n.copy(children = n.children +
                NodeSnapshot(viewId = closeId, clickable = true, bounds = closeBounds)) else n
        }
        assertNull(BaiduPromotionDetector.fromNodes(page, 1080, 2400))
    }

    @Test fun capturedImageCardTargetsFooterCloseInsteadOfPromotion() {
        val page = fixture("baidu-image-card-promotion.xml")
        val observation = BaiduPromotionDetector.observeNodes(page, 1080, 2400)
        val target = checkNotNull(observation.target)
        assertTrue(observation.blockingImageCard)
        assertEquals(BaiduPromotionDetector.CloseAction.CLOSE_IMAGE_CARD, target.action)
        assertEquals(imageCardClose, target.bounds)
        assertNotEquals(imageCardCta, target.bounds)
        assertFalse(target.visual)
    }

    @Test fun imageCardCloseUsesLiveBoundsAtAnotherResolution() {
        fun scale(b: Rect) = Rect(b.left * 4 / 3, b.top * 4 / 3, b.right * 4 / 3, b.bottom * 4 / 3)
        val page = map(fixture("baidu-image-card-promotion.xml")) { it.copy(bounds = scale(it.bounds)) }
        assertEquals(scale(imageCardClose), BaiduPromotionDetector.fromNodes(page, 1440, 3200)?.bounds)
    }

    @Test fun imageCardWithoutSafeFooterNeverTargetsPromotion() {
        val page = fixture("baidu-image-card-promotion.xml")
        for (change in listOf<(NodeSnapshot) -> NodeSnapshot>(
            { it.copy(enabled = false) }, { it.copy(clickable = false) },
            { it.copy(bounds = Rect(397, 1704, 483, 1790)) }, { it.copy(contentDescription = "Log in") },
        )) {
            val changed = map(page) { if (it.bounds == imageCardClose) change(it) else it }
            val observation = BaiduPromotionDetector.observeNodes(changed, 1080, 2400)
            assertNull(observation.target)
            assertTrue(observation.blockingImageCard)
        }
        val duplicate = map(page) { n ->
            if (n.children.singleOrNull()?.bounds == imageCardClose) n.copy(children = n.children + n.children.single()) else n
        }
        val observation = BaiduPromotionDetector.observeNodes(duplicate, 1080, 2400)
        assertNull(observation.target)
        assertTrue(observation.blockingImageCard)
    }

    @Test fun imageCardContainerRemainsOnHomeButNoLongerBlocks() {
        val page = map(fixture("baidu-image-card-promotion.xml")) {
            if (it.viewId == "com.baidu.BaiduMap:id/commonPopupContainer") it.copy(children = emptyList()) else it
        }
        val observation = BaiduPromotionDetector.observeNodes(page, 1080, 2400)
        assertNull(observation.target)
        assertFalse(observation.blockingImageCard)
    }

    @Test fun imageCardMustHaveTheBaiduOverlayAndPromotionAction() {
        val page = fixture("baidu-image-card-promotion.xml")
        val wrongHost = map(page) { it.copy(viewId = it.viewId.replace("com.baidu.BaiduMap", "com.example.other")) }
        val noAction = map(page) { if (it.bounds == imageCardCta) it.copy(clickable = false) else it }
        assertNull(BaiduPromotionDetector.fromNodes(wrongHost, 1080, 2400))
        assertNull(BaiduPromotionDetector.fromNodes(noAction, 1080, 2400))
    }
}
