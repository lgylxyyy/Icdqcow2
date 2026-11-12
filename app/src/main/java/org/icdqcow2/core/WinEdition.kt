package org.icdqcow2.core

/**
 * Windows 版本识别：**内部版本号（build）→ 家族**，**EditionID → 中文版本名**。
 *
 * 为什么不信 `ProductName`：它是给"关于 Windows"看的，各语言安装包写法不一致
 * （`Windows 10 Pro` / `Windows 10 专业版` / `Microsoft Windows XP`），而且同一个名字横跨
 * 多个 build。真正稳定的是 `CurrentBuild` 和 `EditionID`（工具端 `win.version` 两个都给了）。
 *
 * 覆盖面：XP(2600) → 11(22000~28000)，含 Vista / 7 / 8 / 8.1 和 Server 相关 build。
 * 数据来源：微软 Learn 的 release health 表 + Wikipedia「List of Microsoft Windows versions」
 * （2026-11 核对：19045=10 22H2、22000=11 21H2、22621=22H2、22631=23H2、26100=24H2、26200=25H2）。
 */
object WinEdition {

    /**
     * 内部版本号 → 产品家族名（"Windows 10" 这种）。判不出返回 null，别瞎猜。
     *
     * ★ 区间**必须有上界**：以前写成 `build >= 22000 -> Windows 11`，将来 Windows 12
     *   （比如 build 30000）会被标成 11。现在超出现有区间就返回 null，
     *   让 [label] 退回去用注册表里的 `ProductName`（Windows 自己写的名字）。
     */
    fun family(build: Int, installationType: String? = null): String? {
        // 同一个 build 既可能是客户端也可能是 Server（26100 = 11 24H2 / Server 2025）
        if (installationType?.trim().equals("Server", ignoreCase = true)) return serverFamily(build)
        return clientFamily(build)
    }

    /** Server 家族（`installation_type=Server` 走这张表）。 */
    fun serverFamily(build: Int): String? = when {
        build >= 26100 -> "Windows Server 2025"
        build in 20348..20349 -> "Windows Server 2022"
        build in 17763..17763 -> "Windows Server 2019"
        build in 14393..14393 -> "Windows Server 2016"
        build == 9600 -> "Windows Server 2012 R2"
        build == 9200 -> "Windows Server 2012"
        build in 7600..7602 -> "Windows Server 2008 R2"
        build in 6001..6003 -> "Windows Server 2008"
        build in 3790..3791 -> "Windows Server 2003"
        else -> null
    }

    private fun clientFamily(build: Int): String? = when {
        build in 28000..28999 -> "Windows 11"   // 26H1 起的预览线
        build in 22000..26999 -> "Windows 11"   // 21H2 ~ 25H2（22000/22621/22631/26100/26200）
        build in 10240..19999 -> "Windows 10"   // 1507 ~ 22H2
        build == 9600 -> "Windows 8.1"
        build == 9200 -> "Windows 8"
        build in 7600..7602 -> "Windows 7"
        build in 6000..6003 -> "Windows Vista"
        build in 3790..3791 -> "Windows Server 2003"
        build in 2195..2196 -> "Windows 2000"
        build == 2600 -> "Windows XP"
        else -> null
    }

    /** 注册表 `EditionID` → 中文版本名。 */
    fun editionZh(editionId: String?): String? = when (editionId?.trim()?.lowercase()) {
        "core" -> "家庭版"
        "corecountryspecific" -> "家庭中文版"   // 中国特供单语言（"Home China"）
        "coresinglelanguage" -> "家庭单语言版"
        "starter" -> "简易版"
        "homebasic", "homebasicn" -> "家庭普通版"
        "homepremium", "homepremiumn" -> "家庭高级版"
        "professional", "professionaln" -> "专业版"
        "professionaleducation", "professionaleducationn" -> "专业教育版"
        "professionalworkstation", "professionalworkstationn" -> "专业工作站版"
        "enterprise", "enterprisen" -> "企业版"
        "enterprises" -> "企业版 LTSC"
        "education", "educationn" -> "教育版"
        "ultimate", "ultimaten" -> "旗舰版"
        "serverstandard", "serverstandardeval" -> "Server 标准版"
        "serverdatacenter", "serverdatacentereval" -> "Server 数据中心版"
        else -> null
    }

