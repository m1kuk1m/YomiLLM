package com.example.japanesegrammarapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptManagerTest {
    private val allPrompts: Map<String, String> = mapOf(
        "SYSTEM_PROMPT_IMAGE_OCR" to PromptManager.SYSTEM_PROMPT_IMAGE_OCR,
        "SYSTEM_PROMPT_TOKENIZER" to PromptManager.SYSTEM_PROMPT_TOKENIZER,
        "SYSTEM_PROMPT_TOKENIZER_OCR" to PromptManager.SYSTEM_PROMPT_TOKENIZER_OCR,
        "SYSTEM_PROMPT_SEGMENTS" to PromptManager.SYSTEM_PROMPT_SEGMENTS,
        "SYSTEM_PROMPT_SEGMENTS_DECOUPLED" to PromptManager.SYSTEM_PROMPT_SEGMENTS_DECOUPLED,
        "SYSTEM_PROMPT_TRANSLATION" to PromptManager.SYSTEM_PROMPT_TRANSLATION,
        "SYSTEM_PROMPT_CLAUSES" to PromptManager.SYSTEM_PROMPT_CLAUSES,
        "SYSTEM_PROMPT_GRAMMAR" to PromptManager.SYSTEM_PROMPT_GRAMMAR
    )

    private val exampleRegex = Regex("<example>\\s*<input>([\\s\\S]*?)</input>\\s*<output>([\\s\\S]*?)</output>\\s*</example>")
    private val hiraganaOnlyRegex = Regex("^[\\u3041-\\u3096\\u309D\\u309Eー]+$")

    @Test
    fun imageOcrPromptSpecifiesVerticalAndHorizontalReadingOrderAndRubyExclusion() {
        val prompt = PromptManager.SYSTEM_PROMPT_IMAGE_OCR
        assertTrue(prompt.contains("縦書き（文字が縦に積まれている）の場合"))
        assertTrue(prompt.contains("横書き（文字が横に並んでいる）の場合"))
        assertTrue(prompt.contains("振り仮名（ルビ）"))
    }

    @Test
    fun allPromptsUseUnifiedXmlStructureWithoutMarkdownOrShouting() {
        val requiredTags = listOf(
            "<role_and_context>", "</role_and_context>",
            "<task_and_output>", "</task_and_output>",
            "<rules>", "</rules>",
            "<examples>", "</examples>",
            "<example>", "</example>",
            "<input>", "</input>",
            "<output>", "</output>"
        )
        val forbiddenTokens = listOf("**", "`", "【厳守】", "【絶対条件】", "【最重要】", "厳禁", "一切禁止")

        allPrompts.forEach { (name, prompt) ->
            requiredTags.forEach { tag ->
                assertTrue("$name should contain $tag", prompt.contains(tag))
            }
            forbiddenTokens.forEach { forbidden ->
                assertFalse("$name should not contain $forbidden", prompt.contains(forbidden))
            }
        }
    }

    @Test
    fun pipeDelimitedPromptExamplesMatchParserColumnCounts() {
        assertExampleColumnCount("SYSTEM_PROMPT_SEGMENTS", PromptManager.SYSTEM_PROMPT_SEGMENTS, expectedColumns = 8)
        assertExampleColumnCount("SYSTEM_PROMPT_SEGMENTS_DECOUPLED", PromptManager.SYSTEM_PROMPT_SEGMENTS_DECOUPLED, expectedColumns = 7)
        assertExampleColumnCount("SYSTEM_PROMPT_CLAUSES", PromptManager.SYSTEM_PROMPT_CLAUSES, expectedColumns = 4)
        assertExampleColumnCount("SYSTEM_PROMPT_GRAMMAR", PromptManager.SYSTEM_PROMPT_GRAMMAR, expectedColumns = 2)
    }

    @Test
    fun segmentPromptExamplesUsePureHiraganaReadings() {
        listOf(
            "SYSTEM_PROMPT_SEGMENTS" to (PromptManager.SYSTEM_PROMPT_SEGMENTS to 5),
            "SYSTEM_PROMPT_SEGMENTS_DECOUPLED" to (PromptManager.SYSTEM_PROMPT_SEGMENTS_DECOUPLED to 4)
        ).forEach { (name, pair) ->
            val (prompt, dictReadingIndex) = pair
            val examples = exampleRegex.findAll(prompt).toList()
            assertTrue("$name should have at least 2 examples", examples.size >= 2)
            examples.forEach { match ->
                match.groupValues[2].lines().filter { it.isNotBlank() }.forEach { line ->
                    val cols = line.split("|")
                    val reading = cols[1]
                    assertTrue("$name reading '$reading' should be pure hiragana", hiraganaOnlyRegex.matches(reading))
                    val dictReading = cols[dictReadingIndex]
                    if (dictReading.isNotBlank()) {
                        assertTrue("$name dictReading '$dictReading' should be pure hiragana", hiraganaOnlyRegex.matches(dictReading))
                    }
                }
            }
        }
    }

    @Test
    fun tokenizerAndClauseExamplesPreserveExactConcatenation() {
        val tokenizerExamples = exampleRegex.findAll(PromptManager.SYSTEM_PROMPT_TOKENIZER).toList()
        assertTrue("SYSTEM_PROMPT_TOKENIZER should have at least 3 examples", tokenizerExamples.size >= 3)
        tokenizerExamples.forEach { match ->
            val input = match.groupValues[1].trim()
            val joined = match.groupValues[2].lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("")
            assertEquals("SYSTEM_PROMPT_TOKENIZER concatenation mismatch", input, joined)
        }

        val ocrExamples = exampleRegex.findAll(PromptManager.SYSTEM_PROMPT_TOKENIZER_OCR).toList()
        assertTrue("SYSTEM_PROMPT_TOKENIZER_OCR should have at least 3 examples", ocrExamples.size >= 3)
        ocrExamples.forEach { match ->
            val lines = match.groupValues[2].lines().map { it.trim() }.filter { it.isNotBlank() }
            assertTrue("SYSTEM_PROMPT_TOKENIZER_OCR output should have header + tokens", lines.size >= 2)
            assertEquals("SYSTEM_PROMPT_TOKENIZER_OCR concatenation mismatch", lines.first(), lines.drop(1).joinToString(""))
        }

        val clauseExamples = exampleRegex.findAll(PromptManager.SYSTEM_PROMPT_CLAUSES).toList()
        assertTrue("SYSTEM_PROMPT_CLAUSES should have at least 2 examples", clauseExamples.size >= 2)
        clauseExamples.forEach { match ->
            val input = match.groupValues[1].trim()
            val rows = match.groupValues[2].lines().map { it.trim() }.filter { it.isNotBlank() }
            rows.forEachIndexed { idx, row ->
                val cols = row.split("|")
                assertEquals("SYSTEM_PROMPT_CLAUSES index should be 1-based sequential", (idx + 1).toString(), cols[0])
            }
            val joinedText = rows.joinToString("") { it.split("|")[2] }
            assertEquals("SYSTEM_PROMPT_CLAUSES concatenation mismatch", input, joinedText)
        }
    }

    private fun assertExampleColumnCount(name: String, prompt: String, expectedColumns: Int) {
        val examples = exampleRegex.findAll(prompt).toList()
        assertTrue("$name should contain at least 2 examples", examples.size >= 2)
        examples.forEach { match ->
            val lines = match.groupValues[2].lines().filter { it.isNotBlank() }
            assertTrue("$name example output should not be empty", lines.isNotEmpty())
            lines.forEach { line ->
                assertEquals(
                    "$name example line '$line' should have exactly $expectedColumns columns",
                    expectedColumns,
                    line.split("|").size
                )
            }
        }
    }
}
