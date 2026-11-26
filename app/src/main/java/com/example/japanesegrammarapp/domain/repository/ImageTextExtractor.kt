package com.example.japanesegrammarapp.domain.repository

import kotlinx.coroutines.flow.Flow

data class ImageOcrStreamChunk(
    val text: String,
    val metadata: LlmResultMetadata
)

interface ImageTextExtractor {
    suspend fun extractTextLocal(
        imageUri: String,
        cleanSpaces: Boolean = true
    ): String

    fun extractTextWithVisionStreaming(
        imageBase64: String,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit = {},
        onBackup: (backupProvider: String) -> Unit = {},
        recordId: Int? = null,
        stepName: String? = null
    ): Flow<ImageOcrStreamChunk>

    suspend fun extractTextPreferVisionWithLocalFallback(
        imageUri: String,
        stepName: String = "PHOTO_INPUT_OCR"
    ): String
}
