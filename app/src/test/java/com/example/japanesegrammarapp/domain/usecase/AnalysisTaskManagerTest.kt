package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.AnalysisDomainRecord
import com.example.japanesegrammarapp.domain.model.AnalysisStatus
import com.example.japanesegrammarapp.domain.model.AnalysisStep
import com.example.japanesegrammarapp.domain.model.DailyTokenUsage
import com.example.japanesegrammarapp.domain.model.DetailedAnalysisResult
import com.example.japanesegrammarapp.domain.model.DetailedGrammarPoint
import com.example.japanesegrammarapp.domain.model.LlmConfig
import com.example.japanesegrammarapp.domain.model.LlmEndpoint
import com.example.japanesegrammarapp.domain.model.ModelTokenUsage
import com.example.japanesegrammarapp.domain.model.OcrBoxDetectionSettings
import com.example.japanesegrammarapp.domain.model.RegeneratableModule
import com.example.japanesegrammarapp.domain.model.SentenceClause
import com.example.japanesegrammarapp.domain.model.TokenizationResult
import com.example.japanesegrammarapp.domain.model.WordSegment
import com.google.gson.Gson
import com.example.japanesegrammarapp.domain.repository.DetailedResultSerializer
import com.example.japanesegrammarapp.domain.repository.HistoryRepository
import com.example.japanesegrammarapp.domain.repository.ImageAttachmentLoader
import com.example.japanesegrammarapp.domain.repository.ImageOcrStreamChunk
import com.example.japanesegrammarapp.domain.repository.ImagePayload
import com.example.japanesegrammarapp.domain.repository.ImageTextExtractor
import com.example.japanesegrammarapp.domain.repository.LlmAnalysisService
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.LlmResultMetadata
import com.example.japanesegrammarapp.domain.repository.OcrRepository
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.domain.repository.AppLogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTaskManagerTest {
    @Test
    fun executeReturnsExistingIdWhenCompletedDuplicateExists() = runBlocking {
        val history = FakeHistoryRepository(
            AnalysisDomainRecord(
                id = 5,
                originalText = "日本語",
                modelUsed = "Gemini: gemini-test",
                status = AnalysisStatus.COMPLETED
            )
        )
        val llm = FakeLlmAnalysisService()
        val manager = newManager(history = history, llm = llm)

        val id = manager.execute(
            text = "日本語",
            imageUri = null,
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        assertEquals(5, id)
        assertEquals(0, llm.tokenizerCalls)
        assertTrue(manager.progressFlow.value.isEmpty())
    }

    @Test
    fun cancelDeletesPendingRecordAndClearsProgress() = runBlocking {
        val history = FakeHistoryRepository()
        val llm = FakeLlmAnalysisService(delayMs = 250)
        val manager = newManager(history = history, llm = llm)

        val id = manager.execute(
            text = "キャンセル",
            imageUri = null,
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        manager.cancel(id)
        waitUntil { history.getRecordById(id) == null && !manager.progressFlow.value.containsKey(id) }

        assertEquals(null, history.getRecordById(id))
        assertFalse(manager.progressFlow.value.containsKey(id))
    }

    @Test
    fun backgroundFailureMarksRecordFailedAndClearsProgress() = runBlocking {
        val history = FakeHistoryRepository()
        val manager = newManager(
            history = history,
            settings = FakeSettingsRepository(emptyConfigsAfterFirstLookup = true)
        )

        val id = manager.execute(
            text = "失敗",
            imageUri = null,
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.FAILED }
        val record = checkNotNull(history.getRecordById(id))

        assertEquals(AnalysisStatus.FAILED, record.status)
        assertTrue(record.errorMessage.orEmpty().contains("Missing API Key"))
        assertFalse(manager.progressFlow.value.containsKey(id))
    }

    @Test
    fun executeWithImageAttachmentPerformsImageOcrAndFeedsTextToDownstreamSteps() = runBlocking {
        val history = FakeHistoryRepository()
        val llm = FakeLlmAnalysisService()
        val imageTextExtractor = FakeImageTextExtractor(ocrResult = "画像から抽出した日本語")
        val imageLoader = FakeImageAttachmentLoader(ImagePayload("dummyBase64", "image/png"))
        val manager = newManager(
            history = history,
            llm = llm,
            imageTextExtractor = imageTextExtractor,
            imageLoader = imageLoader
        )

        val id = manager.execute(
            text = "",
            imageUri = "content://media/test_image",
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.COMPLETED }
        val record = checkNotNull(history.getRecordById(id))

        assertEquals(1, imageTextExtractor.ocrCalls)
        assertEquals(1, llm.tokenizerCalls)
        assertEquals("画像から抽出した日本語", llm.lastTokenizerText)
        assertEquals(null, llm.lastTokenizerImageBase64)
        assertEquals("画像から抽出した日本語", record.originalText)
        assertEquals(AnalysisStatus.COMPLETED, record.status)
        assertFalse(manager.progressFlow.value.containsKey(id))
    }

    @Test
    fun executeWithImageAttachmentFailsFastWhenImageOcrFails() = runBlocking {
        val history = FakeHistoryRepository()
        val llm = FakeLlmAnalysisService()
        val imageTextExtractor = FakeImageTextExtractor(failOcr = true)
        val imageLoader = FakeImageAttachmentLoader(ImagePayload("dummyBase64", "image/png"))
        val manager = newManager(
            history = history,
            llm = llm,
            imageTextExtractor = imageTextExtractor,
            imageLoader = imageLoader
        )

        val id = manager.execute(
            text = "",
            imageUri = "content://media/test_image",
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.FAILED }
        val record = checkNotNull(history.getRecordById(id))

        assertEquals(1, imageTextExtractor.ocrCalls)
        assertEquals(0, llm.tokenizerCalls)
        assertEquals(AnalysisStatus.FAILED, record.status)
        assertTrue(record.errorMessage.orEmpty().contains("image ocr failed"))
        assertFalse(manager.progressFlow.value.containsKey(id))
    }

    @Test
    fun executeWithImageAttachmentFailsFastWhenImageOcrReturnsEmptyText() = runBlocking {
        val history = FakeHistoryRepository()
        val llm = FakeLlmAnalysisService()
        val imageTextExtractor = FakeImageTextExtractor(ocrResult = "   ")
        val imageLoader = FakeImageAttachmentLoader(ImagePayload("dummyBase64", "image/png"))
        val manager = newManager(
            history = history,
            llm = llm,
            imageTextExtractor = imageTextExtractor,
            imageLoader = imageLoader
        )

        val id = manager.execute(
            text = "",
            imageUri = "content://media/test_image",
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.FAILED }
        val record = checkNotNull(history.getRecordById(id))

        assertEquals(1, imageTextExtractor.ocrCalls)
        assertEquals(0, llm.tokenizerCalls)
        assertEquals(AnalysisStatus.FAILED, record.status)
        assertTrue(record.errorMessage.orEmpty().contains("未能从图片中识别出有效日文内容"))
        assertFalse(manager.progressFlow.value.containsKey(id))
    }

    @Test
    fun executeWithImageAttachmentDeduplicatesWhenRecognizedTextMatchesExistingRecord() = runBlocking {
        val history = FakeHistoryRepository(
            AnalysisDomainRecord(
                id = 99,
                originalText = "既存テキスト",
                modelUsed = "Gemini: gemini-test",
                status = AnalysisStatus.COMPLETED
            )
        )
        val llm = FakeLlmAnalysisService()
        val imageTextExtractor = FakeImageTextExtractor(ocrResult = "既存テキスト")
        val imageLoader = FakeImageAttachmentLoader(ImagePayload("dummyBase64", "image/png"))
        val manager = newManager(
            history = history,
            llm = llm,
            imageTextExtractor = imageTextExtractor,
            imageLoader = imageLoader
        )

        val id = manager.execute(
            text = "",
            imageUri = "content://media/test_image",
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id) == null && !manager.progressFlow.value.containsKey(id) }

        assertEquals(1, imageTextExtractor.ocrCalls)
        assertEquals(0, llm.tokenizerCalls)
        assertEquals(AnalysisStatus.COMPLETED, history.getRecordById(99)?.status)
    }

    @Test
    fun executeUsesDecisionPosAndDecoupledSegmentsWhenEnabled() = runBlocking {
        val history = FakeHistoryRepository()
        val serializer = FakeDetailedResultSerializer()
        val decisionEndpointConfig = LlmApiConfig(
            provider = com.example.japanesegrammarapp.domain.model.DecisionConfig.PROVIDER_ID,
            baseProvider = com.example.japanesegrammarapp.domain.model.DecisionConfig.PROVIDER_ID,
            modelName = "gemini-decision-custom",
            url = "https://decisions.example.test",
            key = "dec-key",
            endpointId = "dec_1",
            endpointName = "Decisions"
        )
        val settings = FakeSettingsRepository(
            decisionApiEnabled = true,
            decisionModel = "gemini-decision-custom",
            decisionConfigs = listOf(decisionEndpointConfig)
        )
        val llm = FakeLlmAnalysisService(
            segmentDelayMs = 40,
            segmentsResult = listOf(
                com.example.japanesegrammarapp.domain.model.WordSegment(text = "猫", reading = "ねこ", partOfSpeech = "", meaning = "猫", role = "主语"),
                com.example.japanesegrammarapp.domain.model.WordSegment(text = "が", reading = "が", partOfSpeech = "", meaning = "主格", role = "助词")
            )
        )
        val decisionRepo = FakeDecisionRepository(
            resultSegments = listOf(
                com.example.japanesegrammarapp.domain.model.WordSegment(text = "猫", partOfSpeech = "名詞", posCategory = "NOUN"),
                com.example.japanesegrammarapp.domain.model.WordSegment(text = "が", partOfSpeech = "助詞", posCategory = "PARTICLE")
            )
        )
        val manager = newManager(
            history = history,
            llm = llm,
            settings = settings,
            decisionRepository = decisionRepo,
            serializer = serializer
        )

        val id = manager.execute(
            text = "猫が",
            imageUri = null,
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.COMPLETED }

        assertEquals(1, decisionRepo.classifyCalls)
        assertEquals("gemini-decision-custom", decisionRepo.lastConfigs.first().modelName)
        assertEquals(true, llm.lastDecoupledPosMode)
        val finalSegments = checkNotNull(serializer.lastSerialized).segments.orEmpty()
        assertEquals(listOf("NOUN", "PARTICLE"), finalSegments.map { it.posCategory })
        assertEquals(listOf("名詞", "助詞"), finalSegments.map { it.partOfSpeech })
        assertEquals(listOf("猫", "主格"), finalSegments.map { it.meaning })
    }

    @Test
    fun executeFallsBackCleanlyWhenDecisionApiFails() = runBlocking {
        val history = FakeHistoryRepository()
        val serializer = FakeDetailedResultSerializer()
        val settings = FakeSettingsRepository(
            decisionApiEnabled = true,
            decisionConfigs = listOf(
                LlmApiConfig(
                    provider = com.example.japanesegrammarapp.domain.model.DecisionConfig.PROVIDER_ID,
                    baseProvider = com.example.japanesegrammarapp.domain.model.DecisionConfig.PROVIDER_ID,
                    modelName = com.example.japanesegrammarapp.domain.model.DecisionConfig.DEFAULT_MODEL,
                    url = "https://decisions.example.test",
                    key = "dec-key"
                )
            )
        )
        val llm = FakeLlmAnalysisService(
            segmentsResult = listOf(
                com.example.japanesegrammarapp.domain.model.WordSegment(text = "猫", reading = "ねこ", partOfSpeech = "", meaning = "猫", role = "主语")
            )
        )
        val decisionRepo = FakeDecisionRepository(shouldFail = true)
        val manager = newManager(
            history = history,
            llm = llm,
            settings = settings,
            decisionRepository = decisionRepo,
            serializer = serializer
        )

        val id = manager.execute(
            text = "猫",
            imageUri = null,
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.COMPLETED }

        assertEquals(1, decisionRepo.classifyCalls)
        assertEquals(AnalysisStatus.COMPLETED, history.getRecordById(id)?.status)
        val finalSegments = checkNotNull(serializer.lastSerialized).segments.orEmpty()
        assertEquals(1, finalSegments.size)
        assertEquals("猫", finalSegments.first().meaning)
    }

    @Test
    fun executeSkipsDecisionApiWhenDisabled() = runBlocking {
        val history = FakeHistoryRepository()
        val decisionRepo = FakeDecisionRepository()
        val llm = FakeLlmAnalysisService()
        val manager = newManager(
            history = history,
            llm = llm,
            settings = FakeSettingsRepository(decisionApiEnabled = false),
            decisionRepository = decisionRepo
        )

        val id = manager.execute(
            text = "猫",
            imageUri = null,
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.COMPLETED }

        assertEquals(0, decisionRepo.classifyCalls)
        assertEquals(false, llm.lastDecoupledPosMode)
    }

    @Test
    fun fullAnalysisPersistsModuleErrorWhenTranslationFails() = runBlocking {
        val history = FakeHistoryRepository()
        val serializer = FakeDetailedResultSerializer()
        val llm = FakeLlmAnalysisService(
            failTranslation = true,
            clausesResult = listOf(SentenceClause(1, "主語", "猫が", "主語を示す")),
            grammarResult = listOf(DetailedGrammarPoint("〜が", "主格助詞"))
        )
        val manager = newManager(history = history, llm = llm, serializer = serializer)

        val id = manager.execute(
            text = "猫が走る",
            imageUri = null,
            provider = "Gemini",
            modelName = "gemini-test",
            baseUrl = "https://example.test",
            apiKey = "key"
        )

        waitUntil { history.getRecordById(id)?.status == AnalysisStatus.COMPLETED }
        val stored = checkNotNull(serializer.fromJson(history.getRecordById(id)?.analysisResult))

        assertEquals(null, stored.translation)
        assertTrue(stored.moduleErrors?.get(AnalysisStep.TRANSLATION.name).orEmpty().contains("translation failed"))
        assertEquals(1, stored.clauses?.size)
        assertEquals(1, stored.grammarPoints?.size)
    }

    @Test
    fun regenerateModuleTranslationOnlyUpdatesTranslationClearsErrorAndAddsTokens() = runBlocking {
        val serializer = FakeDetailedResultSerializer()
        val initialDetail = DetailedAnalysisResult(
            translation = null,
            segments = listOf(WordSegment(text = "猫", reading = "ねこ", meaning = "猫")),
            clauses = listOf(SentenceClause(1, "主語", "猫", "主語")),
            grammarPoints = listOf(DetailedGrammarPoint("猫", "名詞")),
            moduleErrors = mapOf(AnalysisStep.TRANSLATION.name to "Gemini API Blocked"),
            consumedTokens = 10,
            inputTokens = 6,
            outputTokens = 4
        )
        val history = FakeHistoryRepository(
            AnalysisDomainRecord(
                id = 7,
                originalText = "猫が走る",
                analysisResult = serializer.toJson(initialDetail),
                modelUsed = "Gemini: gemini-test",
                status = AnalysisStatus.COMPLETED,
                consumedTokens = 10,
                inputTokens = 6,
                outputTokens = 4
            )
        )
        val llm = FakeLlmAnalysisService(
            translationResult = "猫在跑",
            tokenMeta = LlmResultMetadata(consumedTokens = 5, inputTokens = 3, outputTokens = 2)
        )
        val manager = newManager(history = history, llm = llm, serializer = serializer)

        manager.regenerateModule(7, RegeneratableModule.TRANSLATION)
        waitUntil { manager.regeneratingModulesFlow.value[7].isNullOrEmpty() && llm.translationCalls == 1 }

        val updatedRecord = checkNotNull(history.getRecordById(7))
        val updatedDetail = checkNotNull(serializer.fromJson(updatedRecord.analysisResult))

        assertEquals(AnalysisStatus.COMPLETED, updatedRecord.status)
        assertEquals(1, llm.translationCalls)
        assertEquals(0, llm.tokenizerCalls)
        assertEquals(0, llm.clausesCalls)
        assertEquals(0, llm.grammarCalls)
        assertEquals(0, llm.segmentsCalls)
        assertEquals("猫在跑", updatedDetail.translation)
        assertEquals(null, updatedDetail.moduleErrors)
        assertEquals("猫", updatedDetail.segments?.single()?.text)
        assertEquals(1, updatedDetail.clauses?.size)
        assertEquals(1, updatedDetail.grammarPoints?.size)
        assertEquals(15, updatedRecord.consumedTokens)
        assertEquals(9, updatedRecord.inputTokens)
        assertEquals(6, updatedRecord.outputTokens)
    }

    @Test
    fun regenerateModuleConcurrentTranslationAndGrammarDoNotOverwriteEachOther() = runBlocking {
        val serializer = FakeDetailedResultSerializer()
        val initialDetail = DetailedAnalysisResult(
            translation = "旧翻译",
            segments = listOf(WordSegment(text = "雨", reading = "あめ", meaning = "雨")),
            clauses = listOf(SentenceClause(1, "主語", "雨", "主語")),
            grammarPoints = emptyList(),
            moduleErrors = mapOf(
                AnalysisStep.TRANSLATION.name to "err1",
                AnalysisStep.GRAMMAR_EXPLANATION.name to "err2"
            ),
            consumedTokens = 10,
            inputTokens = 5,
            outputTokens = 5
        )
        val history = FakeHistoryRepository(
            AnalysisDomainRecord(
                id = 8,
                originalText = "雨が降る",
                analysisResult = serializer.toJson(initialDetail),
                modelUsed = "Gemini: gemini-test",
                status = AnalysisStatus.COMPLETED,
                consumedTokens = 10,
                inputTokens = 5,
                outputTokens = 5
            )
        )
        val llm = FakeLlmAnalysisService(
            delayMs = 40,
            translationResult = "下雨了",
            grammarResult = listOf(DetailedGrammarPoint("雨が降る", "下雨表达")),
            tokenMeta = LlmResultMetadata(consumedTokens = 4, inputTokens = 2, outputTokens = 2)
        )
        val manager = newManager(history = history, llm = llm, serializer = serializer)

        manager.regenerateModule(8, RegeneratableModule.TRANSLATION)
        manager.regenerateModule(8, RegeneratableModule.GRAMMAR)

        waitUntil {
            llm.translationCalls == 1 &&
                llm.grammarCalls == 1 &&
                manager.regeneratingModulesFlow.value[8].isNullOrEmpty()
        }

        val updatedRecord = checkNotNull(history.getRecordById(8))
        val updatedDetail = checkNotNull(serializer.fromJson(updatedRecord.analysisResult))

        assertEquals("下雨了", updatedDetail.translation)
        assertEquals("雨が降る", updatedDetail.grammarPoints?.single()?.pattern)
        assertEquals(null, updatedDetail.moduleErrors)
        assertEquals(18, updatedRecord.consumedTokens)
    }

    @Test
    fun regenerateModuleFailurePreservesExistingContentAndPersistsError() = runBlocking {
        val serializer = FakeDetailedResultSerializer()
        val initialDetail = DetailedAnalysisResult(
            translation = "保留的旧翻译",
            segments = listOf(WordSegment(text = "空", reading = "そら", meaning = "天空"))
        )
        val history = FakeHistoryRepository(
            AnalysisDomainRecord(
                id = 9,
                originalText = "空が青い",
                analysisResult = serializer.toJson(initialDetail),
                modelUsed = "Gemini: gemini-test",
                status = AnalysisStatus.COMPLETED
            )
        )
        val llm = FakeLlmAnalysisService(failTranslation = true)
        val manager = newManager(history = history, llm = llm, serializer = serializer)

        manager.regenerateModule(9, RegeneratableModule.TRANSLATION)
        waitUntil { llm.translationCalls == 1 && manager.regeneratingModulesFlow.value[9].isNullOrEmpty() }

        val updatedDetail = checkNotNull(serializer.fromJson(history.getRecordById(9)?.analysisResult))
        assertEquals("保留的旧翻译", updatedDetail.translation)
        assertTrue(updatedDetail.moduleErrors?.get(AnalysisStep.TRANSLATION.name).orEmpty().contains("translation failed"))
    }

    @Test
    fun regenerateModuleSegmentsOnlyReusesTokensWhileFullVocabularyRetokenizes() = runBlocking {
        val serializer = FakeDetailedResultSerializer()
        val initialDetail = DetailedAnalysisResult(
            translation = "猫在跑",
            segments = listOf(WordSegment(text = "猫"), WordSegment(text = "が"), WordSegment(text = "走る"))
        )
        val history = FakeHistoryRepository(
            AnalysisDomainRecord(
                id = 10,
                originalText = "猫が走る",
                analysisResult = serializer.toJson(initialDetail),
                modelUsed = "Gemini: gemini-test",
                status = AnalysisStatus.COMPLETED
            )
        )
        val llm = FakeLlmAnalysisService(
            segmentsResult = listOf(
                WordSegment(text = "猫", reading = "ねこ", meaning = "猫"),
                WordSegment(text = "が", reading = "が", meaning = "主格"),
                WordSegment(text = "走る", reading = "はしる", meaning = "跑")
            )
        )
        val manager = newManager(history = history, llm = llm, serializer = serializer)

        manager.regenerateModule(10, RegeneratableModule.SEGMENTS_ONLY)
        waitUntil { llm.segmentsCalls == 1 && manager.regeneratingModulesFlow.value[10].isNullOrEmpty() }

        assertEquals(0, llm.tokenizerCalls)
        assertEquals(listOf("猫", "が", "走る"), llm.lastSegmentsTokens)
        val afterSegmentsOnly = checkNotNull(serializer.fromJson(history.getRecordById(10)?.analysisResult))
        assertEquals("ねこ", afterSegmentsOnly.segments?.first()?.reading)

        manager.regenerateModule(10, RegeneratableModule.TOKENIZATION_AND_SEGMENTS)
        waitUntil { llm.tokenizerCalls == 1 && llm.segmentsCalls == 2 && manager.regeneratingModulesFlow.value[10].isNullOrEmpty() }
        assertEquals(1, llm.tokenizerCalls)
        assertEquals(2, llm.segmentsCalls)
    }

    private fun newManager(
        history: FakeHistoryRepository,
        llm: FakeLlmAnalysisService = FakeLlmAnalysisService(),
        imageTextExtractor: FakeImageTextExtractor = FakeImageTextExtractor(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        imageLoader: FakeImageAttachmentLoader = FakeImageAttachmentLoader(),
        decisionRepository: FakeDecisionRepository = FakeDecisionRepository(),
        serializer: FakeDetailedResultSerializer = FakeDetailedResultSerializer()
    ): DefaultAnalysisTaskManager {
        val eventBus = AnalysisEventBus()
        val endpointPoolResolver = LlmEndpointPoolResolver(
            settingsRepository = settings,
            endpointHealthTracker = com.example.japanesegrammarapp.data.repository.InMemoryEndpointHealthTracker()
        )
        return DefaultAnalysisTaskManager(
            saveAnalysisRecordUseCase = SaveAnalysisRecordUseCase(history),
            getOcrTextUseCase = GetOcrTextUseCase(
                ocrRepository = FakeOcrRepository(),
                imageLoader = imageLoader,
                eventBus = eventBus
            ),
            imageTextExtractor = imageTextExtractor,
            endpointPoolResolver = endpointPoolResolver,
            llmAnalysisService = llm,
            decisionRepository = decisionRepository,
            detailedResultSerializer = serializer,
            eventBus = eventBus,
            settingsRepository = settings,
            appLogWriter = NoOpAppLogWriter,
            repositoryScope = CoroutineScope(Dispatchers.Default)
        )
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) {
        repeat(80) {
            if (predicate()) return
            delay(25)
        }
        error("Condition was not met in time")
    }
}

private object NoOpAppLogWriter : AppLogWriter {
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

private class FakeImageTextExtractor(
    private val delayMs: Long = 0,
    private val failOcr: Boolean = false,
    private val ocrResult: String = "認識された日本語テキスト"
) : ImageTextExtractor {
    var ocrCalls = 0

    override suspend fun extractTextLocal(imageUri: String, cleanSpaces: Boolean): String = ocrResult

    override fun extractTextWithVisionStreaming(
        imageBase64: String,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?
    ): kotlinx.coroutines.flow.Flow<ImageOcrStreamChunk> = kotlinx.coroutines.flow.flow {
        ocrCalls++
        if (failOcr) error("image ocr failed")
        if (delayMs > 0) delay(delayMs)
        emit(ImageOcrStreamChunk(ocrResult, LlmResultMetadata(10, 6, 4)))
    }

    override suspend fun extractTextPreferVisionWithLocalFallback(
        imageUri: String,
        stepName: String
    ): String = ocrResult
}

private class FakeLlmAnalysisService(
    private val delayMs: Long = 0,
    private val failTranslation: Boolean = false,
    private val translationResult: String = "translated",
    private val clausesResult: List<SentenceClause> = emptyList(),
    private val grammarResult: List<DetailedGrammarPoint> = emptyList(),
    private val segmentsResult: List<com.example.japanesegrammarapp.domain.model.WordSegment> = emptyList(),
    private val segmentDelayMs: Long = 0,
    private val tokenMeta: LlmResultMetadata = LlmResultMetadata(consumedTokens = 1, inputTokens = 1, outputTokens = 0)
) : LlmAnalysisService {
    var tokenizerCalls = 0
    var translationCalls = 0
    var clausesCalls = 0
    var grammarCalls = 0
    var segmentsCalls = 0
    var lastTokenizerText: String? = null
    var lastTokenizerImageBase64: String? = null
    var lastDecoupledPosMode: Boolean? = null
    var lastSegmentsTokens: List<String> = emptyList()

    override suspend fun executeTokenizer(
        text: String,
        imageBase64: String?,
        mimeType: String?,
        isOcrMode: Boolean,
        imageTokenizerMode: String,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?
    ): kotlinx.coroutines.flow.Flow<Pair<TokenizationResult?, LlmResultMetadata>> = kotlinx.coroutines.flow.flow {
        tokenizerCalls++
        lastTokenizerText = text
        lastTokenizerImageBase64 = imageBase64
        if (delayMs > 0) delay(delayMs)
        emit(TokenizationResult(tokens = text.toList().map { it.toString() }) to metadata())
    }

    override suspend fun executeTranslation(
        text: String,
        imageBase64: String?,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?
    ): kotlinx.coroutines.flow.Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> = kotlinx.coroutines.flow.flow {
        translationCalls++
        if (failTranslation) error("translation failed")
        if (delayMs > 0) delay(delayMs)
        emit(DetailedAnalysisResult(translation = translationResult) to metadata())
    }

    override suspend fun executeClauses(
        text: String,
        imageBase64: String?,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?
    ): kotlinx.coroutines.flow.Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> = kotlinx.coroutines.flow.flow {
        clausesCalls++
        if (delayMs > 0) delay(delayMs)
        emit(DetailedAnalysisResult(clauses = clausesResult) to metadata())
    }

    override suspend fun executeGrammar(
        text: String,
        imageBase64: String?,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?
    ): kotlinx.coroutines.flow.Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> = kotlinx.coroutines.flow.flow {
        grammarCalls++
        if (delayMs > 0) delay(delayMs)
        emit(DetailedAnalysisResult(grammarPoints = grammarResult) to metadata())
    }

    override suspend fun executeSegments(
        text: String,
        tokens: List<String>,
        imageBase64: String?,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?,
        decoupledPosMode: Boolean
    ): kotlinx.coroutines.flow.Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> = kotlinx.coroutines.flow.flow {
        segmentsCalls++
        lastSegmentsTokens = tokens
        lastDecoupledPosMode = decoupledPosMode
        val effectiveDelay = if (segmentDelayMs > 0) segmentDelayMs else delayMs
        if (effectiveDelay > 0) delay(effectiveDelay)
        emit(DetailedAnalysisResult(segments = segmentsResult) to metadata())
    }

    private fun metadata() = tokenMeta
}

private class FakeDecisionRepository(
    private val shouldFail: Boolean = false,
    private val delayMs: Long = 0,
    private val resultSegments: List<com.example.japanesegrammarapp.domain.model.WordSegment> = emptyList()
) : com.example.japanesegrammarapp.domain.repository.DecisionRepository {
    var classifyCalls = 0
    var lastConfigs: List<LlmApiConfig> = emptyList()

    override suspend fun evaluateDecisions(
        input: String,
        questions: Map<String, com.example.japanesegrammarapp.domain.model.DecisionQuestion>,
        configs: List<LlmApiConfig>,
        apiTypeLabel: String,
        recordId: Int?,
        stepName: String?
    ): com.example.japanesegrammarapp.domain.model.DecisionBatchResult {
        error("Not used in FakeDecisionRepository")
    }

    override suspend fun classifyTokenPos(
        sentence: String,
        tokens: List<String>,
        configs: List<LlmApiConfig>,
        recordId: Int?,
        stepName: String?
    ): Pair<List<com.example.japanesegrammarapp.domain.model.WordSegment>, LlmResultMetadata> {
        classifyCalls++
        lastConfigs = configs
        if (delayMs > 0) delay(delayMs)
        if (shouldFail) error("decision failed")
        return resultSegments to LlmResultMetadata(consumedTokens = 2, inputTokens = 1, outputTokens = 1)
    }
}

private class FakeSettingsRepository(
    private val emptyConfigsAfterFirstLookup: Boolean = false,
    private val decisionApiEnabled: Boolean = false,
    private val decisionModel: String = com.example.japanesegrammarapp.domain.model.DecisionConfig.DEFAULT_MODEL,
    private val decisionConfigs: List<LlmApiConfig> = emptyList()
) : SettingsRepository {
    private var configLookupCount = 0
    private val config = LlmApiConfig(
        provider = "Gemini",
        baseProvider = "Gemini",
        modelName = "gemini-test",
        url = "https://example.test",
        key = "key",
        endpointId = "default_gemini",
        endpointName = "Default"
    )

    override fun getReasoningLevel(): com.example.japanesegrammarapp.domain.model.ReasoningLevel = com.example.japanesegrammarapp.domain.model.ReasoningLevel.AUTO
    override fun setReasoningLevel(level: com.example.japanesegrammarapp.domain.model.ReasoningLevel) = Unit
    override fun getComponentReasoningLevel(apiTypeLabel: String): com.example.japanesegrammarapp.domain.model.ComponentReasoningLevel = com.example.japanesegrammarapp.domain.model.ComponentReasoningLevel.GLOBAL
    override fun setComponentReasoningLevel(apiTypeLabel: String, level: com.example.japanesegrammarapp.domain.model.ComponentReasoningLevel) = Unit
    override fun getEffectiveReasoningLevel(apiTypeLabel: String): com.example.japanesegrammarapp.domain.model.ReasoningLevel = com.example.japanesegrammarapp.domain.model.ReasoningLevel.AUTO
    override fun getComponentModelConfig(apiTypeLabel: String): com.example.japanesegrammarapp.domain.model.ComponentModelConfig = com.example.japanesegrammarapp.domain.model.ComponentModelConfig()
    override fun setComponentModelConfig(apiTypeLabel: String, config: com.example.japanesegrammarapp.domain.model.ComponentModelConfig) = Unit
    override fun getUseBackupApi(): Boolean = false
    override fun setUseBackupApi(value: Boolean) = Unit
    override fun getAutoRetryOnError(): Boolean = false
    override fun setAutoRetryOnError(value: Boolean) = Unit
    override fun getFailoverToNextEndpoint(): Boolean = false
    override fun setFailoverToNextEndpoint(value: Boolean) = Unit
    override fun getStreamingEnabled(): Boolean = true
    override fun setStreamingEnabled(value: Boolean) = Unit

    override fun getAllProviders(): List<String> = LlmConfig.providers
    override fun getBaseProviderType(providerId: String): String = providerId
    override fun getApiKey(provider: String): String = "key"
    override fun saveApiKey(provider: String, key: String): Boolean = true
    override fun getApiUrl(provider: String): String = "https://example.test"
    override fun saveApiUrl(provider: String, url: String) = Unit
    override fun getEndpoints(provider: String): List<LlmEndpoint> {
        if (provider == com.example.japanesegrammarapp.domain.model.DecisionConfig.PROVIDER_ID) {
            return decisionConfigs.mapIndexed { idx, cfg ->
                LlmEndpoint(
                    id = cfg.endpointId.ifBlank { "decision_$idx" },
                    provider = provider,
                    name = cfg.endpointName.ifBlank { "Decision $idx" },
                    baseUrl = cfg.url,
                    enabled = true
                )
            }
        }
        configLookupCount++
        if (emptyConfigsAfterFirstLookup && configLookupCount > 1) {
            return emptyList()
        }
        return listOf(
            LlmEndpoint(
                id = config.endpointId,
                provider = provider,
                name = config.endpointName,
                baseUrl = config.url,
                enabled = true
            )
        )
    }
    override fun getApiKeyForEndpoint(endpointId: String): String {
        val matchedDecision = decisionConfigs.firstOrNull { it.endpointId == endpointId }
        return matchedDecision?.key ?: "key"
    }
    override fun saveEndpoint(endpoint: LlmEndpoint, apiKey: String?): Boolean = true
    override fun deleteEndpoint(provider: String, endpointId: String): Boolean = true
    override fun getActiveProvider(): String = "Gemini"
    override fun setActiveProvider(provider: String) = Unit
    override fun getActiveModel(provider: String): String = "gemini-test"
    override fun setActiveModel(provider: String, model: String) = Unit
    override fun getUseOcr(): Boolean = false
    override fun setUseOcr(value: Boolean) = Unit
    override fun getImageTokenizerMode(): String = "faithful"
    override fun setImageTokenizerMode(mode: String) = Unit
    override fun getOcrBoxDetectionSettings(): OcrBoxDetectionSettings = OcrBoxDetectionSettings.DEFAULT
    override fun setOcrBoxDetectionSettings(settings: OcrBoxDetectionSettings) = Unit
    override fun resetOcrBoxDetectionSettings() = Unit
    override fun getAutoNavigateResult(): Boolean = true
    override fun setAutoNavigateResult(value: Boolean) = Unit
    override fun getSilentBackgroundMode(): Boolean = false
    override fun setSilentBackgroundMode(value: Boolean) = Unit
    override fun getAutoDeskewAfterCapture(): Boolean = false
    override fun setAutoDeskewAfterCapture(value: Boolean) = Unit
    override fun getModelsForProvider(provider: String): List<String> = emptyList()
    override fun saveModelsForProvider(provider: String, models: List<String>) = Unit
    override fun getBackupProvider(): String = ""
    override fun setBackupProvider(provider: String) = Unit
    override fun getBackupModel(): String = ""
    override fun setBackupModel(model: String) = Unit
    override fun getThemeMode(): String = "System"
    override fun setThemeMode(mode: String) = Unit
    override fun getWallpaperUri(): String = ""
    override fun setWallpaperUri(uri: String) = Unit
    override val themeMode = MutableStateFlow("System")
    override val wallpaperUri = MutableStateFlow("")
    override val silentBackgroundMode = MutableStateFlow(false)
    override fun getCustomPrompt(promptKey: String): String = ""
    override fun saveCustomPrompt(promptKey: String, prompt: String) = Unit
    override fun resetCustomPrompt(promptKey: String) = Unit
    override fun resetAllCustomPrompts() = Unit
    override fun getTtsProvider(): String = "OpenAI"
    override fun setTtsProvider(provider: String) = Unit
    override fun getTtsApiUrl(provider: String): String = ""
    override fun setTtsApiUrl(provider: String, url: String) = Unit
    override fun getTtsApiKey(provider: String): String = ""
    override fun setTtsApiKey(provider: String, key: String): Boolean = true
    override fun getTtsModel(provider: String): String = ""
    override fun setTtsModel(provider: String, model: String) = Unit
    override fun getTtsVoice(provider: String): String = ""
    override fun setTtsVoice(provider: String, voice: String) = Unit
    override fun getTtsRegion(provider: String): String = ""
    override fun setTtsRegion(provider: String, region: String) = Unit
    override fun getPromptPresets(): List<com.example.japanesegrammarapp.domain.model.PromptPreset> = emptyList()
    override fun getActivePromptPresetId(): String = ""
    override fun setActivePromptPresetId(id: String) = Unit
    override fun savePromptPreset(preset: com.example.japanesegrammarapp.domain.model.PromptPreset) = Unit
    override fun deletePromptPreset(id: String) = Unit

    override fun getCardFontSizeScale(): Float = 1.0f
    override fun setCardFontSizeScale(scale: Float) = Unit
    override fun getCardSpacingScale(): Float = 1.0f
    override fun setCardSpacingScale(scale: Float) = Unit
    override fun getFuriganaSizeScale(): Float = 1.0f
    override fun setFuriganaSizeScale(scale: Float) = Unit
    override fun getCardInternalPaddingScale(): Float = 1.0f
    override fun setCardInternalPaddingScale(scale: Float) = Unit
    override fun getFuriganaGapScale(): Float = 1.0f
    override fun setFuriganaGapScale(scale: Float) = Unit
    override val cardFontSizeScale = MutableStateFlow(1.0f)
    override val cardSpacingScale = MutableStateFlow(1.0f)
    override val furiganaSizeScale = MutableStateFlow(1.0f)
    override val cardInternalPaddingScale = MutableStateFlow(1.0f)
    override val furiganaGapScale = MutableStateFlow(1.0f)
    override fun getCardDetailDisplayMode(): String = "POPUP"
    override fun setCardDetailDisplayMode(mode: String) = Unit
    override val cardDetailDisplayMode = MutableStateFlow("POPUP")
    override fun getRemoveAccidentalSpaces(): Boolean = true
    override fun setRemoveAccidentalSpaces(value: Boolean) = Unit

    override fun getDeckSyncSettings(): com.example.japanesegrammarapp.domain.model.DeckSyncSettings = com.example.japanesegrammarapp.domain.model.DeckSyncSettings()
    override fun setDeckSyncEnabled(enabled: Boolean) = Unit
    override fun setDeckSyncPort(port: Int) = Unit
    override fun setDeckSyncPin(pin: String) = Unit
    override fun regenerateDeckSyncPin(): String = "8848"
    override fun getDeckSyncAuthToken(): String = ""
    override fun setDeckSyncAuthToken(token: String) = Unit
    override val deckSyncSettingsFlow = MutableStateFlow(com.example.japanesegrammarapp.domain.model.DeckSyncSettings())

    override fun getDecisionApiEnabled(): Boolean = decisionApiEnabled
    override fun setDecisionApiEnabled(value: Boolean) = Unit
    override fun getDecisionModel(): String = decisionModel
    override fun setDecisionModel(model: String) = Unit
}


private class FakeHistoryRepository(vararg initialRecords: AnalysisDomainRecord) : HistoryRepository {
    private val records = linkedMapOf<Int, AnalysisDomainRecord>()
    private var nextId = 1

    init {
        initialRecords.forEach { record ->
            records[record.id] = record
            nextId = maxOf(nextId, record.id + 1)
        }
    }

    override suspend fun getAllRecordsList(): List<AnalysisDomainRecord> = records.values.toList()
    override suspend fun getAllExportPreviews(): List<com.example.japanesegrammarapp.domain.model.HistoryExportPreview> =
        records.values.map {
            com.example.japanesegrammarapp.domain.model.HistoryExportPreview(
                id = it.id,
                originalText = it.originalText,
                timestamp = it.timestamp,
                modelUsed = it.modelUsed,
                status = it.status
            )
        }
    override suspend fun getRecordsByIds(ids: List<Int>): List<AnalysisDomainRecord> =
        records.values.filter { it.id in ids }
    override suspend fun getRecordById(id: Int): AnalysisDomainRecord? = synchronized(records) { records[id] }
    override fun observeRecordById(id: Int): Flow<AnalysisDomainRecord?> = MutableStateFlow(records[id])
    override suspend fun getRecordByOriginalText(originalText: String): AnalysisDomainRecord? =
        synchronized(records) { records.values.firstOrNull { it.originalText == originalText } }

    override suspend fun insertRecord(record: AnalysisDomainRecord): Long = synchronized(records) {
        val id = nextId++
        records[id] = record.copy(id = id)
        id.toLong()
    }

    override suspend fun updateRecord(record: AnalysisDomainRecord) {
        synchronized(records) { records[record.id] = record }
    }

    override suspend fun deleteRecord(record: AnalysisDomainRecord) {
        synchronized(records) { records.remove(record.id) }
    }

    override suspend fun getNewerRecord(timestamp: Long): AnalysisDomainRecord? {
        return synchronized(records) {
            records.values.filter { it.timestamp > timestamp }.minByOrNull { it.timestamp }
        }
    }

    override suspend fun getOlderRecord(timestamp: Long): AnalysisDomainRecord? {
        return synchronized(records) {
            records.values.filter { it.timestamp < timestamp }.maxByOrNull { it.timestamp }
        }
    }

    override suspend fun markRecordAsRead(id: Int) {
        synchronized(records) {
            records[id] = records[id]?.copy(isRead = true) ?: return
        }
    }

    override val totalTokensConsumed: Flow<Int?> = MutableStateFlow(0)
    override val tokenUsageByModel: Flow<List<ModelTokenUsage>> = MutableStateFlow(emptyList())
    override val dailyTokenUsage: Flow<List<DailyTokenUsage>> = MutableStateFlow(emptyList())
}

private class FakeDetailedResultSerializer : DetailedResultSerializer {
    private val gson = Gson()
    var lastSerialized: DetailedAnalysisResult? = null

    override fun toJson(result: DetailedAnalysisResult): String {
        lastSerialized = result
        return gson.toJson(result)
    }

    override fun fromJson(json: String?): DetailedAnalysisResult? {
        if (json.isNullOrBlank()) return null
        return gson.fromJson(json, DetailedAnalysisResult::class.java)
    }
}

private class FakeOcrRepository : OcrRepository {
    override suspend fun extractTextFromImage(imageUri: String): String = ""
}

private class FakeImageAttachmentLoader(
    private val payload: ImagePayload? = null
) : ImageAttachmentLoader {
    override suspend fun loadAsBase64(uriString: String): ImagePayload? = payload
}
