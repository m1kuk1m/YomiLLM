package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.AnalysisModule
import com.example.japanesegrammarapp.domain.model.ComponentModelConfig
import com.example.japanesegrammarapp.domain.model.LlmEndpoint
import com.example.japanesegrammarapp.domain.model.LlmStreamEvent
import com.example.japanesegrammarapp.domain.model.ReasoningLevel
import com.example.japanesegrammarapp.domain.repository.ImageAttachmentLoader
import com.example.japanesegrammarapp.domain.repository.ImagePayload
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.LlmRepository
import com.example.japanesegrammarapp.domain.repository.LlmResult
import com.example.japanesegrammarapp.domain.repository.LlmResultMetadata
import com.example.japanesegrammarapp.domain.repository.OcrRepository
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.domain.usecase.LlmEndpointPoolResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ImageTextExtractorImplTest {

    private class FakeLlmRepository(
        private val streamText: String = "",
        private val shouldFail: Boolean = false
    ) : LlmRepository {
        override suspend fun fetchModels(provider: String, baseUrl: String, apiKey: String): List<String> = emptyList()

        override suspend fun callLlmApi(
            systemPrompt: String,
            userPrompt: String,
            imageBase64: String?,
            mimeType: String?,
            provider: String,
            baseProvider: String,
            modelName: String,
            effectiveUrl: String,
            apiKey: String,
            apiTypeLabel: String
        ): LlmResult = error("unused")

        override suspend fun executeWithFailover(
            systemPrompt: String,
            userPrompt: String,
            imageBase64: String?,
            mimeType: String?,
            apiTypeLabel: String,
            primaryConfigs: List<LlmApiConfig>,
            backupConfigs: List<LlmApiConfig>,
            recordId: Int?,
            stepName: String?,
            onRetry: (attempt: Int) -> Unit,
            onBackup: (backupProvider: String) -> Unit
        ): LlmResult = error("unused")

        override suspend fun executeWithStreaming(
            systemPrompt: String,
            userPrompt: String,
            imageBase64: String?,
            mimeType: String?,
            apiTypeLabel: String,
            primaryConfigs: List<LlmApiConfig>,
            backupConfigs: List<LlmApiConfig>,
            recordId: Int?,
            stepName: String?,
            onRetry: (attempt: Int) -> Unit,
            onBackup: (backupProvider: String) -> Unit
        ): Flow<LlmStreamEvent> = flow {
            if (shouldFail) error("vision failed")
            emit(LlmStreamEvent.Chunk(streamText))
            emit(
                LlmStreamEvent.Metadata(
                    provider = primaryConfigs.firstOrNull()?.provider ?: "Gemini",
                    modelName = primaryConfigs.firstOrNull()?.modelName ?: "gemini-test",
                    usage = LlmResultMetadata(consumedTokens = 15, inputTokens = 10, outputTokens = 5)
                )
            )
        }
    }

    private fun baseSettings(removeSpaces: Boolean = false): SettingsRepository {
        val settings = mock(SettingsRepository::class.java)
        `when`(settings.getCustomPrompt(AnalysisModule.IMAGE_OCR.promptKey)).thenReturn("ocr system prompt")
        `when`(settings.getEffectiveReasoningLevel(AnalysisModule.IMAGE_OCR.id)).thenReturn(ReasoningLevel.AUTO)
        `when`(settings.getRemoveAccidentalSpaces()).thenReturn(removeSpaces)
        `when`(settings.getComponentModelConfig(AnalysisModule.IMAGE_OCR.id)).thenReturn(ComponentModelConfig())
        `when`(settings.getActiveProvider()).thenReturn("Gemini")
        `when`(settings.getActiveModel("Gemini")).thenReturn("gemini-test")
        `when`(settings.getBaseProviderType("Gemini")).thenReturn("Gemini")
        `when`(settings.getEndpoints("Gemini")).thenReturn(
            listOf(LlmEndpoint("ep_1", "Gemini", "Primary", "https://example.test", enabled = true))
        )
        `when`(settings.getApiKeyForEndpoint("ep_1")).thenReturn("secret")
        `when`(settings.getUseBackupApi()).thenReturn(false)
        return settings
    }

    @Test
    fun extractTextWithVisionStreamingStripsThinkTagsAndTrimsOutput() = runBlocking {
        val settings = baseSettings()
        val extractor = ImageTextExtractorImpl(
            ocrRepository = object : OcrRepository {
                override suspend fun extractTextFromImage(imageUri: String): String = ""
            },
            imageAttachmentLoader = object : ImageAttachmentLoader {
                override suspend fun loadAsBase64(uriString: String): ImagePayload? = null
            },
            llmRepository = FakeLlmRepository(streamText = "<think>checking chars</think>  これは画像テストです。 \n\n"),
            settingsRepository = settings,
            endpointPoolResolver = LlmEndpointPoolResolver(settings, InMemoryEndpointHealthTracker())
        )

        val config = LlmApiConfig("Gemini", "Gemini", "gemini-test", "https://example.test", "secret", "ep_1", "Primary")
        val chunk = extractor.extractTextWithVisionStreaming(
            imageBase64 = "dummy",
            mimeType = "image/png",
            primaryConfigs = listOf(config),
            backupConfigs = emptyList()
        ).last()

        assertEquals("これは画像テストです。", chunk.text)
        assertEquals(15, chunk.metadata.consumedTokens)
    }

    @Test
    fun extractTextWithVisionStreamingStripsCodeFenceAndOutputWrapper() = runBlocking {
        val settings = baseSettings()
        val extractor = ImageTextExtractorImpl(
            ocrRepository = object : OcrRepository {
                override suspend fun extractTextFromImage(imageUri: String): String = ""
            },
            imageAttachmentLoader = object : ImageAttachmentLoader {
                override suspend fun loadAsBase64(uriString: String): ImagePayload? = null
            },
            llmRepository = FakeLlmRepository(streamText = "```text\n<output>\n腹減ってたのを食わせてやっただけっすよ\n</output>\n```"),
            settingsRepository = settings,
            endpointPoolResolver = LlmEndpointPoolResolver(settings, InMemoryEndpointHealthTracker())
        )

        val config = LlmApiConfig("Gemini", "Gemini", "gemini-test", "https://example.test", "secret", "ep_1", "Primary")
        val chunk = extractor.extractTextWithVisionStreaming(
            imageBase64 = "dummy",
            mimeType = "image/png",
            primaryConfigs = listOf(config),
            backupConfigs = emptyList()
        ).last()

        assertEquals("腹減ってたのを食わせてやっただけっすよ", chunk.text)
    }

    @Test
    fun extractTextPreferVisionWithLocalFallbackUsesLocalOcrWhenVisionFailsAndCleansSpaces() = runBlocking {
        val settings = baseSettings(removeSpaces = true)
        val extractor = ImageTextExtractorImpl(
            ocrRepository = object : OcrRepository {
                override suspend fun extractTextFromImage(imageUri: String): String = "猫 が 走る"
            },
            imageAttachmentLoader = object : ImageAttachmentLoader {
                override suspend fun loadAsBase64(uriString: String): ImagePayload =
                    ImagePayload("base64data", "image/jpeg")
            },
            llmRepository = FakeLlmRepository(shouldFail = true),
            settingsRepository = settings,
            endpointPoolResolver = LlmEndpointPoolResolver(settings, InMemoryEndpointHealthTracker())
        )

        val result = extractor.extractTextPreferVisionWithLocalFallback("file://sample.jpg")
        assertEquals("猫が走る", result)
    }
}
