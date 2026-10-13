package com.codeassist.ai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRefusalTest {
    private val refusal =
        "Main ek AI hoon, isliye main aapke phone par WhatsApp open nahi kar sakta. Aapko WhatsApp use karna hoga."

    @Test fun aFalseRefusalToAShortOrderIsRetried() {
        assertTrue(ToolRefusal.shouldRetryWithTools("WhatsApp on karo", refusal))
        assertTrue(ToolRefusal.shouldRetryWithTools("youtube chalu kar do", "I can't open apps on your phone."))
    }

    @Test fun aQuestionAboutAbilityIsNeverRetried() {
        assertFalse(ToolRefusal.shouldRetryWithTools("kya tum WhatsApp khol sakte ho?", refusal))
        assertFalse(ToolRefusal.shouldRetryWithTools("kya tum whatsapp khol sakte ho", refusal))
        assertFalse(ToolRefusal.shouldRetryWithTools("whatsapp kaise khol sakte hain", refusal))
    }

    @Test fun anAnswerThatIsNotARefusalIsLeftAlone() {
        assertFalse(ToolRefusal.shouldRetryWithTools("WhatsApp on karo", "WhatsApp khol diya."))
        assertFalse(ToolRefusal.shouldRetryWithTools("torch on karo", "Main ek AI hoon aur kahani sunata hoon."))
    }

    @Test fun aTrueRefusalAboutAPhoneSettingIsLeftAlone() {
        assertFalse(ToolRefusal.shouldRetryWithTools("wifi on karo", "Main ek AI hoon, wifi on nahi kar sakta, phone ki settings kholo."))
        assertFalse(ToolRefusal.shouldRetryWithTools("bluetooth chalu kar do", refusal))
    }

    @Test fun codeAndLongMessagesAreNeverRetried() {
        assertFalse(ToolRefusal.shouldRetryWithTools("python code run karo", refusal))
        assertFalse(ToolRefusal.shouldRetryWithTools("whatsapp on karo " + "word ".repeat(30), refusal))
        assertFalse(ToolRefusal.shouldRetryWithTools("", refusal))
        assertFalse(ToolRefusal.shouldRetryWithTools("WhatsApp on karo", ""))
    }
}
