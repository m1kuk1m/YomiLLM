package com.example.japanesegrammarapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConfigTest {

    @Test
    fun geminiVersionAndReasoningDetectionCoversGemini3AndGemini4() {
        assertFalse(LlmConfig.isGemini3OrLater("gemini-1.5-pro"))
        assertFalse(LlmConfig.isGemini3OrLater("gemini-2.5-flash"))
        assertTrue(LlmConfig.isGemini3OrLater("gemini-3.1-flash-lite"))
        assertTrue(LlmConfig.isGemini3OrLater("gemini-3.5-flash"))
        assertTrue(LlmConfig.isGemini3OrLater("gemini-3.6-pro"))
        assertTrue(LlmConfig.isGemini3OrLater("gemini-4-flash"))
        assertTrue(LlmConfig.isGemini3OrLater("models/gemini-4.0-pro"))

        assertFalse(LlmConfig.isGeminiReasoningModel("gemini-1.5-flash"))
        assertTrue(LlmConfig.isGeminiReasoningModel("gemini-2.5-flash"))
        assertTrue(LlmConfig.isGeminiReasoningModel("gemini-3.5-flash"))
        assertTrue(LlmConfig.isGeminiReasoningModel("gemini-4-flash"))
        assertTrue(LlmConfig.isGeminiReasoningModel("models/gemini-4-pro"))

        assertEquals(0.1, LlmConfig.resolveGeminiTemperature("gemini-2.5-flash")!!, 0.0001)
        assertNull(LlmConfig.resolveGeminiTemperature("gemini-3.1-flash-lite"))
        assertNull(LlmConfig.resolveGeminiTemperature("gemini-3.6-pro"))
        assertNull(LlmConfig.resolveGeminiTemperature("gemini-4-flash"))
    }

    @Test
    fun openAiReasoningDetectionAndParametersCoverGpt5AndOSeries() {
        listOf("o1", "o1-mini", "o3", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini", "gpt-5.2", "openai/gpt-5").forEach { model ->
            assertTrue("$model should be detected as OpenAI reasoning model", LlmConfig.isOpenAiReasoningModel(model))
            assertNull("$model should omit custom temperature", LlmConfig.resolveOpenAiTemperature(model))
            assertEquals("low", LlmConfig.resolveOpenAiReasoningEffort(model, ReasoningLevel.LOW))
            assertEquals("medium", LlmConfig.resolveOpenAiReasoningEffort(model, ReasoningLevel.MEDIUM))
            assertEquals("high", LlmConfig.resolveOpenAiReasoningEffort(model, ReasoningLevel.HIGH))
            assertNull(LlmConfig.resolveOpenAiReasoningEffort(model, ReasoningLevel.AUTO))
        }

        assertEquals("minimal", LlmConfig.resolveOpenAiReasoningEffort("gpt-5", ReasoningLevel.OFF))
        assertEquals("minimal", LlmConfig.resolveOpenAiReasoningEffort("openai/gpt-5-mini", ReasoningLevel.OFF))
        assertNull(LlmConfig.resolveOpenAiReasoningEffort("o3-mini", ReasoningLevel.OFF))
        assertNull(LlmConfig.resolveOpenAiReasoningEffort("o4-mini", ReasoningLevel.OFF))

        listOf("gpt-4o", "gpt-4.1", "deepseek-chat", "qwen-max", "custom-o10-model").forEach { model ->
            assertFalse("$model should not be detected as OpenAI reasoning model", LlmConfig.isOpenAiReasoningModel(model))
            assertEquals(0.1, LlmConfig.resolveOpenAiTemperature(model)!!, 0.0001)
            assertNull(LlmConfig.resolveOpenAiReasoningEffort(model, ReasoningLevel.HIGH))
        }
    }
}
