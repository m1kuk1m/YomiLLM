package com.example.japanesegrammarapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmResponseSanitizerTest {

    @Test
    fun cleanModelOutputStripsThinkBlocksCodeFencesAndOutputTags() {
        assertEquals(
            "図書館で本を読んでいる。",
            LlmResponseSanitizer.cleanModelOutput("<think>checking</think>\n図書館で本を読んでいる。")
        )
        assertEquals(
            "在图书馆看书。",
            LlmResponseSanitizer.cleanModelOutput("```text\n在图书馆看书。\n```")
        )
        assertEquals(
            "図書館\nで\n本",
            LlmResponseSanitizer.cleanModelOutput("<output>\n図書館\nで\n本\n</output>")
        )
        assertEquals(
            "本|ほん|书|名詞||||対象。",
            LlmResponseSanitizer.cleanModelOutput("```\n<output>\n本|ほん|书|名詞||||対象。\n</output>\n```")
        )
        // Streaming partial states
        assertEquals("", LlmResponseSanitizer.cleanModelOutput("```text"))
        assertEquals("", LlmResponseSanitizer.cleanModelOutput("<output>"))
        assertEquals(
            "図書館\nで",
            LlmResponseSanitizer.cleanModelOutput("```text\n<output>\n図書館\nで")
        )
    }

    @Test
    fun normalizeReadingToHiraganaConvertsKatakanaAndPreservesLongVowelMark() {
        assertEquals("あにめ", LlmResponseSanitizer.normalizeReadingToHiragana("アニメ"))
        assertEquals("こーひー", LlmResponseSanitizer.normalizeReadingToHiragana("コーヒー"))
        assertEquals("ゔぁいおりん", LlmResponseSanitizer.normalizeReadingToHiragana("ヴァイオリン"))
        assertEquals("いすゞ", LlmResponseSanitizer.normalizeReadingToHiragana("いすヾ"))
        assertEquals("よんで", LlmResponseSanitizer.normalizeReadingToHiragana("よんで"))
        assertEquals("", LlmResponseSanitizer.normalizeReadingToHiragana(""))
        assertNull(LlmResponseSanitizer.normalizeReadingToHiragana(null))
    }
}
