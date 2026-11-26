package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.LlmStreamEvent
import com.example.japanesegrammarapp.domain.model.ReasoningLevel
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.network.DecisionRequest
import com.example.japanesegrammarapp.network.DecisionResponse
import com.example.japanesegrammarapp.network.GeminiCandidate
import com.example.japanesegrammarapp.network.GeminiContent
import com.example.japanesegrammarapp.network.GeminiModelListResponse
import com.example.japanesegrammarapp.network.GeminiPart
import com.example.japanesegrammarapp.network.GeminiPromptFeedback
import com.example.japanesegrammarapp.network.GeminiRequest
import com.example.japanesegrammarapp.network.GeminiResponse
import com.example.japanesegrammarapp.network.GeminiUsageMetadata
import com.example.japanesegrammarapp.network.LlmApiService
import com.example.japanesegrammarapp.network.OpenAiModelListResponse
import com.example.japanesegrammarapp.network.OpenAiRequest
import com.example.japanesegrammarapp.network.OpenAiResponse
import com.google.gson.Gson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LlmRepositoryImplTest {

    private class FakeLlmApiService(
        private val responses: ArrayDeque<GeminiResponse>
    ) : LlmApiService {
        val requestedUrls = mutableListOf<String>()

        override suspend fun generateOpenAiCompatible(
            url: String,
            authHeader: String,
            request: OpenAiRequest
        ): OpenAiResponse = error("unused")

        override suspend fun getOpenAiModels(
            url: String,
            authHeader: String
        ): OpenAiModelListResponse = error("unused")

        override suspend fun generateGemini(
            url: String,
            request: GeminiRequest
        ): GeminiResponse {
            requestedUrls += url
            return responses.removeFirst()
        }

        override suspend fun getGeminiModels(url: String): GeminiModelListResponse = error("unused")

        override suspend fun executeDecisions(
            url: String,
            request: DecisionRequest
        ): DecisionResponse = error("unused")
    }

    private fun mockSettings(
        streamingEnabled: Boolean,
        failoverToNextEndpoint: Boolean = true
    ): SettingsRepository {
        val settings = mock(SettingsRepository::class.java)
        `when`(settings.getStreamingEnabled()).thenReturn(streamingEnabled)
        `when`(settings.getFailoverToNextEndpoint()).thenReturn(failoverToNextEndpoint)
        `when`(settings.getEffectiveReasoningLevel("translation")).thenReturn(ReasoningLevel.LOW)
        return settings
    }

    @Test
    fun executeWithStreamingUsesNonStreamingEndpointForGeminiWhenStreamingDisabled() = runBlocking {
        val fakeApi = FakeLlmApiService(
            ArrayDeque(
                listOf(
                    GeminiResponse(
                        candidates = listOf(
                            GeminiCandidate(
                                content = GeminiContent(
                                    role = "model",
                                    parts = listOf(
                                        GeminiPart(text = "internal thought", thought = true),
                                        GeminiPart(text = "这是完整翻译")
                                    )
                                ),
                                finishReason = "STOP"
                            )
                        ),
                        usageMetadata = GeminiUsageMetadata(
                            totalTokenCount = 45,
                            promptTokenCount = 20,
                            candidatesTokenCount = 15,
                            thoughtsTokenCount = 10
                        )
                    )
                )
            )
        )
        val healthTracker = InMemoryEndpointHealthTracker()
        val repository = LlmRepositoryImpl(
            llmService = fakeApi,
            settingsRepository = mockSettings(streamingEnabled = false),
            endpointHealthTracker = healthTracker,
            okHttpClient = OkHttpClient(),
            gson = Gson()
        )
        val config = LlmApiConfig(
            provider = "Gemini",
            baseProvider = "Gemini",
            modelName = "gemini-2.5-flash",
            url = "https://generativelanguage.googleapis.com/v1beta",
            key = "test-key",
            endpointId = "ep-gemini",
            endpointName = "Gemini Primary"
        )

        val events = repository.executeWithStreaming(
            systemPrompt = "system",
            userPrompt = "user",
            imageBase64 = null,
            mimeType = null,
            apiTypeLabel = "translation",
            primaryConfigs = listOf(config),
            backupConfigs = emptyList(),
            recordId = 1,
            stepName = "TRANSLATION"
        ).toList()

        assertEquals(1, fakeApi.requestedUrls.size)
        assertTrue(fakeApi.requestedUrls.single().contains(":generateContent?key=test-key"))
        assertEquals(3, events.size)
        assertEquals(LlmStreamEvent.ThoughtChunk("internal thought"), events[0])
        assertEquals(LlmStreamEvent.Chunk("这是完整翻译"), events[1])
        val metaEvent = events[2] as LlmStreamEvent.Metadata
        assertEquals("Gemini", metaEvent.provider)
        assertEquals("gemini-2.5-flash", metaEvent.modelName)
        assertEquals(45, metaEvent.usage.consumedTokens)
        assertEquals(20, metaEvent.usage.inputTokens)
        assertEquals(15, metaEvent.usage.outputTokens)
        assertEquals(10, metaEvent.usage.reasoningTokens)
        assertEquals(0, healthTracker.getHealth("ep-gemini").consecutiveFailures)
    }

    @Test
    fun executeWithStreamingAlsoUsesNonStreamingForVertexAiWhenStreamingDisabled() = runBlocking {
        val fakeApi = FakeLlmApiService(
            ArrayDeque(
                listOf(
                    GeminiResponse(
                        candidates = listOf(
                            GeminiCandidate(
                                content = GeminiContent(
                                    role = "model",
                                    parts = listOf(GeminiPart(text = "Vertex response"))
                                ),
                                finishReason = "STOP"
                            )
                        ),
                        usageMetadata = GeminiUsageMetadata(totalTokenCount = 12, promptTokenCount = 7, candidatesTokenCount = 5)
                    )
                )
            )
        )
        val repository = LlmRepositoryImpl(
            llmService = fakeApi,
            settingsRepository = mockSettings(streamingEnabled = false),
            endpointHealthTracker = InMemoryEndpointHealthTracker(),
            okHttpClient = OkHttpClient(),
            gson = Gson()
        )
        val config = LlmApiConfig(
            provider = "Vertex AI",
            baseProvider = "Vertex AI",
            modelName = "gemini-2.5-pro",
            url = "https://aiplatform.googleapis.com/v1/publishers/google",
            key = "vertex-key",
            endpointId = "ep-vertex",
            endpointName = "Vertex Primary"
        )

        val events = repository.executeWithStreaming(
            systemPrompt = "system",
            userPrompt = "user",
            imageBase64 = null,
            mimeType = null,
            apiTypeLabel = "translation",
            primaryConfigs = listOf(config),
            backupConfigs = emptyList(),
            recordId = 2,
            stepName = "TRANSLATION"
        ).toList()

        assertEquals(1, fakeApi.requestedUrls.size)
        assertTrue(fakeApi.requestedUrls.single().contains(":generateContent?key=vertex-key"))
        assertEquals(LlmStreamEvent.Chunk("Vertex response"), events[0])
    }

    @Test
    fun nonStreamingGeminiSafetyBlockFailsOverToNextEndpoint() = runBlocking {
        val fakeApi = FakeLlmApiService(
            ArrayDeque(
                listOf(
                    GeminiResponse(
                        candidates = emptyList(),
                        promptFeedback = GeminiPromptFeedback(blockReason = "PROHIBITED_CONTENT")
                    ),
                    GeminiResponse(
                        candidates = listOf(
                            GeminiCandidate(
                                content = GeminiContent(
                                    role = "model",
                                    parts = listOf(GeminiPart(text = "backup success"))
                                ),
                                finishReason = "STOP"
                            )
                        ),
                        usageMetadata = GeminiUsageMetadata(totalTokenCount = 10, promptTokenCount = 6, candidatesTokenCount = 4)
                    )
                )
            )
        )
        val healthTracker = InMemoryEndpointHealthTracker()
        val repository = LlmRepositoryImpl(
            llmService = fakeApi,
            settingsRepository = mockSettings(streamingEnabled = false, failoverToNextEndpoint = true),
            endpointHealthTracker = healthTracker,
            okHttpClient = OkHttpClient(),
            gson = Gson()
        )
        val primary = LlmApiConfig(
            provider = "Gemini",
            baseProvider = "Gemini",
            modelName = "gemini-2.5-flash",
            url = "https://ep1.example",
            key = "k1",
            endpointId = "ep-1",
            endpointName = "EP1"
        )
        val secondary = LlmApiConfig(
            provider = "Gemini",
            baseProvider = "Gemini",
            modelName = "gemini-2.5-flash",
            url = "https://ep2.example",
            key = "k2",
            endpointId = "ep-2",
            endpointName = "EP2"
        )

        val events = repository.executeWithStreaming(
            systemPrompt = "system",
            userPrompt = "user",
            imageBase64 = null,
            mimeType = null,
            apiTypeLabel = "translation",
            primaryConfigs = listOf(primary, secondary),
            backupConfigs = emptyList(),
            recordId = 3,
            stepName = "TRANSLATION"
        ).toList()

        assertEquals(2, fakeApi.requestedUrls.size)
        assertEquals(1, healthTracker.getHealth("ep-1").consecutiveFailures)
        assertEquals(0, healthTracker.getHealth("ep-2").consecutiveFailures)
        assertEquals(LlmStreamEvent.Chunk("backup success"), events[0])
    }
}
