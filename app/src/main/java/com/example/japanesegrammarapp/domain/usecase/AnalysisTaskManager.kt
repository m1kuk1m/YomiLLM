package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.*
import com.example.japanesegrammarapp.domain.repository.*
import com.example.japanesegrammarapp.domain.ApplicationScope
import com.example.japanesegrammarapp.utils.TextCleaner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class AnalysisProgress(
    val isImageTask: Boolean = false,
    val imageOcrCompleted: Boolean = false,
    val tokenizerCompleted: Boolean = false,
    val segmentsCompleted: Boolean = false,
    val decisionPosEnabled: Boolean = false,
    val decisionPosCompleted: Boolean = false,
    val clausesCompleted: Boolean = false,
    val translationCompleted: Boolean = false,
    val grammarCompleted: Boolean = false,
    val partialSegments: List<WordSegment>? = null,
    val stepErrors: Map<String, String> = emptyMap()
)

interface AnalysisTaskManager {
    val progressFlow: StateFlow<Map<Int, AnalysisProgress>>
    val regeneratingModulesFlow: StateFlow<Map<Int, Set<RegeneratableModule>>>

    suspend fun execute(
        text: String,
        imageUri: String?,
        provider: String,
        modelName: String,
        baseUrl: String,
        apiKey: String
    ): Int

    suspend fun executeRetry(recordId: Int, text: String, imageUri: String?)
    suspend fun regenerateModule(recordId: Int, module: RegeneratableModule)
    fun cancel(recordId: Int)
    fun parseDetailedResult(originalText: String, jsonString: String?): DetailedAnalysisResult?
}