    /**
     * XP / 2000 / Server 2003 的版本名：这三代**注册表里没有 `EditionID`**，
     * SKU 只写在 `ProductName` 里（REPLY-74 §2 确认）。
     *
     * ★ 必须按**整串**匹配、且 x64 排在 Professional 前面：
     *   `Microsoft Windows XP Professional x64 Edition` 里也含 `Professional`，
     *   用 `contains("Professional")` 会把它认成普通专业版。
     */
    private fun legacyEditionZh(productName: String?): String? {
        val t = cleanProductName(productName)?.lowercase() ?: return null
        return when {
            "professional x64" in t -> "专业版 x64"
            "professional" in t -> "专业版"
            "home edition" in t -> "家庭版"
            "media center" in t -> "媒体中心版"
            "tablet pc" in t -> "平板电脑版"
            "starter" in t -> "简易版"
            "embedded" in t -> "嵌入式版"
            "windows 2000 professional" in t -> "专业版"
            "windows 2000 server" in t -> "服务器版"
            "windows 2000 advanced server" in t -> "高级服务器版"
            "datacenter" in t -> "数据中心版"
            "enterprise" in t -> "企业版"
            "web edition" in t -> "Web 版"
            "standard edition" in t -> "标准版"
            else -> null
        }
    }

    /** 把注册表里的 `ProductName` 洗成能直接显示的样子（去掉厂商前缀/多余空白）。 */
    private fun cleanProductName(raw: String?): String? {
        val t = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (t.isEmpty()) return null
        return t.removePrefix("Microsoft ").removePrefix("Microsoft® ").trim().ifEmpty { null }
    }

    /**
     * 自检页那一行的完整文案：`Windows 11 专业版 24H2 · 26100.3800`。
     *
     * - 功能更新代号取注册表 `DisplayVersion`（Win10 1903+）/ `ReleaseId`（1511~1809），
     *   **不再用 App 里写死的对照表**（REPLY-72：工具端恒定输出，读不到就是空串）；
     * - `UBR` 有就拼成 `build.ubr`，没有就是 `build`。
     */
    fun summary(
        productName: String?,
        editionId: String?,
        build: Int,
        installationType: String? = null,
        displayVersion: String? = null,
        releaseId: String? = null,
        ubr: Int = 0,
        /** 注册表 `CSDVersion`：`Service Pack 3`（XP/7 这类才有关）。 */
        csdVersion: String? = null,
    ): String? {
        val name = label(productName, editionId, build, installationType) ?: return null
        val rel = displayVersion?.trim()?.ifBlank { null }
            ?: releaseId?.trim()?.ifBlank { null }
        val ver = if (ubr > 0) "$build.$ubr" else build.toString()
        // SP 走名字后面（Windows 自己也这么写：Windows XP Professional Service Pack 3），
        // 但压成 `SP3` —— 8.1/Win10 之后没有 CSDVersion，这一段自然就没有。
        val sp = csdVersion?.trim()?.ifBlank { null }?.let { raw ->
            Regex("^Service\\s*Pack\\s*(\\d+)$", RegexOption.IGNORE_CASE)
                .find(raw)?.let { "SP${it.groupValues[1]}" } ?: raw
        }
        val head = buildString {
            append(name)
            if (sp != null) append(' ').append(sp)
            if (rel != null) append(' ').append(rel)
        }
        return "$head · $ver"
    }

    /**
     * 最终显示名：`Windows 10 家庭中文版` 这种。
     *
     * 拼接优先级：
     * 1. 家族（build 判） + 中文版本名（EditionID 判）—— 最稳；
     * 2. EditionID 认不出 → 用洗过的 `ProductName`；
     * 3. 连 ProductName 都没有 → 只给家族名；
     * 4. build 也不认识 → null（界面显示"读不出"，不瞎猜）。
     */
    fun label(
        productName: String?,
        editionId: String?,
        build: Int,
        installationType: String? = null,
    ): String? {
        val fam = family(build, installationType)   // 认识的 build 才给家族名，不认识就是 null
        var ed = editionZh(editionId) ?: legacyEditionZh(productName)
        // Server 家族名里已经带 "Windows Server"，版本名里的 "Server " 是重复的
        // （Server 数据中心版 + Windows Server 2025 → Windows Server 2025 数据中心版）
        if (fam != null && fam.startsWith("Windows Server ") && ed != null && ed.startsWith("Server ")) {
            ed = ed.removePrefix("Server ")
        }
        val pn = cleanProductName(productName)
        if (fam != null && ed != null) return "$fam $ed"
        if (fam != null && pn != null) {
            // ProductName 里已经带家族名就别重复拼
            return if (pn.contains(fam.removePrefix("Windows "), ignoreCase = true)) pn else "$fam $pn"
        }
        if (pn != null) return pn
        return fam
    }
}