package com.codeassist.ai.ai

/**
 * Reads "open an app" phrases from an already normalized message (lower case, no punctuation: see Tier0.clean).
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM; [Tier0] looks the app up on the phone.
 *
 * Test chat 13 Oct 2026: "WhatsApp on karo" was refused by the model because no rule saw it as "open an app".
 * Two groups of phrases exist now:
 *  - the old ones ("open whatsapp", "whatsapp kholo", "whatsapp chalu karo"): any app whose name contains the words matches;
 *  - the "switch on" ones ("whatsapp on karo", "youtube chalu kar do"): these are also said about things that are NOT apps
 *    ("wifi on karo"), so they are [Req.strict]: only an app whose name is exactly the words (or starts with them, when
 *    they are 4+ letters) is opened, and phone settings are never treated as apps.
 */
object OpenPhrase {
    /** [name] is the app words; [strict] tells the phone lookup to be careful (see above). */
    class Req(val name: String, val strict: Boolean)

    private val openPrefix = Regex("^(open|launch|start|kholo|khol) (.+)$")
    private val openSuffix = Regex("^(.+?) (kholo|khol do|khol|open karo|open kar do|open|chalu karo|start karo|launch karo)$")

    private val switchSuffix = Regex(
        "^(.+?) (on karo|on kar do|on kardo|on kar de|chalu kar do|chalu kardo|chalu kar de|" +
            "start kar do|start kardo|launch kar do|launch kardo|khol de|khol dena|on|chalu)$"
    )

    /** Things people switch on that are phone settings, not apps: left to the brain, which says honestly that it cannot. */
    private val notApps = setOf(
        "wifi", "wi fi", "bluetooth", "hotspot", "data", "mobile data", "internet", "net", "gps", "location", "nfc",
        "airplane mode", "flight mode", "silent", "silent mode", "dnd", "do not disturb", "sound", "volume", "brightness",
        "night mode", "dark mode", "battery saver", "power saving", "screen", "display", "phone", "mobile", "light", "flash"
    )

    private val tidy = Regex("( app| ko| please)+$")
    private val lead = Regex("^(the |app )+")

    /** The app request in [t], or null when [t] is not one (or the name is too short to be one). */
    fun parse(t: String): Req? {
        val pre = openPrefix.find(t)?.groupValues?.get(2)
        val suf = if (pre == null) openSuffix.find(t)?.groupValues?.get(1) else null
        if (pre != null || suf != null) {
            val name = clean(pre ?: suf ?: return null)
            return if (name.length < 2) null else Req(name, false)
        }
        val sw = switchSuffix.find(t)?.groupValues?.get(1) ?: return null
        val name = clean(sw)
        if (name.length < 2 || name in notApps) return null
        return Req(name, true)
    }

    private fun clean(raw: String): String = raw.replace(lead, "").replace(tidy, "").trim()
}
