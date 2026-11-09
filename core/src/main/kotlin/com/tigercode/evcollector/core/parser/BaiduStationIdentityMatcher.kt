package com.tigercode.evcollector.core.parser

/** Compare changed charging descriptions while preserving the complete brand and site identity. */
object BaiduStationIdentityMatcher {
    private data class Identity(val head: String, val brand: String, val site: String)

    private val stationSuffixes = listOf("充电站", "充电桩", "超充站", "快充站")
    private val trailingDescriptors = listOf("重卡", "兆瓦", "液冷")
    private val genericSites = setOf(
        "充电站", "汽车充电站", "超级充电站", "新能源充电站", "重卡充电站",
        "重卡超充站", "超充站", "快充站", "充电桩", "充电",
    ).map(StationNameNormalizer::normalize).toSet()

    fun matchesDescriptorVariant(taskName: String, actualName: String): Boolean {
        val task = identity(taskName) ?: return false
        val actual = identity(actualName) ?: return false
        return task.head != actual.head && task.brand == actual.brand && task.site == actual.site
    }

    /** Same brand and full locality; allow charging words to move and a terminal village label. */
    fun matchesVillageStationVariant(taskName: String, actualName: String): Boolean {
        val task = identity(taskName, allowNewEnergyDescriptor = true) ?: return false
        val actual = identity(actualName, allowNewEnergyDescriptor = true) ?: return false
        if (task.brand != actual.brand || task.site == actual.site) return false

        fun locality(site: String): String {
            // Keep 重卡, road directions, site numbers, powers and phases significant.
            var value = site
            stationSuffixes.forEach { value = value.replace(it, "") }
            return value.removeSuffix("站")
        }
        val taskLocality = locality(task.site)
        val actualLocality = locality(actual.site)
        if (taskLocality.length < 4 || actualLocality.length < 4) return false
        if (taskLocality == actualLocality) return true
        // Only a trailing 村 may differ. Never remove 村 from the middle of a place name.
        if (taskLocality.endsWith("村") == actualLocality.endsWith("村")) return false
        val taskStem = taskLocality.removeSuffix("村")
        val actualStem = actualLocality.removeSuffix("村")
        return taskStem.length >= 4 && taskStem == actualStem
    }

    /** Same brand and complete site identity; only a terminal 平价桩 may replace a charging suffix. */
    fun matchesAffordablePileVariant(taskName: String, actualName: String): Boolean {
        val task = identity(taskName) ?: return false
        val actual = identity(actualName) ?: return false
        if (task.brand != actual.brand) return false
        val taskAffordable = task.site.endsWith("平价桩")
        val actualAffordable = actual.site.endsWith("平价桩")
        if (taskAffordable == actualAffordable) return false

        fun siteStem(site: String, affordable: Boolean): String? {
            val suffix = if (affordable) "平价桩" else
                stationSuffixes.firstOrNull(site::endsWith) ?: return null
            return site.removeSuffix(suffix)
        }
        val taskStem = siteStem(task.site, taskAffordable) ?: return false
        val actualStem = siteStem(actual.site, actualAffordable) ?: return false
        // Never use a partial name or discard directions, powers, site numbers or phases.
        return taskStem.length >= 4 && taskStem !in genericSites && taskStem == actualStem
    }

    /** A missing administrative prefix must be confirmed by this result's own address. */
    fun matchesAddressSupportedAdministrativePrefix(
        taskName: String,
        actualName: String,
        resultAddress: String,
    ): Boolean {
        val task = identity(taskName) ?: return false
        val actual = identity(actualName) ?: return false
        if (task.brand != actual.brand || task.site == actual.site || resultAddress.isBlank()) return false
        val longer = if (task.site.length > actual.site.length) task.site else actual.site
        val shorter = if (task.site.length > actual.site.length) actual.site else task.site
        if (!longer.endsWith(shorter)) return false
        val prefix = longer.removeSuffix(shorter)
        // Remove exactly one full city/county/district prefix, retaining the full site name.
        if (!Regex("^\\p{IsHan}{2,12}?[市县区]$").matches(prefix)) return false
        val suffix = stationSuffixes.firstOrNull(shorter::endsWith) ?: return false
        val specificSite = shorter.removeSuffix(suffix)
        if (specificSite.length < 4 || specificSite in genericSites) return false
        return addressConfirmsAdministrativePrefix(resultAddress, prefix)
    }

    /** Preserve the full locality and site, allowing 县/市/区 and a terminal charging label to be omitted. */
    fun matchesAddressSupportedLocalityVariant(
        taskName: String,
        actualName: String,
        resultAddress: String,
    ): Boolean {
        val task = identity(taskName) ?: return false
        val actual = identity(actualName) ?: return false
        if (task.brand != actual.brand || task.site == actual.site || resultAddress.isBlank()) return false
        fun withoutChargingSuffix(site: String): String =
            stationSuffixes.firstOrNull(site::endsWith)?.let(site::removeSuffix) ?: site
        val taskSite = withoutChargingSuffix(task.site)
        val actualSite = withoutChargingSuffix(actual.site)
        fun sameLocalityAndSite(full: String, other: String): Boolean {
            val administrative = Regex("^(\\p{IsHan}{2,12}?)(市|县|区)").find(full) ?: return false
            if (!addressConfirmsAdministrativePrefix(resultAddress, administrative.value)) return false
            val locality = administrative.groupValues[1]
            val site = full.substring(administrative.value.length)
            // Only the administrative suffix and trailing charging label may differ.
            // Retain 重卡, directions, road/site numbers, powers and phases.
            if (site.length < 4 || site in genericSites) return false
            return other == locality + site || other == administrative.value + site
        }
        return sameLocalityAndSite(taskSite, actualSite) || sameLocalityAndSite(actualSite, taskSite)
    }

    private fun addressConfirmsAdministrativePrefix(resultAddress: String, prefix: String): Boolean {
        // Use only this result's address; detail pages use hyphens where lists use spaces.
        val address = StationNameNormalizer.normalize(resultAddress)
        return Regex(
            "(?:^|[,，]|省|自治区|特别行政区|市|自治州|地区|盟)${Regex.escape(prefix)}(?![路街道])"
        ).containsMatchIn(address)
    }

    private fun identity(name: String, allowNewEnergyDescriptor: Boolean = false): Identity? {
        if (listOf("...", "…", "⋯", "．．．").any { it in name }) return null
        val raw = name.trim().replace('（', '(').replace('）', ')')
        val open = raw.indexOf('(')
        if (open <= 0 || !raw.endsWith(')')) return null
        // Do not accept an unfinished title or silently ignore text after its closing bracket.
        var depth = 0
        for (index in open until raw.length) {
            when (raw[index]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth < 0 || (depth == 0 && index != raw.lastIndex)) return null
        }
        if (depth != 0) return null
        val site = StationNameNormalizer.normalize(raw.substring(open + 1, raw.lastIndex))
        if (site.length < 4 || site in genericSites) return null
        val head = StationNameNormalizer.normalize(raw.substring(0, open))
        val suffix = stationSuffixes.firstOrNull(head::endsWith) ?: return null
        var brand = head.removeSuffix(suffix)
        while (true) {
            val descriptor = trailingDescriptors.firstOrNull(brand::endsWith)
                ?: if (allowNewEnergyDescriptor && brand.endsWith("新能源")) "新能源" else null
                ?: break
            brand = brand.removeSuffix(descriptor)
        }
        // Only known descriptors at the end of the head are removed. Site numbers,
        // road directions, powers, phases and all other brand words remain significant.
        if (brand.length < 2) return null
        return Identity(head, brand, site)
    }
}
