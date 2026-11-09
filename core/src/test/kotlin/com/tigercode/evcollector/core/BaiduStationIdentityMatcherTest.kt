package com.tigercode.evcollector.core

import com.tigercode.evcollector.core.parser.BaiduStationIdentityMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduStationIdentityMatcherTest {
    private val task = "畅行重卡超充站(106国道岗李店街北路东站)"
    private val actual = "畅行兆瓦液冷超级充电站(106国道岗李店街北路东站)"

    @Test
    fun acceptsChangedChargingDescriptorsForSameBrandAndFullSite() {
        assertTrue(BaiduStationIdentityMatcher.matchesDescriptorVariant(task, actual))
        assertTrue(BaiduStationIdentityMatcher.matchesDescriptorVariant(actual, task))
        assertTrue(BaiduStationIdentityMatcher.matchesDescriptorVariant(task, actual.replace('(', '（').replace(')', '）')))
        assertTrue(BaiduStationIdentityMatcher.matchesDescriptorVariant(
            "畅行重卡超充站(飞砂走石重卡站(无梁服务区))",
            "畅行兆瓦液冷超级充电站(飞砂走石重卡站(无梁服务区))",
        ))
    }

    @Test
    fun rejectsDifferentSiteDirectionRoadNumberStationNumberPowerAndPhase() {
        for (candidate in listOf(
            actual.replace("路东", "路西"), actual.replace("106", "107"),
            actual.replace("北路", "南路"), actual.replace("岗李店", "岗李庄"),
            actual.replace("东站", "东站2号"), actual.replace("东站", "东站一期"),
            actual.replace("东站", "东站400kW"),
        )) {
            assertFalse(candidate, BaiduStationIdentityMatcher.matchesDescriptorVariant(task, candidate))
        }
        assertFalse(BaiduStationIdentityMatcher.matchesDescriptorVariant(
            task.replace("东站", "东站240kW"), actual.replace("东站", "东站400kW"),
        ))
    }

    @Test
    fun rejectsDifferentBrandsAndPreservesNonDescriptorBrandWords() {
        assertFalse(BaiduStationIdentityMatcher.matchesDescriptorVariant(task, actual.replace("畅行", "畅通")))
        assertFalse(BaiduStationIdentityMatcher.matchesDescriptorVariant(task, actual.replace("畅行", "畅行新能源")))
        assertFalse(BaiduStationIdentityMatcher.matchesDescriptorVariant(
            task, actual.replace("兆瓦", "400kW"),
        ))
    }

    @Test
    fun rejectsGenericAndIncompleteIdentities() {
        for (candidate in listOf(actual.dropLast(1), actual + "2号", actual.replace("东站", "东…"))) {
            assertFalse(candidate, BaiduStationIdentityMatcher.matchesDescriptorVariant(task, candidate))
        }
        assertFalse(BaiduStationIdentityMatcher.matchesDescriptorVariant(
            "重卡超充站(106国道岗李店街北路东站)", "兆瓦液冷充电站(106国道岗李店街北路东站)",
        ))
        assertFalse(BaiduStationIdentityMatcher.matchesDescriptorVariant(
            "畅行重卡超充站(重卡充电站)", "畅行兆瓦液冷充电站(重卡充电站)",
        ))
        assertFalse(BaiduStationIdentityMatcher.matchesDescriptorVariant(
            task, "畅行兆瓦液冷超级充电站106国道岗李店街北路东站",
        ))
    }
    @Test
    fun acceptsSameBrandLocalityWithMovedChargingWordsAndVillageSuffix() {
        val task = "京能汽车充电站(续发重卡充电站小滹沱站)"
        val actual = "京能新能源充电站(续发重卡小滹沱村充电站)"
        assertTrue(BaiduStationIdentityMatcher.matchesVillageStationVariant(task, actual))
        assertTrue(BaiduStationIdentityMatcher.matchesVillageStationVariant(actual, task))
        assertTrue(BaiduStationIdentityMatcher.matchesVillageStationVariant(task,
            actual.replace('(', '（').replace(')', '）')))
        assertTrue(BaiduStationIdentityMatcher.matchesVillageStationVariant(task,
            actual.replace("小滹沱村", "小滹沱")))
    }

    @Test
    fun villageVariantPreservesBrandLocalityDirectionNumbersPowerAndPhase() {
        val task = "京能汽车充电站(续发重卡充电站小滹沱站)"
        val actual = "京能新能源充电站(续发重卡小滹沱村充电站)"
        for (candidate in listOf(
            actual.replace("京能", "京东"), actual.replace("小滹沱村", "庙口"),
            actual.replace("小滹沱村", "淇县107高村"), actual.replace("村充电站", "村东充电站"),
            actual.replace("村充电站", "村2号充电站"), actual.replace("村充电站", "村一期充电站"),
            actual.replace("村充电站", "村400kW充电站"), actual.replace("续发重卡", "续发"),
            actual.dropLast(1), actual + "2号", actual.replace("小滹沱村", "小滹…"),
        )) assertFalse(candidate, BaiduStationIdentityMatcher.matchesVillageStationVariant(task, candidate))
        assertFalse(BaiduStationIdentityMatcher.matchesVillageStationVariant(
            "京能充电站(小村东充电站)", "京能新能源充电站(小东村充电站)"))
        assertFalse(BaiduStationIdentityMatcher.matchesVillageStationVariant(
            "京能充电站(重卡充电站)", "京能新能源充电站(重卡村充电站)"))
        assertFalse(BaiduStationIdentityMatcher.matchesVillageStationVariant(
            "充电站(续发重卡小滹沱站)", "新能源充电站(续发重卡小滹沱村充电站)"))
    }

    @Test
    fun acceptsSameBrandAndCompleteSiteWithAffordablePileSuffix() {
        val task = "云快充汽车充电站(蓝鑫重卡充电站)"
        val actual = "云快充充电站(蓝鑫重卡平价桩)"
        assertTrue(BaiduStationIdentityMatcher.matchesAffordablePileVariant(task, actual))
        assertTrue(BaiduStationIdentityMatcher.matchesAffordablePileVariant(actual, task))
        assertTrue(BaiduStationIdentityMatcher.matchesAffordablePileVariant(task,
            actual.replace('(', '（').replace(')', '）')))
        assertTrue(BaiduStationIdentityMatcher.matchesAffordablePileVariant(
            task.replace("充电站)", "超充站)"), actual))
    }

    @Test
    fun affordablePileVariantPreservesFullBrandSiteAndQualifiers() {
        val task = "云快充汽车充电站(蓝鑫重卡充电站)"
        val actual = "云快充充电站(蓝鑫重卡平价桩)"
        for (candidate in listOf(
            actual.replace("云快充", "星星"), actual.replace("蓝鑫", "蓝新"),
            actual.replace("蓝鑫重卡", "蓝鑫"), actual.replace("蓝鑫重卡", "中澳迎宾高速口重卡"),
            actual.replace("平价桩", "东平价桩"), actual.replace("平价桩", "2号平价桩"),
            actual.replace("平价桩", "400kW平价桩"), actual.replace("平价桩", "一期平价桩"),
            actual.dropLast(1), actual + "2号", actual.replace("蓝鑫重卡", "蓝鑫重…"),
        )) assertFalse(candidate, BaiduStationIdentityMatcher.matchesAffordablePileVariant(task, candidate))
        assertFalse(BaiduStationIdentityMatcher.matchesAffordablePileVariant(
            "云快充充电站(重卡充电站)", "云快充充电站(重卡平价桩)"))
        assertFalse(BaiduStationIdentityMatcher.matchesAffordablePileVariant(
            "云快充充电站(蓝鑫平价重卡充电站)", "云快充充电站(蓝鑫重卡平价桩)"))
        assertFalse(BaiduStationIdentityMatcher.matchesAffordablePileVariant(
            "云快充充电站(蓝鑫重卡路东充电站)", "云快充充电站(蓝鑫重卡路西平价桩)"))
    }

}
