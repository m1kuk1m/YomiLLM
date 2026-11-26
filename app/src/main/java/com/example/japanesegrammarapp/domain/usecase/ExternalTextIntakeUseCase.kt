package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.AnalysisStatus
import com.example.japanesegrammarapp.domain.repository.AppLogWriter
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ExternalTextIntakeResult {
    data object Ignored : ExternalTextIntakeResult
    data class QueuedSilently(val trimmedText: String) : ExternalTextIntakeResult
    data class OpenInWorkspace(val trimmedText: String) : ExternalTextIntakeResult
}

@Singleton
class ExternalTextIntakeUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val saveAnalysisRecordUseCase: SaveAnalysisRecordUseCase,
    private val analyzeTextUseCase: AnalyzeTextUseCase,
    private val appLogWriter: AppLogWriter
) {
    suspend fun handle(rawText: String?): ExternalTextIntakeResult = withContext(Dispatchers.IO) {
        val trimmedText = rawText?.trim().orEmpty()
        if (trimmedText.isBlank()) {
            return@withContext ExternalTextIntakeResult.Ignored
        }

        val existingRecord = saveAnalysisRecordUseCase.getByOriginalText(trimmedText)
        val isAlreadyAnalyzed = existingRecord != null && existingRecord.status == AnalysisStatus.COMPLETED
        val silentMode = settingsRepository.getSilentBackgroundMode()

        if (!isAlreadyAnalyzed && silentMode) {
            val provider = settingsRepository.getActiveProvider()
            val savedModel = settingsRepository.getActiveModel(provider)
            val model = savedModel.ifBlank {
                settingsRepository.getModelsForProvider(provider).firstOrNull() ?: "default"
            }
            val key = settingsRepository.getApiKey(provider)
            val url = settingsRepository.getApiUrl(provider)

            try {
                analyzeTextUseCase.execute(trimmedText, null, provider, model, url, key)
                return@withContext ExternalTextIntakeResult.QueuedSilently(trimmedText)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appLogWriter.error("EXTERNAL_RECEIVER", "Silent background analysis failed to start", e)
                return@withContext ExternalTextIntakeResult.OpenInWorkspace(trimmedText)
            }
        }

        ExternalTextIntakeResult.OpenInWorkspace(trimmedText)
    }
}
