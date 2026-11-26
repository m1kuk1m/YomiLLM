package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.AnalysisModule
import com.example.japanesegrammarapp.domain.model.LlmStreamEvent
import com.example.japanesegrammarapp.domain.repository.ImageAttachmentLoader
import com.example.japanesegrammarapp.domain.repository.ImageOcrStreamChunk
import com.example.japanesegrammarapp.domain.repository.ImageTextExtractor
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.LlmRepository
import com.example.japanesegrammarapp.domain.repository.LlmResultMetadata
import com.example.japanesegrammarapp.domain.repository.OcrRepository
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.domain.usecase.LlmEndpointPoolResolver
import com.example.japanesegrammarapp.utils.AppLogger
import com.example.japanesegrammarapp.utils.LlmResponseSanitizer
import com.example.japanesegrammarapp.utils.TextCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageTextExtractorImpl @Inject constructor(
    private val ocrRepository: OcrRepository,
    private val imageAttachmentLoader: ImageAttachmentLoader,
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val endpointPoolResolver: LlmEndpointPoolResolver
) : ImageTextExtractor {

    private companion object {
        const val VISION_OCR_TIMEOUT_MS = 150_000L
        const val IMAGE_OCR_USER_PROMPT =
            "画像内の日本語本文を視覚的な読み順に従って忠実に書き起こし、本文のプレーンテキストのみを出力してください。"
    }

    override suspend fun extractTextLocal(imageUri: String, cleanSpaces: Boolean): String {
        val raw = ocrRepository.extractTextFromImage(imageUri).trim()
        return if (cleanSpaces && settingsRepository.getRemoveAccidentalSpaces()) {
            TextCleaner.removeAccidentalSpaces(raw)
        } else {
            raw
        }
    }

    override fun extractTextWithVisionStreaming(
        imageBase64: String,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?
    ): Flow<ImageOcrStreamChunk> = flow {
        val apiTypeLabel = AnalysisModule.IMAGE_OCR.id
        val systemPrompt = settingsRepository.getCustomPrompt(AnalysisModule.IMAGE_OCR.promptKey)
        val userPrompt = IMAGE_OCR_USER_PROMPT

        var providerLabel = buildString {
            append(primaryConfigs.firstOrNull()?.provider ?: "Main API")
            backupConfigs.firstOrNull()?.provider?.let { append(" -> ").append(it) }
        }
        var modelLabel = buildString {
            append(primaryConfigs.firstOrNull()?.modelName ?: "Unknown")
            backupConfigs.firstOrNull()?.modelName?.let { append(" -> ").append(it) }
        }

        val stepStartMs = System.currentTimeMillis()
        var accumulatedText = ""
        var accumulatedThoughtText = ""
        var currentMetadata = LlmResultMetadata(0, 0, 0)

        try {
            withTimeout(VISION_OCR_TIMEOUT_MS) {
                llmRepository.executeWithStreaming(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    imageBase64 = imageBase64,
                    mimeType = mimeType,
                    apiTypeLabel = apiTypeLabel,
                    primaryConfigs = primaryConfigs,
                    backupConfigs = backupConfigs,
                    recordId = recordId,
                    stepName = stepName,
                    onRetry = onRetry,
                    onBackup = onBackup
                ).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Chunk -> {
                            accumulatedText += event.text
                            val cleanText = LlmResponseSanitizer.cleanModelOutput(accumulatedText)
                            emit(ImageOcrStreamChunk(cleanText, currentMetadata))
                        }
                        is LlmStreamEvent.ThoughtChunk -> {
                            accumulatedThoughtText += event.text
                        }
                        is LlmStreamEvent.Metadata -> {
                            providerLabel = event.provider
                            modelLabel = event.modelName
                            currentMetadata = event.usage
                            val cleanText = LlmResponseSanitizer.cleanModelOutput(accumulatedText)
                            emit(ImageOcrStreamChunk(cleanText, currentMetadata))
                        }
                    }
                }
            }

            val extractedThink = LlmResponseSanitizer.THINKING_REGEX.findAll(accumulatedText)
                .map { it.groupValues.getOrNull(1)?.trim() ?: "" }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()
            val finalRawResponse = LlmResponseSanitizer.cleanModelOutput(accumulatedText)

            val finalReasoningContent = when {
                accumulatedThoughtText.isNotBlank() && extractedThink.isNotBlank() ->
                    "$accumulatedThoughtText\n$extractedThink"
                accumulatedThoughtText.isNotBlank() -> accumulatedThoughtText
                extractedThink.isNotBlank() -> extractedThink
                currentMetadata.reasoningTokens > 0 ->
                    "[モデルは内部推論を行いました (${currentMetadata.reasoningTokens} tokens 消費)。このモデルはAPIに思考テキストを返しません]"
                else -> null
            }

            val reasoningLevel = settingsRepository.getEffectiveReasoningLevel(apiTypeLabel).name
            AppLogger.apiSuccess(
                apiTypeLabel = apiTypeLabel,
                provider = providerLabel,
                model = modelLabel,
                hasImage = true,
                userPrompt = userPrompt,
                systemPrompt = systemPrompt,
                rawResponse = finalRawResponse,
                parsedPreview = finalRawResponse.take(100).replace("\n", " "),
                consumedTokens = currentMetadata.consumedTokens,
                inputTokens = currentMetadata.inputTokens,
                outputTokens = currentMetadata.outputTokens,
                reasoningContent = finalReasoningContent,
                reasoningTokens = currentMetadata.reasoningTokens,
                reasoningLevel = reasoningLevel,
                recordId = recordId,
                stepName = stepName,
                elapsedMs = System.currentTimeMillis() - stepStartMs
            )
        } catch (e: TimeoutCancellationException) {
            val elapsedMs = System.currentTimeMillis() - stepStartMs
            val message = "Step timed out after ${VISION_OCR_TIMEOUT_MS / 1000}s"
            val cleanRawResponse = LlmResponseSanitizer.cleanModelOutput(accumulatedText)
            val reasoningLevel = settingsRepository.getEffectiveReasoningLevel(apiTypeLabel).name
            AppLogger.apiError(
                apiTypeLabel = apiTypeLabel,
                provider = providerLabel,
                model = modelLabel,
                hasImage = true,
                userPrompt = userPrompt,
                systemPrompt = systemPrompt,
                message = message,
                throwable = e,
                rawResponse = cleanRawResponse,
                reasoningLevel = reasoningLevel,
                recordId = recordId,
                stepName = stepName,
                elapsedMs = elapsedMs
            )
            throw Exception(message, e)
        } catch (e: retrofit2.HttpException) {
            val body = e.response()?.errorBody()?.string() ?: ""
            val safeBody = if (body.length > 200) body.take(200) + "..." else body
            val message = "HTTP ${e.code()}: ${e.message()}\n$safeBody"
            val reasoningLevel = settingsRepository.getEffectiveReasoningLevel(apiTypeLabel).name
            AppLogger.apiError(
                apiTypeLabel = apiTypeLabel,
                provider = providerLabel,
                model = modelLabel,
                hasImage = true,
                userPrompt = userPrompt,
                systemPrompt = systemPrompt,
                message = message,
                throwable = e,
                rawResponse = body,
                reasoningLevel = reasoningLevel,
                recordId = recordId,
                stepName = stepName,
                elapsedMs = System.currentTimeMillis() - stepStartMs
            )
            throw Exception(message, e)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val cleanRawResponse = LlmResponseSanitizer.cleanModelOutput(accumulatedText)
            val reasoningLevel = settingsRepository.getEffectiveReasoningLevel(apiTypeLabel).name
            AppLogger.apiError(
                apiTypeLabel = apiTypeLabel,
                provider = providerLabel,
                model = modelLabel,
                hasImage = true,
                userPrompt = userPrompt,
                systemPrompt = systemPrompt,
                message = e.localizedMessage ?: "Unknown LLM step error",
                throwable = e,
                rawResponse = cleanRawResponse,
                reasoningLevel = reasoningLevel,
                recordId = recordId,
                stepName = stepName,
                elapsedMs = System.currentTimeMillis() - stepStartMs
            )
            throw e
        }
    }

    override suspend fun extractTextPreferVisionWithLocalFallback(
        imageUri: String,
        stepName: String
    ): String {
        val payload = try {
            imageAttachmentLoader.loadAsBase64(imageUri)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e("IMAGE_OCR", "Failed to load base64 from $imageUri", e)
            null
        }

        if (payload != null && payload.base64Data.isNotBlank()) {
            val primaryConfigs = endpointPoolResolver.resolveModuleConfigs(AnalysisModule.IMAGE_OCR)
            val backupConfigs = endpointPoolResolver.resolveBackupConfigs()

            if (primaryConfigs.isNotEmpty()) {
                try {
                    var recognized = ""
                    extractTextWithVisionStreaming(
                        imageBase64 = payload.base64Data,
                        mimeType = payload.mimeType,
                        primaryConfigs = primaryConfigs,
                        backupConfigs = backupConfigs,
                        recordId = null,
                        stepName = stepName
                    ).collect { chunk ->
                        if (chunk.text.isNotBlank()) {
                            recognized = chunk.text
                        }
                    }

                    var clean = recognized.trim()
                    if (clean.isNotBlank()) {
                        if (settingsRepository.getRemoveAccidentalSpaces()) {
                            clean = TextCleaner.removeAccidentalSpaces(clean)
                        }
                        return clean
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLogger.e("IMAGE_OCR", "Vision LLM OCR failed, falling back to local OCR", e)
                }
            }
        }

        return try {
            extractTextLocal(imageUri, cleanSpaces = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e("IMAGE_OCR", "Local OCR fallback also failed", e)
            ""
        }
    }
}
