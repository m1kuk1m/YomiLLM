package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.AnalysisDomainRecord
import com.example.japanesegrammarapp.domain.model.AnalysisStatus
import com.example.japanesegrammarapp.domain.model.DailyTokenUsage
import com.example.japanesegrammarapp.domain.model.DetailedAnalysisResult
import com.example.japanesegrammarapp.domain.model.HistoryExportPreview
import com.example.japanesegrammarapp.domain.model.ModelTokenUsage
import com.example.japanesegrammarapp.domain.repository.AppLogWriter
import com.example.japanesegrammarapp.domain.repository.HistoryRepository
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ExternalTextIntakeUseCaseTest {

    private class FakeTaskManager(
        private val shouldThrow: Boolean = false
    ) : AnalysisTaskManager {
        override val progressFlow: StateFlow<Map<Int, AnalysisProgress>> = MutableStateFlow(emptyMap())
        val executedTexts = mutableListOf<String>()

        override suspend fun execute(
            text: String,
            imageUri: String?,
            provider: String,
            modelName: String,
            baseUrl: String,
            apiKey: String
        ): Int {
            if (shouldThrow) throw IllegalArgumentException("Missing API Key.")
            executedTexts.add(text)
            return 1
        }

        override suspend fun executeRetry(recordId: Int, text: String, imageUri: String?) = Unit
        override fun cancel(recordId: Int) = Unit
        override fun parseDetailedResult(originalText: String, jsonString: String?): DetailedAnalysisResult? = null
    }

    private class StubHistoryRepository(
        private val existingByText: Map<String, AnalysisDomainRecord> = emptyMap()
    ) : HistoryRepository {
        override val totalTokensConsumed: Flow<Int?> = emptyFlow()
        override val tokenUsageByModel: Flow<List<ModelTokenUsage>> = emptyFlow()
        override val dailyTokenUsage: Flow<List<DailyTokenUsage>> = emptyFlow()
        override suspend fun getAllRecordsList(): List<AnalysisDomainRecord> = emptyList()
        override suspend fun getAllExportPreviews(): List<HistoryExportPreview> = emptyList()
        override suspend fun getRecordsByIds(ids: List<Int>): List<AnalysisDomainRecord> = emptyList()
        override suspend fun getRecordById(id: Int): AnalysisDomainRecord? = null
        override fun observeRecordById(id: Int): Flow<AnalysisDomainRecord?> = emptyFlow()
        override suspend fun getRecordByOriginalText(originalText: String): AnalysisDomainRecord? = existingByText[originalText]
        override suspend fun getNewerRecord(timestamp: Long): AnalysisDomainRecord? = null
        override suspend fun getOlderRecord(timestamp: Long): AnalysisDomainRecord? = null
        override suspend fun insertRecord(record: AnalysisDomainRecord): Long = 1L
        override suspend fun updateRecord(record: AnalysisDomainRecord) = Unit
        override suspend fun deleteRecord(record: AnalysisDomainRecord) = Unit
        override suspend fun markRecordAsRead(id: Int) = Unit
    }

    private object NoOpLogger : AppLogWriter {
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun stubSettings(silentMode: Boolean): SettingsRepository {
        val delegate = mock(SettingsRepository::class.java)
        return object : SettingsRepository by delegate {
            override fun getSilentBackgroundMode(): Boolean = silentMode
            override fun getActiveProvider(): String = "Gemini"
            override fun getActiveModel(provider: String): String = "gemini-2.5-flash"
            override fun getModelsForProvider(provider: String): List<String> = listOf("gemini-2.5-flash")
            override fun getApiKey(provider: String): String = "secret"
            override fun getApiUrl(provider: String): String = "https://example.test"
        }
    }

    @Test
    fun blankTextReturnsIgnored() = runBlocking {
        val taskManager = FakeTaskManager()
        val useCase = ExternalTextIntakeUseCase(
            settingsRepository = stubSettings(silentMode = true),
            saveAnalysisRecordUseCase = SaveAnalysisRecordUseCase(StubHistoryRepository()),
            analyzeTextUseCase = AnalyzeTextUseCase(taskManager),
            appLogWriter = NoOpLogger
        )

        assertEquals(ExternalTextIntakeResult.Ignored, useCase.handle("   \n  "))
        assertEquals(emptyList<String>(), taskManager.executedTexts)
    }

    @Test
    fun newSentenceInSilentModeQueuesBackgroundAnalysis() = runBlocking {
        val taskManager = FakeTaskManager()
        val useCase = ExternalTextIntakeUseCase(
            settingsRepository = stubSettings(silentMode = true),
            saveAnalysisRecordUseCase = SaveAnalysisRecordUseCase(StubHistoryRepository()),
            analyzeTextUseCase = AnalyzeTextUseCase(taskManager),
            appLogWriter = NoOpLogger
        )

        val result = useCase.handle("  猫が走る。 ")
        assertEquals(ExternalTextIntakeResult.QueuedSilently("猫が走る。"), result)
        assertEquals(listOf("猫が走る。"), taskManager.executedTexts)
    }

    @Test
    fun alreadyCompletedSentenceOpensWorkspaceEvenInSilentMode() = runBlocking {
        val existing = AnalysisDomainRecord(
            id = 7,
            originalText = "猫が走る。",
            modelUsed = "Gemini: gemini-2.5-flash",
            status = AnalysisStatus.COMPLETED
        )
        val taskManager = FakeTaskManager()
        val useCase = ExternalTextIntakeUseCase(
            settingsRepository = stubSettings(silentMode = true),
            saveAnalysisRecordUseCase = SaveAnalysisRecordUseCase(StubHistoryRepository(mapOf("猫が走る。" to existing))),
            analyzeTextUseCase = AnalyzeTextUseCase(taskManager),
            appLogWriter = NoOpLogger
        )

        val result = useCase.handle("猫が走る。")
        assertEquals(ExternalTextIntakeResult.OpenInWorkspace("猫が走る。"), result)
        assertEquals(emptyList<String>(), taskManager.executedTexts)
    }

    @Test
    fun silentModeStartFailureFallsBackToOpenInWorkspace() = runBlocking {
        val taskManager = FakeTaskManager(shouldThrow = true)
        val useCase = ExternalTextIntakeUseCase(
            settingsRepository = stubSettings(silentMode = true),
            saveAnalysisRecordUseCase = SaveAnalysisRecordUseCase(StubHistoryRepository()),
            analyzeTextUseCase = AnalyzeTextUseCase(taskManager),
            appLogWriter = NoOpLogger
        )

        val result = useCase.handle("猫が走る。")
        assertEquals(ExternalTextIntakeResult.OpenInWorkspace("猫が走る。"), result)
    }

    @Test
    fun nonSilentModeOpensWorkspaceDirectly() = runBlocking {
        val taskManager = FakeTaskManager()
        val useCase = ExternalTextIntakeUseCase(
            settingsRepository = stubSettings(silentMode = false),
            saveAnalysisRecordUseCase = SaveAnalysisRecordUseCase(StubHistoryRepository()),
            analyzeTextUseCase = AnalyzeTextUseCase(taskManager),
            appLogWriter = NoOpLogger
        )

        val result = useCase.handle("猫が走る。")
        assertEquals(ExternalTextIntakeResult.OpenInWorkspace("猫が走る。"), result)
        assertEquals(emptyList<String>(), taskManager.executedTexts)
    }
}
