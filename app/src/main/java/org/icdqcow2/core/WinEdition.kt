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

    /** 内部版本号 → 产品家族名（"Windows 10" 这种）。判不出返回 null，别瞎猜。 */
    fun family(build: Int): String? = when {
        build >= 28000 -> "Windows 11"          // 26H1 起的预览线
        build >= 22000 -> "Windows 11"          // 21H2 ~ 25H2（22000/22621/22631/26100/26200）
        build in 10240..19099 -> "Windows 10"   // 1507 ~ 22H2
        build == 9600 -> "Windows 8.1"
        build == 9200 -> "Windows 8"
        build in 7600..7602 -> "Windows 7"
        build in 6000..6003 -> "Windows Vista"
        build in 3790..3791 -> "Windows Server 2003"
        build in 2195..2196 -> "Windows 2000"
        build == 2600 -> "Windows XP"
        else -> null
    }

    /** 10/11 的功能更新代号（22H2 这种）。老系统没有，返回 null。 */
    fun release(build: Int): String? = when (build) {
        28000 -> "26H1"
        26200 -> "25H2"
        26100 -> "24H2"
        22631 -> "23H2"
        22621 -> "22H2"
        22000 -> "21H2"
        19045 -> "22H2"
        19044 -> "21H2"
        19043 -> "21H1"
        19042 -> "20H2"
        19041 -> "2004"
        18363 -> "1909"
        18362 -> "1903"
        17763 -> "1809"
        17134 -> "1803"
        16299 -> "1709"
        15063 -> "1703"
        14393 -> "1607"
        10586 -> "1511"
        10240 -> "1507"
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

    /** 把注册表里的 `ProductName` 洗成能直接显示的样子（去掉厂商前缀/多余空白）。 */
    private fun cleanProductName(raw: String?): String? {
        val t = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (t.isEmpty()) return null
        return t.removePrefix("Microsoft ").removePrefix("Microsoft® ").trim().ifEmpty { null }
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
    fun label(productName: String?, editionId: String?, build: Int): String? {
        val fam = family(build)
        val ed = editionZh(editionId)
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