@Singleton
class DefaultAnalysisTaskManager @Inject constructor(
    private val saveAnalysisRecordUseCase: SaveAnalysisRecordUseCase,
    private val getOcrTextUseCase: GetOcrTextUseCase,
    private val imageTextExtractor: ImageTextExtractor,
    private val endpointPoolResolver: LlmEndpointPoolResolver,
    private val llmAnalysisService: LlmAnalysisService,
    private val decisionRepository: DecisionRepository,
    private val detailedResultSerializer: DetailedResultSerializer,
    private val eventBus: AnalysisEventBus,
    private val settingsRepository: SettingsRepository,
    private val appLogWriter: AppLogWriter,
    @ApplicationScope private val repositoryScope: CoroutineScope
) : AnalysisTaskManager {
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    private val activeModuleJobs = ConcurrentHashMap<Pair<Int, String>, Job>()
    private val recordMutexes = ConcurrentHashMap<Int, Mutex>()
    private val progressStore = AnalysisProgressStore()
    override val progressFlow: StateFlow<Map<Int, AnalysisProgress>> = progressStore.progressFlow
    private val _regeneratingModulesFlow = MutableStateFlow<Map<Int, Set<RegeneratableModule>>>(emptyMap())
    override val regeneratingModulesFlow: StateFlow<Map<Int, Set<RegeneratableModule>>> = _regeneratingModulesFlow.asStateFlow()

    override suspend fun execute(
        text: String,
        imageUri: String?,
        provider: String,
        modelName: String,
        baseUrl: String,
        apiKey: String
    ): Int {
        val cleanText = if (settingsRepository.getRemoveAccidentalSpaces()) {
            TextCleaner.removeAccidentalSpaces(text)
        } else {
            text
        }

        if (cleanText.isBlank() && imageUri.isNullOrBlank()) {
            throw IllegalArgumentException("Please enter text or capture an image.")
        }
        val configuredPrimaryEndpoints = endpointPoolResolver.resolveConfigs(provider, modelName)
        val hasCompatibleLegacyKey = apiKey.isNotBlank()
        if (configuredPrimaryEndpoints.isEmpty() && !hasCompatibleLegacyKey) {
            throw IllegalArgumentException("Missing API Key.")
        }

        // Prevent duplicate concurrent analysis for the same text
        if (cleanText.isNotBlank()) {
            val duplicate = saveAnalysisRecordUseCase.getByOriginalText(cleanText)
            if (duplicate != null) {
                val isRunning = activeJobs.containsKey(duplicate.id)
                if (isRunning) {
                    return duplicate.id
                } else if (duplicate.status == AnalysisStatus.FAILED || duplicate.status == AnalysisStatus.PENDING) {
                    // Zombie PENDING or FAILED record: restart background analysis
                    saveAnalysisRecordUseCase.update(
                        duplicate.copy(
                            status = AnalysisStatus.PENDING,
                            errorMessage = null,
                            modelUsed = "$provider: $modelName"
                        )
                    )
                    executeRetry(duplicate.id, cleanText, duplicate.imageUri)
                    return duplicate.id
                } else if (duplicate.status == AnalysisStatus.COMPLETED) {
                    return duplicate.id
                }
            }
        }

        val record = AnalysisDomainRecord(
            originalText = cleanText.ifBlank { "" },
            imageUri = imageUri,
            analysisResult = null,
            modelUsed = "$provider: $modelName",
            status = AnalysisStatus.PENDING
        )
        val recordId = saveAnalysisRecordUseCase.insert(record).toInt()

        val job = launchBackgroundAnalysis(recordId, cleanText, imageUri)

        activeJobs[recordId] = job
        job.invokeOnCompletion { activeJobs.remove(recordId) }

        return recordId
    }

    override suspend fun executeRetry(recordId: Int, text: String, imageUri: String?) {
        cancelModuleRegenerations(recordId)
        val cleanText = if (settingsRepository.getRemoveAccidentalSpaces()) {
            TextCleaner.removeAccidentalSpaces(text)
        } else {
            text
        }
        val job = launchBackgroundAnalysis(recordId, cleanText, imageUri)
        activeJobs[recordId] = job
        job.invokeOnCompletion { activeJobs.remove(recordId) }
    }


    override fun cancel(recordId: Int) {
        cancelModuleRegenerations(recordId)
        val job = activeJobs.remove(recordId)
        job?.cancel()
        repositoryScope.launch {
            val record = saveAnalysisRecordUseCase.getById(recordId)
            if (record != null) {
                saveAnalysisRecordUseCase.delete(record)
            }
        }
    }

    private fun cancelModuleRegenerations(recordId: Int) {
        val keysToRemove = activeModuleJobs.keys.filter { it.first == recordId }
        keysToRemove.forEach { key ->
            activeModuleJobs.remove(key)?.cancel()
        }
        _regeneratingModulesFlow.update { it - recordId }
    }

    private fun markModuleRegenerating(recordId: Int, module: RegeneratableModule) {
        _regeneratingModulesFlow.update { map ->
            val current = map[recordId].orEmpty()
            map + (recordId to (current + module))
        }
    }

    private fun unmarkModuleRegenerating(recordId: Int, module: RegeneratableModule) {
        _regeneratingModulesFlow.update { map ->
            val next = map[recordId].orEmpty() - module
            if (next.isEmpty()) map - recordId else map + (recordId to next)
        }
    }

    override suspend fun regenerateModule(recordId: Int, module: RegeneratableModule) {
        if (recordId <= 0) return
        if (activeJobs.containsKey(recordId)) return
        val jobKey = recordId to module.conflictGroup
        if (activeModuleJobs.containsKey(jobKey)) return

        val record = saveAnalysisRecordUseCase.getById(recordId) ?: return
        if (record.status != AnalysisStatus.COMPLETED) return
        val analysisText = record.originalText.trim()
        if (analysisText.isBlank()) return

        val primaryProvider = settingsRepository.getActiveProvider()
        val primaryModel = settingsRepository.getActiveModel(primaryProvider)
        val fallbackPrimaryConfigs = endpointPoolResolver.resolveConfigs(primaryProvider, primaryModel)

        val resolveForModule: (AnalysisModule) -> List<LlmApiConfig> = { target ->
            endpointPoolResolver.resolveModuleConfigs(
                module = target,
                fallbackProvider = primaryProvider,
                fallbackModel = primaryModel,
                onOverrideUnavailable = { overrideProvider, overrideModel ->
                    appLogWriter.error(
                        "LLM_API",
                        "Module model override for [${target.id}] ($overrideProvider: $overrideModel) has no usable endpoint; falling back to global model"
                    )
                }
            )
        }

        val requiredConfigs = when (module) {
            RegeneratableModule.TRANSLATION -> resolveForModule(AnalysisModule.TRANSLATION)
            RegeneratableModule.CLAUSES -> resolveForModule(AnalysisModule.CLAUSES)
            RegeneratableModule.GRAMMAR -> resolveForModule(AnalysisModule.GRAMMAR)
            RegeneratableModule.SEGMENTS_ONLY -> resolveForModule(AnalysisModule.SEGMENTS)
            RegeneratableModule.TOKENIZATION_AND_SEGMENTS -> resolveForModule(AnalysisModule.TOKENIZER_TEXT)
        }
        if (requiredConfigs.isEmpty() && fallbackPrimaryConfigs.isEmpty()) {
            throw IllegalArgumentException("Missing API Key.")
        }

        val backupConfigs = endpointPoolResolver.resolveBackupConfigs()
        val decisionConfigs = if (settingsRepository.getDecisionApiEnabled()) {
            val model = settingsRepository.getDecisionModel()
            endpointPoolResolver.resolveConfigs(DecisionConfig.PROVIDER_ID, model)
        } else {
            emptyList()
        }

        val job = repositoryScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val getRetryListener = { step: AnalysisStep ->
                    { attempt: Int ->
                        repositoryScope.launch {
                            eventBus.post(AnalysisEvent.LlmRetryTriggered(recordId, step, attempt))
                        }
                        Unit
                    }
                }
                val getBackupListener = { step: AnalysisStep ->
                    { backupProv: String ->
                        repositoryScope.launch {
                            eventBus.post(AnalysisEvent.LlmBackupTriggered(recordId, step, backupProv))
                        }
                        Unit
                    }
                }

                when (module) {
                    RegeneratableModule.TRANSLATION -> runRegenerateTranslation(
                        recordId = recordId,
                        analysisText = analysisText,
                        primaryConfigs = resolveForModule(AnalysisModule.TRANSLATION),
                        backupConfigs = backupConfigs,
                        getRetryListener = getRetryListener,
                        getBackupListener = getBackupListener
                    )
                    RegeneratableModule.CLAUSES -> runRegenerateClauses(
                        recordId = recordId,
                        analysisText = analysisText,
                        primaryConfigs = resolveForModule(AnalysisModule.CLAUSES),
                        backupConfigs = backupConfigs,
                        getRetryListener = getRetryListener,
                        getBackupListener = getBackupListener
                    )
                    RegeneratableModule.GRAMMAR -> runRegenerateGrammar(
                        recordId = recordId,
                        analysisText = analysisText,
                        primaryConfigs = resolveForModule(AnalysisModule.GRAMMAR),
                        backupConfigs = backupConfigs,
                        getRetryListener = getRetryListener,
                        getBackupListener = getBackupListener
                    )
                    RegeneratableModule.SEGMENTS_ONLY -> runRegenerateVocabulary(
                        recordId = recordId,
                        analysisText = analysisText,
                        retokenize = false,
                        tokenizerConfigs = resolveForModule(AnalysisModule.TOKENIZER_TEXT),
                        segmentsConfigs = resolveForModule(AnalysisModule.SEGMENTS),
                        backupConfigs = backupConfigs,
                        decisionConfigs = decisionConfigs,
                        getRetryListener = getRetryListener,
                        getBackupListener = getBackupListener
                    )
                    RegeneratableModule.TOKENIZATION_AND_SEGMENTS -> runRegenerateVocabulary(
                        recordId = recordId,
                        analysisText = analysisText,
                        retokenize = true,
                        tokenizerConfigs = resolveForModule(AnalysisModule.TOKENIZER_TEXT),
                        segmentsConfigs = resolveForModule(AnalysisModule.SEGMENTS),
                        backupConfigs = backupConfigs,
                        decisionConfigs = decisionConfigs,
                        getRetryListener = getRetryListener,
                        getBackupListener = getBackupListener
                    )
                }
            } finally {
                activeModuleJobs.remove(jobKey)
                unmarkModuleRegenerating(recordId, module)
            }
        }

        if (activeModuleJobs.putIfAbsent(jobKey, job) != null) {
            job.cancel()
            return
        }
        markModuleRegenerating(recordId, module)
        job.start()
    }

    private fun launchBackgroundAnalysis(
        recordId: Int,
        text: String,
        imageUri: String?
    ): Job {
        return repositoryScope.launch(Dispatchers.IO) {
            progressStore.start(recordId)
            val partialResultStore = AnalysisPartialResultStore(
                recordId = recordId,
                saveAnalysisRecordUseCase = saveAnalysisRecordUseCase,
                detailedResultSerializer = detailedResultSerializer,
                appLogWriter = appLogWriter
            )

            try {
                // Fetch LLM API configurations from the provider endpoint pools.
                val primaryProvider = settingsRepository.getActiveProvider()
                val primaryModel = settingsRepository.getActiveModel(primaryProvider)
                val primaryConfigs = endpointPoolResolver.resolveConfigs(primaryProvider, primaryModel)
                if (primaryConfigs.isEmpty()) {
                    throw IllegalArgumentException("Missing API Key.")
                }

                val backupConfigs = endpointPoolResolver.resolveBackupConfigs()

                // モジュール別モデル設定を解決する。オーバーライドが無効(キー未設定・全端点クールダウン中)
                // ならグローバル設定へフォールバックし、解析自体は中断しない。予備APIは常にグローバル。
                val stepPrimaryConfigs = AnalysisModule.entries.associateWith { module ->
                    endpointPoolResolver.resolveModuleConfigs(
                        module = module,
                        fallbackProvider = primaryProvider,
                        fallbackModel = primaryModel,
                        onOverrideUnavailable = { overrideProvider, overrideModel ->
                            appLogWriter.error(
                                "LLM_API",
                                "Module model override for [${module.id}] ($overrideProvider: $overrideModel) has no usable endpoint; falling back to global model"
                            )
                        }
                    )
                }
                val translationConfigs = stepPrimaryConfigs.getValue(AnalysisModule.TRANSLATION)
                val clausesConfigs = stepPrimaryConfigs.getValue(AnalysisModule.CLAUSES)
                val grammarConfigs = stepPrimaryConfigs.getValue(AnalysisModule.GRAMMAR)
                val segmentsConfigs = stepPrimaryConfigs.getValue(AnalysisModule.SEGMENTS)
                val imageOcrConfigs = stepPrimaryConfigs.getValue(AnalysisModule.IMAGE_OCR)

                val decisionConfigs = if (settingsRepository.getDecisionApiEnabled()) {
                    val model = settingsRepository.getDecisionModel()
                    endpointPoolResolver.resolveConfigs(DecisionConfig.PROVIDER_ID, model)
                } else {
                    emptyList()
                }
                progressStore.setDecisionPosEnabled(recordId, decisionConfigs.isNotEmpty())

                // Perform OCR if needed
                val isOcrEnabled = settingsRepository.getUseOcr()
                val imageTokenizerMode = settingsRepository.getImageTokenizerMode()
                val ocrResult = getOcrTextUseCase.execute(text, imageUri, isOcrEnabled, recordId)

                val isOcrMode = ocrResult.isOcrMode
                val rawOcrText = ocrResult.ocrText
                val ocrText = if (settingsRepository.getRemoveAccidentalSpaces()) {
                    TextCleaner.removeAccidentalSpaces(rawOcrText)
                } else {
                    rawOcrText
                }
                val imageBase64 = ocrResult.imagePayload?.base64Data
                val mimeType = ocrResult.imagePayload?.mimeType

                // tokenizer 変種は入力モード確定後に解決(executeTokenizer と同一条件)
                val tokenizerConfigs = stepPrimaryConfigs.getValue(
                    AnalysisModule.tokenizerVariant(isOcrMode)
                )


                val getRetryListener = { step: AnalysisStep ->
                    { attempt: Int ->
                        repositoryScope.launch {
                            eventBus.post(AnalysisEvent.LlmRetryTriggered(recordId, step, attempt))
                        }
                        Unit
                    }
                }

                val getBackupListener = { step: AnalysisStep ->
                    { backupProv: String ->
                        repositoryScope.launch {
                            eventBus.post(AnalysisEvent.LlmBackupTriggered(recordId, step, backupProv))
                        }
                        Unit
                    }
                }

                if (isOcrMode) {
                    // ==========================================
                    // OCR Mode Flow (Sequential Tokenizer first)
                    // ==========================================
                    var tokens = emptyList<String>()
                    var correctedText: String? = null

                    try {
                        llmAnalysisService.executeTokenizer(
                            text = ocrText,
                            imageBase64 = null,
                            mimeType = null,
                            isOcrMode = true,
                            imageTokenizerMode = imageTokenizerMode,
                            primaryConfigs = tokenizerConfigs,
                            backupConfigs = backupConfigs,
                            onRetry = getRetryListener(AnalysisStep.TOKENIZATION),
                            onBackup = getBackupListener(AnalysisStep.TOKENIZATION),
                            recordId = recordId,
                            stepName = AnalysisStep.TOKENIZATION.name
                        ).collect { res ->
                            val tokenObj = res.first
                            val metadata = res.second
                            tokens = tokenObj?.tokens ?: emptyList()
                            correctedText = tokenObj?.correctedText

                            val skeletonSegments = tokens.map { WordSegment(text = it) }
                            progressStore.updatePartialSegments(recordId, skeletonSegments)
                            partialResultStore.update { current ->
                                var updated = current
                                if (skeletonSegments.isNotEmpty()) {
                                    updated = updated.copy(segments = skeletonSegments)
                                }
                                updated.copy(
                                    consumedTokens = updated.consumedTokens + metadata.consumedTokens,
                                    inputTokens = updated.inputTokens + metadata.inputTokens,
                                    outputTokens = updated.outputTokens + metadata.outputTokens
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val errorMsg = e.localizedMessage ?: "Tokenizer failed"
                        logStepFailure(recordId, AnalysisStep.TOKENIZATION, e)
                        progressStore.markStepError(recordId, AnalysisStep.TOKENIZATION.name, errorMsg)
                        
                        val fallbackToken = if (ocrText.isNotBlank()) ocrText else "読み取り失敗"
                        tokens = listOf(fallbackToken)
                        correctedText = ocrText
                        
                        val skeletonSegments = tokens.map { WordSegment(text = it) }
                        progressStore.updatePartialSegments(recordId, skeletonSegments)
                        partialResultStore.update { current ->
                            current.copy(segments = skeletonSegments)
                                .withModuleError(AnalysisStep.TOKENIZATION.name, errorMsg)
                        }
                    }

                    var effectiveText = ocrText
                    val finalCorrectedText = correctedText
                    if (!finalCorrectedText.isNullOrBlank() && finalCorrectedText != ocrText) {
                        effectiveText = finalCorrectedText
                        eventBus.post(AnalysisEvent.SpellingCorrectedTriggered(recordId, effectiveText))
                    }

                    val duplicateRecord = saveAnalysisRecordUseCase.getByOriginalText(effectiveText)
                    if (duplicateRecord != null && duplicateRecord.id != recordId) {
                        // Atomically clean up the newly created record before notifying the UI,
                        // so the event handler does NOT need to call cancelAnalysis() 鈥?which
                        // would be a no-op or race against invokeOnCompletion anyway.
                        val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                        if (currentRecord != null) {
                            saveAnalysisRecordUseCase.delete(currentRecord)
                        }
                        eventBus.post(AnalysisEvent.DuplicateFound(recordId, duplicateRecord.id))
                        return@launch
                    }

                    if (effectiveText != text) {
                        val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                        if (currentRecord != null) {
                            saveAnalysisRecordUseCase.update(currentRecord.copy(originalText = effectiveText))
                        }
                    }

                    progressStore.markTokenizerCompleted(recordId)

                    supervisorScope {
                        // Translation
                        launch {
                            try {
                                llmAnalysisService.executeTranslation(
                                    text = effectiveText,
                                    imageBase64 = null,
                                    mimeType = null,
                                    primaryConfigs = translationConfigs,
                                    backupConfigs = backupConfigs,
                                    onRetry = getRetryListener(AnalysisStep.TRANSLATION),
                                    onBackup = getBackupListener(AnalysisStep.TRANSLATION),
                                    recordId = recordId,
                                    stepName = AnalysisStep.TRANSLATION.name
                                ).collect { res ->
                                    val obj = res.first
                                    val meta = res.second
                                    partialResultStore.update { current ->
                                    current.copy(
                                        translation = obj?.translation,
                                        consumedTokens = current.consumedTokens + meta.consumedTokens,
                                        inputTokens = current.inputTokens + meta.inputTokens,
                                        outputTokens = current.outputTokens + meta.outputTokens
                                    )
                                }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                val errorMsg = e.localizedMessage ?: "Unknown error"
                                logStepFailure(recordId, AnalysisStep.TRANSLATION, e)
                                progressStore.markStepError(recordId, AnalysisStep.TRANSLATION.name, errorMsg)
                                partialResultStore.update { it.withModuleError(AnalysisStep.TRANSLATION.name, errorMsg) }
                            } finally {
                                progressStore.markTranslationCompleted(recordId)
                            }
                        }

                        // Clauses
                        launch {
                            try {
                                llmAnalysisService.executeClauses(
                                    text = effectiveText,
                                    imageBase64 = null,
                                    mimeType = null,
                                    primaryConfigs = clausesConfigs,
                                    backupConfigs = backupConfigs,
                                    onRetry = getRetryListener(AnalysisStep.CLAUSE_ANALYSIS),
                                    onBackup = getBackupListener(AnalysisStep.CLAUSE_ANALYSIS),
                                    recordId = recordId,
                                    stepName = AnalysisStep.CLAUSE_ANALYSIS.name
                                ).collect { res ->
                                    val obj = res.first
                                    val meta = res.second
                                    partialResultStore.update { current ->
                                    current.copy(
                                        clauses = obj?.clauses,
                                        consumedTokens = current.consumedTokens + meta.consumedTokens,
                                        inputTokens = current.inputTokens + meta.inputTokens,
                                        outputTokens = current.outputTokens + meta.outputTokens
                                    )
                                }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                val errorMsg = e.localizedMessage ?: "Unknown error"
                                logStepFailure(recordId, AnalysisStep.CLAUSE_ANALYSIS, e)
                                progressStore.markStepError(recordId, AnalysisStep.CLAUSE_ANALYSIS.name, errorMsg)
                                partialResultStore.update { it.withModuleError(AnalysisStep.CLAUSE_ANALYSIS.name, errorMsg) }
                            } finally {
                                progressStore.markClausesCompleted(recordId)
                            }
                        }

                        // Grammar
                        launch {
                            try {
                                llmAnalysisService.executeGrammar(
                                    text = effectiveText,
                                    imageBase64 = null,
                                    mimeType = null,
                                    primaryConfigs = grammarConfigs,
                                    backupConfigs = backupConfigs,
                                    onRetry = getRetryListener(AnalysisStep.GRAMMAR_EXPLANATION),
                                    onBackup = getBackupListener(AnalysisStep.GRAMMAR_EXPLANATION),
                                    recordId = recordId,
                                    stepName = AnalysisStep.GRAMMAR_EXPLANATION.name
                                ).collect { res ->
                                    val obj = res.first
                                    val meta = res.second
                                    partialResultStore.update { current ->
                                    current.copy(
                                        grammarPoints = obj?.grammarPoints,
                                        consumedTokens = current.consumedTokens + meta.consumedTokens,
                                        inputTokens = current.inputTokens + meta.inputTokens,
                                        outputTokens = current.outputTokens + meta.outputTokens
                                    )
                                }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                val errorMsg = e.localizedMessage ?: "Unknown error"
                                logStepFailure(recordId, AnalysisStep.GRAMMAR_EXPLANATION, e)
                                progressStore.markStepError(recordId, AnalysisStep.GRAMMAR_EXPLANATION.name, errorMsg)
                                partialResultStore.update { it.withModuleError(AnalysisStep.GRAMMAR_EXPLANATION.name, errorMsg) }
                            } finally {
                                progressStore.markGrammarCompleted(recordId)
                            }
                        }

                        // Segments + optional parallel Decision POS classification
                        launchSegmentsAndDecisionPos(
                            scope = this,
                            recordId = recordId,
                            analysisText = effectiveText,
                            tokens = tokens,
                            segmentsConfigs = segmentsConfigs,
                            backupConfigs = backupConfigs,
                            decisionConfigs = decisionConfigs,
                            partialResultStore = partialResultStore,
                            getRetryListener = getRetryListener,
                            getBackupListener = getBackupListener
                        )
                    }
                } else {
                    // ==========================================
                    // Non-Local-OCR Flow (Image OCR if image present, then Tokenizer, Translation, Clauses, Grammar in parallel. Segments runs after Tokenizer.)
                    // ==========================================
                    val analysisText: String
                    if (imageBase64 != null) {
                        progressStore.setImageTask(recordId, true)
                        var recognizedText: String? = null
                        try {
                            imageTextExtractor.extractTextWithVisionStreaming(
                                imageBase64 = imageBase64,
                                mimeType = mimeType,
                                primaryConfigs = imageOcrConfigs,
                                backupConfigs = backupConfigs,
                                onRetry = getRetryListener(AnalysisStep.IMAGE_OCR),
                                onBackup = getBackupListener(AnalysisStep.IMAGE_OCR),
                                recordId = recordId,
                                stepName = AnalysisStep.IMAGE_OCR.name
                            ).collect { res ->
                                val chunk = res.text
                                val metadata = res.metadata
                                if (chunk.isNotBlank()) {
                                    recognizedText = chunk
                                    val cur = saveAnalysisRecordUseCase.getById(recordId)
                                    if (cur != null) {
                                        saveAnalysisRecordUseCase.update(cur.copy(originalText = chunk))
                                    }
                                }
                                partialResultStore.update { current ->
                                    current.copy(
                                        consumedTokens = current.consumedTokens + metadata.consumedTokens,
                                        inputTokens = current.inputTokens + metadata.inputTokens,
                                        outputTokens = current.outputTokens + metadata.outputTokens
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            logStepFailure(recordId, AnalysisStep.IMAGE_OCR, e)
                            progressStore.markStepError(recordId, AnalysisStep.IMAGE_OCR.name, e.localizedMessage ?: "Image OCR failed")
                            val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                            if (currentRecord != null) {
                                saveAnalysisRecordUseCase.update(
                                    currentRecord.copy(
                                        status = AnalysisStatus.FAILED,
                                        errorMessage = e.localizedMessage ?: "Image OCR failed"
                                    )
                                )
                            }
                            eventBus.post(AnalysisEvent.TaskFailed(recordId, e))
                            return@launch
                        }

                        var effectiveText = recognizedText?.trim().orEmpty()
                        if (settingsRepository.getRemoveAccidentalSpaces()) {
                            effectiveText = TextCleaner.removeAccidentalSpaces(effectiveText)
                        }

                        if (effectiveText.isBlank()) {
                            val err = IllegalStateException("未能从图片中识别出有效日文内容")
                            logStepFailure(recordId, AnalysisStep.IMAGE_OCR, err)
                            progressStore.markStepError(recordId, AnalysisStep.IMAGE_OCR.name, err.message ?: "Empty OCR text")
                            val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                            if (currentRecord != null) {
                                saveAnalysisRecordUseCase.update(
                                    currentRecord.copy(
                                        status = AnalysisStatus.FAILED,
                                        errorMessage = err.message
                                    )
                                )
                            }
                            eventBus.post(AnalysisEvent.TaskFailed(recordId, err))
                            return@launch
                        }

                        progressStore.markImageOcrCompleted(recordId)

                        val duplicateRecord = saveAnalysisRecordUseCase.getByOriginalText(effectiveText)
                        if (duplicateRecord != null && duplicateRecord.id != recordId) {
                            val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                            if (currentRecord != null) {
                                saveAnalysisRecordUseCase.delete(currentRecord)
                            }
                            eventBus.post(AnalysisEvent.DuplicateFound(recordId, duplicateRecord.id))
                            return@launch
                        }

                        if (effectiveText != text) {
                            val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                            if (currentRecord != null) {
                                saveAnalysisRecordUseCase.update(currentRecord.copy(originalText = effectiveText))
                            }
                        }
                        analysisText = effectiveText
                    } else {
                        analysisText = text
                    }

                    // Parallel Pipeline for Analysis
                    supervisorScope {
                            // 1. Translation
                            launch {
                                try {
                                    llmAnalysisService.executeTranslation(
                                        text = analysisText,
                                        imageBase64 = null,
                                        mimeType = null,
                                        primaryConfigs = translationConfigs,
                                        backupConfigs = backupConfigs,
                                        onRetry = getRetryListener(AnalysisStep.TRANSLATION),
                                        onBackup = getBackupListener(AnalysisStep.TRANSLATION),
                                        recordId = recordId,
                                        stepName = AnalysisStep.TRANSLATION.name
                                    ).collect { res ->
                                        val obj = res.first
                                        val meta = res.second
                                        partialResultStore.update { current ->
                                        current.copy(
                                            translation = obj?.translation,
                                            consumedTokens = current.consumedTokens + meta.consumedTokens,
                                            inputTokens = current.inputTokens + meta.inputTokens,
                                            outputTokens = current.outputTokens + meta.outputTokens
                                        )
                                    }
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    val errorMsg = e.localizedMessage ?: "Unknown error"
                                    logStepFailure(recordId, AnalysisStep.TRANSLATION, e)
                                    progressStore.markStepError(recordId, AnalysisStep.TRANSLATION.name, errorMsg)
                                    partialResultStore.update { it.withModuleError(AnalysisStep.TRANSLATION.name, errorMsg) }
                                } finally {
                                    progressStore.markTranslationCompleted(recordId)
                                }
                            }

                            // 2. Clauses
                            launch {
                                try {
                                    llmAnalysisService.executeClauses(
                                        text = analysisText,
                                        imageBase64 = null,
                                        mimeType = null,
                                        primaryConfigs = clausesConfigs,
                                        backupConfigs = backupConfigs,
                                        onRetry = getRetryListener(AnalysisStep.CLAUSE_ANALYSIS),
                                        onBackup = getBackupListener(AnalysisStep.CLAUSE_ANALYSIS),
                                        recordId = recordId,
                                        stepName = AnalysisStep.CLAUSE_ANALYSIS.name
                                    ).collect { res ->
                                        val obj = res.first
                                        val meta = res.second
                                        partialResultStore.update { current ->
                                        current.copy(
                                            clauses = obj?.clauses,
                                            consumedTokens = current.consumedTokens + meta.consumedTokens,
                                            inputTokens = current.inputTokens + meta.inputTokens,
                                            outputTokens = current.outputTokens + meta.outputTokens
                                        )
                                    }
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    val errorMsg = e.localizedMessage ?: "Unknown error"
                                    logStepFailure(recordId, AnalysisStep.CLAUSE_ANALYSIS, e)
                                    progressStore.markStepError(recordId, AnalysisStep.CLAUSE_ANALYSIS.name, errorMsg)
                                    partialResultStore.update { it.withModuleError(AnalysisStep.CLAUSE_ANALYSIS.name, errorMsg) }
                                } finally {
                                    progressStore.markClausesCompleted(recordId)
                                }
                            }

                            // 3. Grammar
                            launch {
                                try {
                                    llmAnalysisService.executeGrammar(
                                        text = analysisText,
                                        imageBase64 = null,
                                        mimeType = null,
                                        primaryConfigs = grammarConfigs,
                                        backupConfigs = backupConfigs,
                                        onRetry = getRetryListener(AnalysisStep.GRAMMAR_EXPLANATION),
                                        onBackup = getBackupListener(AnalysisStep.GRAMMAR_EXPLANATION),
                                        recordId = recordId,
                                        stepName = AnalysisStep.GRAMMAR_EXPLANATION.name
                                    ).collect { res ->
                                        val obj = res.first
                                        val meta = res.second
                                        partialResultStore.update { current ->
                                        current.copy(
                                            grammarPoints = obj?.grammarPoints,
                                            consumedTokens = current.consumedTokens + meta.consumedTokens,
                                            inputTokens = current.inputTokens + meta.inputTokens,
                                            outputTokens = current.outputTokens + meta.outputTokens
                                        )
                                    }
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    val errorMsg = e.localizedMessage ?: "Unknown error"
                                    logStepFailure(recordId, AnalysisStep.GRAMMAR_EXPLANATION, e)
                                    progressStore.markStepError(recordId, AnalysisStep.GRAMMAR_EXPLANATION.name, errorMsg)
                                    partialResultStore.update { it.withModuleError(AnalysisStep.GRAMMAR_EXPLANATION.name, errorMsg) }
                                } finally {
                                    progressStore.markGrammarCompleted(recordId)
                                }
                            }

                            // 4. Tokenizer & Segments Sequence
                            launch {
                                var tokens = emptyList<String>()
                                try {
                                    // 4a. Execute Tokenizer first
                                    llmAnalysisService.executeTokenizer(
                                        text = analysisText,
                                        imageBase64 = null,
                                        mimeType = null,
                                        isOcrMode = false,
                                        imageTokenizerMode = imageTokenizerMode,
                                        primaryConfigs = tokenizerConfigs,
                                        backupConfigs = backupConfigs,
                                        onRetry = getRetryListener(AnalysisStep.TOKENIZATION),
                                        onBackup = getBackupListener(AnalysisStep.TOKENIZATION),
                                        recordId = recordId,
                                        stepName = AnalysisStep.TOKENIZATION.name
                                    ).collect { res ->
                                        val tokenObj = res.first
                                        val tokenMeta = res.second
                                        tokens = tokenObj?.tokens ?: emptyList()

                                        val skeletonSegments = tokens.map { WordSegment(text = it) }
                                        progressStore.updatePartialSegments(recordId, skeletonSegments)
                                        partialResultStore.update { current ->
                                            var updated = current
                                            if (skeletonSegments.isNotEmpty()) {
                                                updated = updated.copy(segments = skeletonSegments)
                                            }
                                            updated.copy(
                                                consumedTokens = updated.consumedTokens + tokenMeta.consumedTokens,
                                                inputTokens = updated.inputTokens + tokenMeta.inputTokens,
                                                outputTokens = updated.outputTokens + tokenMeta.outputTokens
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    val errorMsg = e.localizedMessage ?: "Tokenizer failed"
                                    logStepFailure(recordId, AnalysisStep.TOKENIZATION, e)
                                    progressStore.markStepError(recordId, AnalysisStep.TOKENIZATION.name, errorMsg)
                                    val fallbackToken = if (analysisText.isNotBlank()) analysisText else "読み取り失敗"
                                    tokens = listOf(fallbackToken)
                                    val skeletonSegments = tokens.map { WordSegment(text = it) }
                                    progressStore.updatePartialSegments(recordId, skeletonSegments)
                                    partialResultStore.update { current ->
                                        current.copy(segments = skeletonSegments)
                                            .withModuleError(AnalysisStep.TOKENIZATION.name, errorMsg)
                                    }
                                } finally {
                                    progressStore.markTokenizerCompleted(recordId)
                                }

                                // 4b. Execute detailed segments analysis (+ optional parallel Decision POS classification)
                                launchSegmentsAndDecisionPos(
                                    scope = this,
                                    recordId = recordId,
                                    analysisText = analysisText,
                                    tokens = tokens,
                                    segmentsConfigs = segmentsConfigs,
                                    backupConfigs = backupConfigs,
                                    decisionConfigs = decisionConfigs,
                                    partialResultStore = partialResultStore,
                                    getRetryListener = getRetryListener,
                                    getBackupListener = getBackupListener
                                )
                            }
                        }
                    }

                val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                if (currentRecord != null) {
                    var updatedText = currentRecord.originalText
                    if (updatedText.isBlank()) {
                        val combinedSentence = partialResultStore.combinedSegmentText()
                        if (combinedSentence.isNotBlank()) {
                            updatedText = combinedSentence
                        }
                    }
                    val finalResultJson = partialResultStore.toJson()
                    val finalResultSnapshot = partialResultStore.snapshot()
                    val updatedRecord = currentRecord.copy(
                        originalText = updatedText,
                        analysisResult = finalResultJson,
                        status = AnalysisStatus.COMPLETED,
                        consumedTokens = finalResultSnapshot.consumedTokens,
                        inputTokens = finalResultSnapshot.inputTokens,
                        outputTokens = finalResultSnapshot.outputTokens,
                        modelUsed = "$primaryProvider: $primaryModel"
                    )
                    saveAnalysisRecordUseCase.update(updatedRecord)

                    eventBus.post(AnalysisEvent.TaskCompleted(recordId, updatedText, updatedText.length > 10))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appLogWriter.error("LLM_API", "Background analysis execution failed for recordId: $recordId", e)

                val currentRecord = saveAnalysisRecordUseCase.getById(recordId)
                if (currentRecord != null) {
                    val fullMessage = buildString {
                        append(e.localizedMessage ?: "Unknown network error")
                        append("\n\n--- Stack Trace ---\n")
                        append(e.stackTraceToString().take(1500))
                        if (e.stackTraceToString().length > 1500) {
                            append("...")
                        }
                    }
                    progressStore.finish(recordId)
                    saveAnalysisRecordUseCase.update(
                        currentRecord.copy(
                            status = AnalysisStatus.FAILED,
                            errorMessage = fullMessage
                        )
                    )
                    eventBus.post(AnalysisEvent.TaskFailed(recordId, e))
                }
            } finally {
                progressStore.finish(recordId)
            }
        }
    }

    private fun launchSegmentsAndDecisionPos(
        scope: CoroutineScope,
        recordId: Int,
        analysisText: String,
        tokens: List<String>,
        segmentsConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        decisionConfigs: List<LlmApiConfig>,
        partialResultStore: AnalysisPartialResultStore,
        getRetryListener: (AnalysisStep) -> (Int) -> Unit,
        getBackupListener: (AnalysisStep) -> (String) -> Unit
    ) {
        val useDecisionPos = decisionConfigs.isNotEmpty()
        val mergeMutex = Mutex()
        var decisionSegments: List<WordSegment>? = null

        if (useDecisionPos) {
            scope.launch {
                try {
                    val (classified, meta) = decisionRepository.classifyTokenPos(
                        sentence = analysisText,
                        tokens = tokens,
                        configs = decisionConfigs,
                        recordId = recordId,
                        stepName = AnalysisStep.DECISION_POS.name
                    )
                    mergeMutex.withLock {
                        decisionSegments = classified
                        partialResultStore.update { current ->
                            val baseSegments = current.segments ?: tokens.map { WordSegment(text = it) }
                            val merged = SegmentMergeHelper.mergeDecisionPos(baseSegments, classified)
                            progressStore.updatePartialSegments(recordId, merged)
                            current.copy(
                                segments = merged,
                                consumedTokens = current.consumedTokens + meta.consumedTokens,
                                inputTokens = current.inputTokens + meta.inputTokens,
                                outputTokens = current.outputTokens + meta.outputTokens
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val errorMsg = e.localizedMessage ?: "Decision POS classification failed"
                    logStepFailure(recordId, AnalysisStep.DECISION_POS, e)
                    progressStore.markStepError(
                        recordId,
                        AnalysisStep.DECISION_POS.name,
                        errorMsg
                    )
                    partialResultStore.update { it.withModuleError(AnalysisStep.DECISION_POS.name, errorMsg) }
                } finally {
                    progressStore.markDecisionPosCompleted(recordId)
                }
            }
        }

        scope.launch {
            try {
                llmAnalysisService.executeSegments(
                    text = analysisText,
                    tokens = tokens,
                    imageBase64 = null,
                    mimeType = null,
                    primaryConfigs = segmentsConfigs,
                    backupConfigs = backupConfigs,
                    onRetry = getRetryListener(AnalysisStep.DETAILED_GRAMMAR),
                    onBackup = getBackupListener(AnalysisStep.DETAILED_GRAMMAR),
                    recordId = recordId,
                    stepName = AnalysisStep.DETAILED_GRAMMAR.name,
                    decoupledPosMode = useDecisionPos
                ).collect { resSeg ->
                    val segObj = resSeg.first
                    val segMeta = resSeg.second
                    mergeMutex.withLock {
                        val merged = segObj?.segments?.let { detailSegs ->
                            SegmentMergeHelper.mergeDetailSegments(detailSegs, decisionSegments)
                        }
                        merged?.let { progressStore.updatePartialSegments(recordId, it) }
                        partialResultStore.update { current ->
                            val nextSegments = merged ?: current.segments
                            current.copy(
                                segments = nextSegments,
                                consumedTokens = current.consumedTokens + segMeta.consumedTokens,
                                inputTokens = current.inputTokens + segMeta.inputTokens,
                                outputTokens = current.outputTokens + segMeta.outputTokens
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = e.localizedMessage ?: "Unknown error"
                logStepFailure(recordId, AnalysisStep.DETAILED_GRAMMAR, e)
                progressStore.markStepError(
                    recordId,
                    AnalysisStep.DETAILED_GRAMMAR.name,
                    errorMsg
                )
                partialResultStore.update { it.withModuleError(AnalysisStep.DETAILED_GRAMMAR.name, errorMsg) }
            } finally {
                progressStore.markSegmentsCompleted(recordId)
            }
        }
    }

    private class TokenDeltaTracker {
        private var lastConsumed = 0
        private var lastInput = 0
        private var lastOutput = 0

        fun consumeDelta(meta: LlmResultMetadata): LlmResultMetadata {
            val deltaConsumed = (meta.consumedTokens - lastConsumed).coerceAtLeast(0)
            val deltaInput = (meta.inputTokens - lastInput).coerceAtLeast(0)
            val deltaOutput = (meta.outputTokens - lastOutput).coerceAtLeast(0)
            if (meta.consumedTokens > lastConsumed) lastConsumed = meta.consumedTokens
            if (meta.inputTokens > lastInput) lastInput = meta.inputTokens
            if (meta.outputTokens > lastOutput) lastOutput = meta.outputTokens
            return LlmResultMetadata(deltaConsumed, deltaInput, deltaOutput, meta.reasoningTokens)
        }
    }

    private fun DetailedAnalysisResult.addTokenDelta(delta: LlmResultMetadata): DetailedAnalysisResult {
        if (delta.consumedTokens == 0 && delta.inputTokens == 0 && delta.outputTokens == 0) {
            return this
        }
        return copy(
            consumedTokens = consumedTokens + delta.consumedTokens,
            inputTokens = inputTokens + delta.inputTokens,
            outputTokens = outputTokens + delta.outputTokens
        )
    }

    private suspend fun patchRecordResult(
        recordId: Int,
        transform: (DetailedAnalysisResult) -> DetailedAnalysisResult
    ) {
        val mutex = recordMutexes.computeIfAbsent(recordId) { Mutex() }
        mutex.withLock {
            val currentRecord = saveAnalysisRecordUseCase.getById(recordId) ?: return@withLock
            val existing = detailedResultSerializer.fromJson(currentRecord.analysisResult)
                ?: DetailedAnalysisResult(
                    consumedTokens = currentRecord.consumedTokens,
                    inputTokens = currentRecord.inputTokens,
                    outputTokens = currentRecord.outputTokens
                )
            val synced = existing.copy(
                consumedTokens = maxOf(existing.consumedTokens, currentRecord.consumedTokens),
                inputTokens = maxOf(existing.inputTokens, currentRecord.inputTokens),
                outputTokens = maxOf(existing.outputTokens, currentRecord.outputTokens)
            )
            val updatedDetail = transform(synced)
            val updatedJson = detailedResultSerializer.toJson(updatedDetail)
            saveAnalysisRecordUseCase.update(
                currentRecord.copy(
                    analysisResult = updatedJson,
                    consumedTokens = updatedDetail.consumedTokens,
                    inputTokens = updatedDetail.inputTokens,
                    outputTokens = updatedDetail.outputTokens
                )
            )
        }
    }

    private suspend fun runRegenerateTranslation(
        recordId: Int,
        analysisText: String,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        getRetryListener: (AnalysisStep) -> (Int) -> Unit,
        getBackupListener: (AnalysisStep) -> (String) -> Unit
    ) {
        val tokenTracker = TokenDeltaTracker()
        patchRecordResult(recordId) { it.withoutModuleErrors(AnalysisStep.TRANSLATION.name) }
        try {
            llmAnalysisService.executeTranslation(
                text = analysisText,
                imageBase64 = null,
                mimeType = null,
                primaryConfigs = primaryConfigs,
                backupConfigs = backupConfigs,
                onRetry = getRetryListener(AnalysisStep.TRANSLATION),
                onBackup = getBackupListener(AnalysisStep.TRANSLATION),
                recordId = recordId,
                stepName = AnalysisStep.TRANSLATION.name
            ).collect { (obj, meta) ->
                val delta = tokenTracker.consumeDelta(meta)
                val nextTranslation = obj?.translation?.takeIf { it.isNotBlank() }
                if (nextTranslation != null || delta.consumedTokens > 0 || delta.inputTokens > 0 || delta.outputTokens > 0) {
                    patchRecordResult(recordId) { current ->
                        val base = if (nextTranslation != null) {
                            current.copy(translation = nextTranslation)
                                .withoutModuleErrors(AnalysisStep.TRANSLATION.name)
                        } else {
                            current
                        }
                        base.addTokenDelta(delta)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val errorMsg = e.localizedMessage ?: "Unknown error"
            logStepFailure(recordId, AnalysisStep.TRANSLATION, e)
            patchRecordResult(recordId) { it.withModuleError(AnalysisStep.TRANSLATION.name, errorMsg) }
        }
    }

    private suspend fun runRegenerateClauses(
        recordId: Int,
        analysisText: String,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        getRetryListener: (AnalysisStep) -> (Int) -> Unit,
        getBackupListener: (AnalysisStep) -> (String) -> Unit
    ) {
        val tokenTracker = TokenDeltaTracker()
        var receivedNonEmpty = false
        patchRecordResult(recordId) { it.withoutModuleErrors(AnalysisStep.CLAUSE_ANALYSIS.name) }
        try {
            llmAnalysisService.executeClauses(
                text = analysisText,
                imageBase64 = null,
                mimeType = null,
                primaryConfigs = primaryConfigs,
                backupConfigs = backupConfigs,
                onRetry = getRetryListener(AnalysisStep.CLAUSE_ANALYSIS),
                onBackup = getBackupListener(AnalysisStep.CLAUSE_ANALYSIS),
                recordId = recordId,
                stepName = AnalysisStep.CLAUSE_ANALYSIS.name
            ).collect { (obj, meta) ->
                val delta = tokenTracker.consumeDelta(meta)
                val nextClauses = obj?.clauses
                if (!nextClauses.isNullOrEmpty()) {
                    receivedNonEmpty = true
                }
                if (!nextClauses.isNullOrEmpty() || delta.consumedTokens > 0 || delta.inputTokens > 0 || delta.outputTokens > 0) {
                    patchRecordResult(recordId) { current ->
                        val base = if (!nextClauses.isNullOrEmpty()) {
                            current.copy(clauses = nextClauses)
                                .withoutModuleErrors(AnalysisStep.CLAUSE_ANALYSIS.name)
                        } else {
                            current
                        }
                        base.addTokenDelta(delta)
                    }
                }
            }
            if (!receivedNonEmpty) {
                patchRecordResult(recordId) { current ->
                    current.copy(clauses = emptyList())
                        .withoutModuleErrors(AnalysisStep.CLAUSE_ANALYSIS.name)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val errorMsg = e.localizedMessage ?: "Unknown error"
            logStepFailure(recordId, AnalysisStep.CLAUSE_ANALYSIS, e)
            patchRecordResult(recordId) { it.withModuleError(AnalysisStep.CLAUSE_ANALYSIS.name, errorMsg) }
        }
    }

    private suspend fun runRegenerateGrammar(
        recordId: Int,
        analysisText: String,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        getRetryListener: (AnalysisStep) -> (Int) -> Unit,
        getBackupListener: (AnalysisStep) -> (String) -> Unit
    ) {
        val tokenTracker = TokenDeltaTracker()
        var receivedNonEmpty = false
        patchRecordResult(recordId) { it.withoutModuleErrors(AnalysisStep.GRAMMAR_EXPLANATION.name) }
        try {
            llmAnalysisService.executeGrammar(
                text = analysisText,
                imageBase64 = null,
                mimeType = null,
                primaryConfigs = primaryConfigs,
                backupConfigs = backupConfigs,
                onRetry = getRetryListener(AnalysisStep.GRAMMAR_EXPLANATION),
                onBackup = getBackupListener(AnalysisStep.GRAMMAR_EXPLANATION),
                recordId = recordId,
                stepName = AnalysisStep.GRAMMAR_EXPLANATION.name
            ).collect { (obj, meta) ->
                val delta = tokenTracker.consumeDelta(meta)
                val nextPoints = obj?.grammarPoints
                if (!nextPoints.isNullOrEmpty()) {
                    receivedNonEmpty = true
                }
                if (!nextPoints.isNullOrEmpty() || delta.consumedTokens > 0 || delta.inputTokens > 0 || delta.outputTokens > 0) {
                    patchRecordResult(recordId) { current ->
                        val base = if (!nextPoints.isNullOrEmpty()) {
                            current.copy(grammarPoints = nextPoints)
                                .withoutModuleErrors(AnalysisStep.GRAMMAR_EXPLANATION.name)
                        } else {
                            current
                        }
                        base.addTokenDelta(delta)
                    }
                }
            }
            if (!receivedNonEmpty) {
                patchRecordResult(recordId) { current ->
                    current.copy(grammarPoints = emptyList())
                        .withoutModuleErrors(AnalysisStep.GRAMMAR_EXPLANATION.name)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val errorMsg = e.localizedMessage ?: "Unknown error"
            logStepFailure(recordId, AnalysisStep.GRAMMAR_EXPLANATION, e)
            patchRecordResult(recordId) { it.withModuleError(AnalysisStep.GRAMMAR_EXPLANATION.name, errorMsg) }
        }
    }

    private suspend fun runRegenerateVocabulary(
        recordId: Int,
        analysisText: String,
        retokenize: Boolean,
        tokenizerConfigs: List<LlmApiConfig>,
        segmentsConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        decisionConfigs: List<LlmApiConfig>,
        getRetryListener: (AnalysisStep) -> (Int) -> Unit,
        getBackupListener: (AnalysisStep) -> (String) -> Unit
    ) {
        val currentRecord = saveAnalysisRecordUseCase.getById(recordId) ?: return
        val existingDetail = detailedResultSerializer.fromJson(currentRecord.analysisResult)
        val existingTokens = existingDetail?.segments
            .orEmpty()
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
        val hasTokenizerError = existingDetail?.moduleErrors?.containsKey(AnalysisStep.TOKENIZATION.name) == true
        val effectiveRetokenize = retokenize || existingTokens.isEmpty() || hasTokenizerError

        var tokens = existingTokens
        if (effectiveRetokenize) {
            patchRecordResult(recordId) {
                it.withoutModuleErrors(
                    AnalysisStep.TOKENIZATION.name,
                    AnalysisStep.DETAILED_GRAMMAR.name,
                    AnalysisStep.DECISION_POS.name
                )
            }
            val tokenTracker = TokenDeltaTracker()
            val imageTokenizerMode = settingsRepository.getImageTokenizerMode()
            var newTokens = emptyList<String>()
            try {
                llmAnalysisService.executeTokenizer(
                    text = analysisText,
                    imageBase64 = null,
                    mimeType = null,
                    isOcrMode = false,
                    imageTokenizerMode = imageTokenizerMode,
                    primaryConfigs = tokenizerConfigs,
                    backupConfigs = backupConfigs,
                    onRetry = getRetryListener(AnalysisStep.TOKENIZATION),
                    onBackup = getBackupListener(AnalysisStep.TOKENIZATION),
                    recordId = recordId,
                    stepName = AnalysisStep.TOKENIZATION.name
                ).collect { (tokenObj, meta) ->
                    val delta = tokenTracker.consumeDelta(meta)
                    val parsedTokens = tokenObj?.tokens.orEmpty().filter { it.isNotBlank() }
                    if (parsedTokens.isNotEmpty()) {
                        newTokens = parsedTokens
                    }
                    if (parsedTokens.isNotEmpty() || delta.consumedTokens > 0 || delta.inputTokens > 0 || delta.outputTokens > 0) {
                        patchRecordResult(recordId) { current ->
                            val base = if (parsedTokens.isNotEmpty()) {
                                current.copy(segments = parsedTokens.map { WordSegment(text = it) })
                                    .withoutModuleErrors(AnalysisStep.TOKENIZATION.name)
                            } else {
                                current
                            }
                            base.addTokenDelta(delta)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = e.localizedMessage ?: "Tokenizer failed"
                logStepFailure(recordId, AnalysisStep.TOKENIZATION, e)
                patchRecordResult(recordId) { current ->
                    current.withModuleError(AnalysisStep.TOKENIZATION.name, errorMsg)
                }
                return
            }

            if (newTokens.isEmpty()) {
                val errorMsg = "Tokenizer returned empty tokens"
                patchRecordResult(recordId) { current ->
                    current.withModuleError(AnalysisStep.TOKENIZATION.name, errorMsg)
                }
                return
            }
            tokens = newTokens
        } else {
            patchRecordResult(recordId) {
                it.withoutModuleErrors(
                    AnalysisStep.DETAILED_GRAMMAR.name,
                    AnalysisStep.DECISION_POS.name
                )
            }
        }

        val useDecisionPos = decisionConfigs.isNotEmpty()
        val mergeMutex = Mutex()
        var decisionSegments: List<WordSegment>? = null

        supervisorScope {
            if (useDecisionPos) {
                launch {
                    try {
                        val (classified, meta) = decisionRepository.classifyTokenPos(
                            sentence = analysisText,
                            tokens = tokens,
                            configs = decisionConfigs,
                            recordId = recordId,
                            stepName = AnalysisStep.DECISION_POS.name
                        )
                        mergeMutex.withLock {
                            decisionSegments = classified
                            patchRecordResult(recordId) { current ->
                                val baseSegments = current.segments ?: tokens.map { WordSegment(text = it) }
                                val merged = SegmentMergeHelper.mergeDecisionPos(baseSegments, classified)
                                current.copy(segments = merged)
                                    .withoutModuleErrors(AnalysisStep.DECISION_POS.name)
                                    .addTokenDelta(meta)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val errorMsg = e.localizedMessage ?: "Decision POS classification failed"
                        logStepFailure(recordId, AnalysisStep.DECISION_POS, e)
                        patchRecordResult(recordId) { it.withModuleError(AnalysisStep.DECISION_POS.name, errorMsg) }
                    }
                }
            }

            launch {
                val segTokenTracker = TokenDeltaTracker()
                try {
                    llmAnalysisService.executeSegments(
                        text = analysisText,
                        tokens = tokens,
                        imageBase64 = null,
                        mimeType = null,
                        primaryConfigs = segmentsConfigs,
                        backupConfigs = backupConfigs,
                        onRetry = getRetryListener(AnalysisStep.DETAILED_GRAMMAR),
                        onBackup = getBackupListener(AnalysisStep.DETAILED_GRAMMAR),
                        recordId = recordId,
                        stepName = AnalysisStep.DETAILED_GRAMMAR.name,
                        decoupledPosMode = useDecisionPos
                    ).collect { (segObj, meta) ->
                        val delta = segTokenTracker.consumeDelta(meta)
                        mergeMutex.withLock {
                            val merged = segObj?.segments?.takeIf { it.isNotEmpty() }?.let { detailSegs ->
                                SegmentMergeHelper.mergeDetailSegments(detailSegs, decisionSegments)
                            }
                            if (merged != null || delta.consumedTokens > 0 || delta.inputTokens > 0 || delta.outputTokens > 0) {
                                patchRecordResult(recordId) { current ->
                                    val base = if (merged != null) {
                                        current.copy(segments = merged)
                                            .withoutModuleErrors(AnalysisStep.DETAILED_GRAMMAR.name)
                                    } else {
                                        current
                                    }
                                    base.addTokenDelta(delta)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val errorMsg = e.localizedMessage ?: "Unknown error"
                    logStepFailure(recordId, AnalysisStep.DETAILED_GRAMMAR, e)
                    patchRecordResult(recordId) { it.withModuleError(AnalysisStep.DETAILED_GRAMMAR.name, errorMsg) }
                }
            }
        }
    }

    private fun logStepFailure(recordId: Int, step: AnalysisStep, error: Exception) {
        appLogWriter.error(
            "LLM_STEP",
            "Analysis step ${step.name} failed for recordId: $recordId",
            error
        )
    }

    override fun parseDetailedResult(originalText: String, jsonString: String?): DetailedAnalysisResult? {
        return detailedResultSerializer.fromJson(jsonString)
    }
}
