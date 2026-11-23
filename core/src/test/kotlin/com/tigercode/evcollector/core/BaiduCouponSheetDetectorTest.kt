package com.tigercode.evcollector.core

import com.tigercode.evcollector.core.model.NodeSnapshot
import com.tigercode.evcollector.core.model.Rect
import com.tigercode.evcollector.core.parser.BaiduCouponSheetDetector
import com.tigercode.evcollector.core.parser.XmlSnapshotParser
import org.junit.Assert.*
import org.junit.Test

class BaiduCouponSheetDetectorTest {
    private fun fixture(): NodeSnapshot = XmlSnapshotParser.parse(
        javaClass.getResource("/baidu-coupon-package-sheet.xml")!!.readText()
    )!!

    private fun map(node: NodeSnapshot, update: (NodeSnapshot) -> NodeSnapshot): NodeSnapshot =
        update(node.copy(children = node.children.map { map(it, update) }))

    @Test fun recordedPurchaseSheetLocatesUnlabelledNonClickableClose() {
        val page = fixture()
        assertTrue(BaiduCouponSheetDetector.isVisible(page))
        assertEquals(Rect(966, 1729, 1068, 1832), BaiduCouponSheetDetector.closeBounds(page, 1080, 2400))
    }

    @Test fun scaledDeviceUsesObservedBounds() {
        val page = map(fixture()) { n ->
            val b = n.bounds
            n.copy(bounds = Rect(b.left * 2, b.top * 2, b.right * 2, b.bottom * 2))
        }
        assertEquals(Rect(1932, 3458, 2136, 3664), BaiduCouponSheetDetector.closeBounds(page, 2160, 4800))
    }

    @Test fun ordinaryBannerAndPriceSheetAreNotCouponPurchaseSheet() {
        val page = NodeSnapshot(bounds = Rect(0, 0, 1080, 2400), children = listOf(
            NodeSnapshot(text = "超值券 充电券包2.9元抵9元", bounds = Rect(30, 1000, 1000, 1100)),
            NodeSnapshot(text = "价格详情", bounds = Rect(55, 614, 244, 664)),
            NodeSnapshot(text = "×", bounds = Rect(966, 610, 1068, 710))
        ))
        assertFalse(BaiduCouponSheetDetector.isVisible(page))
        assertNull(BaiduCouponSheetDetector.closeBounds(page, 1080, 2400))
    }

    @Test fun missingCloseDoesNotFallBackToPurchaseButtonOrBackground() {
        val page = map(fixture()) { n ->
            if (n.bounds == Rect(966, 1729, 1068, 1832)) n.copy(bounds = Rect()) else n
        }
        assertTrue(BaiduCouponSheetDetector.isVisible(page))
        assertNull(BaiduCouponSheetDetector.closeBounds(page, 1080, 2400))
    }

    @Test fun purchaseHistoryAndPurchaseActionAreRequired() {
        for (text in listOf("购买记录", "购买")) {
            val page = map(fixture()) { n -> if (n.text == text) n.copy(text = "") else n }
            assertNull(BaiduCouponSheetDetector.closeBounds(page, 1080, 2400))
        }
    }

    @Test fun hiddenHeaderDoesNotCountAsVisibleSheet() {
        val page = map(fixture()) { n ->
            if (n.text == "充电优惠券套餐") n.copy(bounds = Rect()) else n
        }
        assertFalse(BaiduCouponSheetDetector.isVisible(page))
        assertNull(BaiduCouponSheetDetector.closeBounds(page, 1080, 2400))
    }
}
