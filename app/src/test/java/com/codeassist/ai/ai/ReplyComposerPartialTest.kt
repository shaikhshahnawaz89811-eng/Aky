package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyComposerPartialTest {
    @Test fun nothingWrongNoSummary() {
        assertNull(ReplyComposer.partialSummary(3, 0, 0))
    }

    @Test fun singleTaskNoSummary() {
        assertNull(ReplyComposer.partialSummary(0, 1, 0))
        assertNull(ReplyComposer.partialSummary(0, 0, 1))
    }

    @Test fun mixed() {
        assertEquals(
            "2 kaam ho gaye, 1 nahi hua. Chaho toh nahi hue kaam dobara bolo.",
            ReplyComposer.partialSummary(2, 1, 0)
        )
        assertEquals(
            "1 kaam ho gaya, 1 nahi hua, 2 chhod diya. Chaho toh nahi hue kaam dobara bolo.",
            ReplyComposer.partialSummary(1, 1, 2)
        )
        assertEquals(
            "Koi kaam nahi hua, 1 chhod diya. Chaho toh nahi hue kaam dobara bolo.",
            ReplyComposer.partialSummary(0, 1, 1)
        )
        assertEquals(
            "Koi kaam nahi hua. Chaho toh nahi hue kaam dobara bolo.",
            ReplyComposer.partialSummary(0, 2, 0)
        )
    }
}
