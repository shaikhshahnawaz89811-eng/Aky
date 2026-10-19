package com.codeassist.ai.service

/**
 * Phone-maker specific hints for keeping the hands-free service alive (audit PDF Gap B3: "OEM battery killers
 * in India: Xiaomi / Oppo / Vivo / Realme / Samsung"). Pure Kotlin. Menu names change between versions, so the
 * steps are written as "look for something like ...", not as exact paths.
 */
object BatteryAdvice {
    const val XIAOMI = "xiaomi"
    const val OPPO = "oppo"
    const val VIVO = "vivo"
    const val SAMSUNG = "samsung"
    const val ONEPLUS = "oneplus"
    const val HUAWEI = "huawei"
    const val OTHER = "other"

    fun oemKey(manufacturer: String?): String {
        val m = (manufacturer ?: "").trim().lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> XIAOMI
            m.contains("oppo") || m.contains("realme") -> OPPO
            m.contains("vivo") || m.contains("iqoo") -> VIVO
            m.contains("samsung") -> SAMSUNG
            m.contains("oneplus") -> ONEPLUS
            m.contains("huawei") || m.contains("honor") -> HUAWEI
            else -> OTHER
        }
    }

    fun label(key: String): String = when (key) {
        XIAOMI -> "Xiaomi / Redmi / Poco (MIUI / HyperOS)"
        OPPO -> "Oppo / Realme (ColorOS / realme UI)"
        VIVO -> "Vivo / iQOO (Funtouch / OriginOS)"
        SAMSUNG -> "Samsung (One UI)"
        ONEPLUS -> "OnePlus (OxygenOS)"
        HUAWEI -> "Huawei / Honor"
        else -> "Aapka phone"
    }

    /** Steps for the one maker screen the app can try to open, in the order the user should do them. */
    fun steps(key: String): List<String> = when (key) {
        XIAOMI -> listOf(
            "App info > Battery saver > \"No restrictions\" chuno.",
            "Security app > Permissions > Autostart mein CodeAssist ko on karo.",
            "Recent apps mein CodeAssist ko lock karo (card ko neeche kheench kar lock icon)."
        )
        OPPO -> listOf(
            "App info > Battery usage > \"Allow background activity\" on karo.",
            "Settings mein \"Auto launch\" / \"Startup manager\" mein CodeAssist ko allow karo.",
            "Recent apps mein CodeAssist ko lock karo."
        )
        VIVO -> listOf(
            "App info > Battery > \"High background power consumption\" allow karo.",
            "i Manager / Settings mein \"Background app management\" ya Autostart mein CodeAssist ko allow karo.",
            "Recent apps mein CodeAssist ko lock karo."
        )
        SAMSUNG -> listOf(
            "Settings > Battery > Background usage limits mein CodeAssist ko \"Sleeping apps\" / \"Deep sleeping apps\" se hatao.",
            "App info > Battery > \"Unrestricted\" chuno.",
            "\"Put unused apps to sleep\" band karo ya CodeAssist ko uski exception list mein daalo."
        )
        ONEPLUS -> listOf(
            "App info > Battery > \"Allow background activity\" on karo.",
            "Battery optimisation mein CodeAssist ko \"Don't optimise\" karo.",
            "Recent apps mein CodeAssist ko lock karo."
        )
        HUAWEI -> listOf(
            "App info > Battery > \"App launch\" mein \"Manage manually\" chuno, teeno switch (Auto-launch, Secondary launch, Run in background) on karo.",
            "Recent apps mein CodeAssist ko lock karo."
        )
        else -> listOf(
            "App info > Battery mein \"Unrestricted\" / \"Don't optimise\" chuno.",
            "Agar phone mein \"Autostart\" ya \"Background activity\" naam ka option hai, CodeAssist ke liye on karo.",
            "Recent apps mein CodeAssist ko lock karo (agar phone deta hai)."
        )
    }

    /** Short honest note: even after all this, nothing is guaranteed. */
    const val CAVEAT =
        "Ye steps phone ke version ke hisaab se alag naam se ho sakte hain. Phir bhi koi guarantee nahi: " +
            "agar service dobara band ho, app khulte hi wo khud chalu ho jaati hai aur ek notification bhi aati hai."
}
