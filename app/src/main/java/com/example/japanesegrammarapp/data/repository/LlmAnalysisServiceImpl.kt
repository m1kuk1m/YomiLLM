package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.repository.LlmAnalysisService
import com.example.japanesegrammarapp.domain.repository.LlmRepository
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.LlmResultMetadata
import com.example.japanesegrammarapp.domain.model.AnalysisModule
import com.example.japanesegrammarapp.domain.model.DetailedAnalysisResult
import com.example.japanesegrammarapp.domain.model.TokenizationResult
import com.example.japanesegrammarapp.domain.model.WordSegment
import com.example.japanesegrammarapp.domain.model.isPunctuationToken
import com.example.japanesegrammarapp.domain.model.PUNCTUATION_CHARS
import com.example.japanesegrammarapp.domain.model.derivePosCategory
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmAnalysisServiceImpl @Inject constructor(
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val gson: Gson
) : LlmAnalysisService {

    private companion object {
        const val DEFAULT_STEP_TIMEOUT_MS = 150_000L
        const val GRAMMAR_STEP_TIMEOUT_MS = 120_000L
        val TOKENIZER_BOUNDARY_PUNCTUATION_CHARS = setOf(
            '。', '、', '・', '？', '！', '「', '」', '『', '』', '（', '）',
            '〔', '〕', '［', '］', '｛', '｝', '〜', '～', '…', '：', '；',
            '―', '—', '【', '】', '《', '》', '〈', '〉', '〝', '〟',
            '?', '!', '(', ')', '[', ']', '{', '}', ':', ';',
            '‼', '⁉', '⁇', '⁈', '〰'
        )
        val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
    }

    override suspend fun executeImageOcr(
        imageBase64: String,
        mimeType: String?,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?
    ): Flow<Pair<String?, LlmResultMetadata>> {
        val systemPrompt = settingsRepository.getCustomPrompt(AnalysisModule.IMAGE_OCR.promptKey)
        val userPrompt = "画像内の日本語テキストを視覚情報に従って正確に読み取り、余計な説明・装飾・振仮名は一切含めず、本文のみをテキストで出力してください。"

        return executeAnalysisStepStreaming(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            mimeType = mimeType,
            apiTypeLabel = AnalysisModule.IMAGE_OCR.id,
            primaryConfigs = primaryConfigs,
            backupConfigs = backupConfigs,
            onRetry = onRetry,
            onBackup = onBackup,
            recordId = recordId,
            stepName = stepName,
            timeoutMs = DEFAULT_STEP_TIMEOUT_MS
        ) { accumulated ->
            accumulated.trim()
        }
    }

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
    ): Flow<Pair<TokenizationResult?, LlmResultMetadata>> {
        // 入力モードに応じた tokenizer 変種(プロンプト選択と apiTypeLabel を単一の解決に統合)
        val module = AnalysisModule.tokenizerVariant(imageBase64, isOcrMode, imageTokenizerMode)
        val systemPrompt = settingsRepository.getCustomPrompt(module.promptKey)

        val userPrompt = when {
            imageBase64 != null && imageTokenizerMode == "repair" -> "画像内の日本語テキストを視覚情報優先で読み取り、不鮮明な場合も文脈は類似字形候補を選ぶ補助に限定してください。意味・自然さ・頻度・安全性を理由に一般語へ置き換えず、濁点/半濁点/長音符/小書き文字は画像上の痕跡を優先してください。テキストで出力してください。"
            imageBase64 != null -> "画像内の日本語テキストを原文のまま忠実に認識し、一切修正せず、文字を変更しないでトークン化してください。テキストで出力してください。"
            isOcrMode -> "分析対象のOCRテキスト: \"$text\"\nこのテキストのOCR誤認識（て・で誤認、濁点脱落など）を自动修正した上で、トークン化し、出力してください。"
            else -> "分析対象の文: \"$text\"\nこの文をトークン化し、出力してください。"
        }

        return executeAnalysisStepStreaming(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            mimeType = mimeType,
            apiTypeLabel = module.id,
            primaryConfigs = primaryConfigs,
            backupConfigs = backupConfigs,
            onRetry = onRetry,
            onBackup = onBackup,
            recordId = recordId,
            stepName = stepName,
            timeoutMs = DEFAULT_STEP_TIMEOUT_MS
        ) { accumulated ->
            val lines = accumulated.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                TokenizationResult()
            } else {
                if (imageBase64 != null || isOcrMode) {
                    val firstLine = lines.firstOrNull() ?: ""
                    val tokens = if (lines.size > 1) normalizeTokenizerTokens(lines.drop(1)) else emptyList()
                    if (isOcrMode) {
                        TokenizationResult(correctedText = firstLine, tokens = tokens)
                    } else {
                        TokenizationResult(recognizedText = firstLine, tokens = tokens)
                    }
                } else {
                    TokenizationResult(tokens = normalizeTokenizerTokens(lines))
                }
            }
        }
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
    ): Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> {
        val userPrompt = if (text.isNotBlank()) {
            "分析対象の文: \"$text\"\nこの文の自然な中国語訳を出力してください。"
        } else {
            "画像内の日本語テキストを自然な中国語（簡体字）に翻訳してください。"
        }

        return executeAnalysisStepStreaming(
            systemPrompt = settingsRepository.getCustomPrompt("prompt_translation"),
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            mimeType = mimeType,
            apiTypeLabel = AnalysisModule.TRANSLATION.id,
            primaryConfigs = primaryConfigs,
            backupConfigs = backupConfigs,
            onRetry = onRetry,
            onBackup = onBackup,
            recordId = recordId,
            stepName = stepName,
            timeoutMs = DEFAULT_STEP_TIMEOUT_MS
        ) { accumulated ->
            DetailedAnalysisResult(translation = accumulated.trim())
        }
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
    ): Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> {
        val userPrompt = if (text.isNotBlank()) {
            "分析対象の文: \"$text\"\nこの文の文節（フレーズ）ごとの文法的役割の詳細な解説を行ってください。"
        } else {
            "画像内のすべての日本語の文節構造と文法的役割を詳細に分析してください。"
        }

        return executeAnalysisStepStreaming(
            systemPrompt = settingsRepository.getCustomPrompt("prompt_clauses"),
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            mimeType = mimeType,
            apiTypeLabel = AnalysisModule.CLAUSES.id,
            primaryConfigs = primaryConfigs,
            backupConfigs = backupConfigs,
            onRetry = onRetry,
            onBackup = onBackup,
            recordId = recordId,
            stepName = stepName,
            timeoutMs = DEFAULT_STEP_TIMEOUT_MS
        ) { accumulated ->
            val lines = accumulated.lines().map { it.trim() }.filter { it.isNotBlank() }
            val clauses = lines.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 4) {
                    com.example.japanesegrammarapp.domain.model.SentenceClause(
                        index = parts[0].toIntOrNull(),
                        role = parts[1],
                        text = parts[2],
                        explanation = parts.drop(3).joinToString("|")
                    )
                } else null
            }
            DetailedAnalysisResult(clauses = clauses)
        }
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
    ): Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> {
        val userPrompt = if (text.isNotBlank()) {
            "分析対象の文: \"$text\"\nこの文に含まれる最も重要かつ難度の高い文法項目・慣用表現を厳選して解説してください。"
        } else {
            "画像内のすべての日本語の文法表现や固定表現を詳細に分析してください。"
        }

        return executeAnalysisStepStreaming(
            systemPrompt = settingsRepository.getCustomPrompt("prompt_grammar"),
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            mimeType = mimeType,
            apiTypeLabel = AnalysisModule.GRAMMAR.id,
            primaryConfigs = primaryConfigs,
            backupConfigs = backupConfigs,
            onRetry = onRetry,
            onBackup = onBackup,
            recordId = recordId,
            stepName = stepName,
            timeoutMs = GRAMMAR_STEP_TIMEOUT_MS
        ) { accumulated ->
            val lines = accumulated.lines().map { it.trim() }.filter { it.isNotBlank() }
            val points = lines.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 2) {
                    com.example.japanesegrammarapp.domain.model.DetailedGrammarPoint(
                        pattern = parts[0],
                        explanation = parts.drop(1).joinToString("|")
                    )
                } else null
            }
            DetailedAnalysisResult(grammarPoints = points)
        }
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
        stepName: String?
    ): Flow<Pair<DetailedAnalysisResult?, LlmResultMetadata>> = flow {
        val nonPunctuationTokens = tokens.filter { !isPunctuationToken(it) }

        if (nonPunctuationTokens.isEmpty()) {
            val finalSegments = tokens.map { getPunctuationSegment(it) }
            val result = DetailedAnalysisResult(segments = finalSegments)
            emit(Pair(result, LlmResultMetadata(0, 0, 0)))
            return@flow
        }

        val tokensStr = nonPunctuationTokens.joinToString(", ")
        val userPrompt = if (text.isNotBlank()) {
            "分析対象の文: \"$text\"\nユーザーが提供したトークン配列（記号除く）: $tokensStr\n各トークンの詳細な文法分析を行ってください。"
        } else {
            "画像内の日本語テキストのトークン配列（記号除く）: $tokensStr\n各トークンの詳細な文法分析を行ってください。"
        }

        executeAnalysisStepStreaming(
            systemPrompt = settingsRepository.getCustomPrompt("prompt_segments"),
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            mimeType = mimeType,
            apiTypeLabel = AnalysisModule.SEGMENTS.id,
            primaryConfigs = primaryConfigs,
            backupConfigs = backupConfigs,
            onRetry = onRetry,
            onBackup = onBackup,
            recordId = recordId,
            stepName = stepName,
            timeoutMs = DEFAULT_STEP_TIMEOUT_MS
        ) { accumulated ->
            val lines = accumulated.lines().map { it.trim() }.filter { it.isNotBlank() }
            val segments = lines.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 8) {
                    val pos = parts[3].trim().takeIf { it.isNotBlank() && it != "null" }
                    WordSegment(
                        text = parts[0].trim(),
                        reading = parts[1].trim().takeIf { it.isNotBlank() && it != "null" },
                        meaning = parts[2].trim().takeIf { it.isNotBlank() && it != "null" },
                        partOfSpeech = pos,
                        posCategory = derivePosCategory(pos),
                        dictionaryForm = parts[4].trim().takeIf { it.isNotBlank() && it != "null" },
                        dictionaryFormReading = parts[5].trim().takeIf { it.isNotBlank() && it != "null" },
                        inflection = parts[6].trim().takeIf { it.isNotBlank() && it != "null" },
                        role = parts.drop(7).joinToString("|").trim().takeIf { it.isNotBlank() && it != "null" }
                    )
                } else if (parts.size >= 5) {
                    val pos = parts[3].trim().takeIf { it.isNotBlank() && it != "null" }
                    val remainingParts = parts.drop(4).map { it.trim() }.filter { it.isNotBlank() && it != "null" }
                    WordSegment(
                        text = parts[0].trim(),
                        reading = parts[1].trim().takeIf { it.isNotBlank() && it != "null" },
                        meaning = parts[2].trim().takeIf { it.isNotBlank() && it != "null" },
                        partOfSpeech = pos,
                        posCategory = derivePosCategory(pos),
                        role = remainingParts.joinToString(" | ").takeIf { it.isNotBlank() }
                    )
                } else null
            }

            val returnedSegments = segments
            val finalSegments = alignTokensWithSegments(tokens, returnedSegments)
            DetailedAnalysisResult(segments = finalSegments)
        }.collect {
            emit(it)
        }
    }

    private fun <T> executeAnalysisStepStreaming(
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        apiTypeLabel: String,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit,
        recordId: Int?,
        stepName: String?,
        timeoutMs: Long,
        parser: (String) -> T?
    ): Flow<Pair<T?, LlmResultMetadata>> = flow {
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
            
            withTimeout(timeoutMs) {
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
                        is com.example.japanesegrammarapp.domain.model.LlmStreamEvent.Chunk -> {
                            accumulatedText += event.text
                            val cleanText = accumulatedText.replace(THINKING_REGEX, "").trimStart()
                            val parsed = parser(cleanText)
                            emit(Pair(parsed, currentMetadata))
                        }
                        is com.example.japanesegrammarapp.domain.model.LlmStreamEvent.ThoughtChunk -> {
                            accumulatedThoughtText += event.text
                        }
                        is com.example.japanesegrammarapp.domain.model.LlmStreamEvent.Metadata -> {
                            providerLabel = event.provider
                            modelLabel = event.modelName
                            currentMetadata = event.usage
                            val cleanText = accumulatedText.replace(THINKING_REGEX, "").trimStart()
                            val parsed = parser(cleanText)
                            emit(Pair(parsed, currentMetadata))
                        }
                    }
                }
            }
            val thinkMatches = THINKING_REGEX.findAll(accumulatedText)
            val extractedThink = thinkMatches.map { it.groupValues.getOrNull(1)?.trim() ?: "" }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()
            val finalRawResponse = accumulatedText.replace(THINKING_REGEX, "").trimStart()
            
            val finalReasoningContent = if (accumulatedThoughtText.isNotBlank()) {
                if (extractedThink.isNotBlank()) {
                    "$accumulatedThoughtText\n$extractedThink"
                } else {
                    accumulatedThoughtText
                }
            } else if (extractedThink.isNotBlank()) {
                extractedThink
            } else if (currentMetadata.reasoningTokens > 0) {
                // 模型进行了内部推理但 API 未返回明文 thought text（隐式思考场景，如 gemini-3.1-flash-lite）
                "[モデルは内部推論を行いました (${currentMetadata.reasoningTokens} tokens 消費)。このモデルはAPIに思考テキストを返しません]"
            } else {
                null
            }

            val reasoningLevel = settingsRepository.getEffectiveReasoningLevel(apiTypeLabel).name

            AppLogger.apiSuccess(
                apiTypeLabel = apiTypeLabel,
                provider = providerLabel,
                model = modelLabel,
                hasImage = imageBase64 != null,
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
            val message = "Step timed out after ${timeoutMs / 1000}s"
            val cleanRawResponse = accumulatedText.replace(THINKING_REGEX, "").trimStart()
            val reasoningLevel = settingsRepository.getEffectiveReasoningLevel(apiTypeLabel).name
            AppLogger.apiError(
                apiTypeLabel = apiTypeLabel,
                provider = providerLabel,
                model = modelLabel,
                hasImage = imageBase64 != null,
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
                hasImage = imageBase64 != null,
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            val cleanRawResponse = accumulatedText.replace(THINKING_REGEX, "").trimStart()
            val reasoningLevel = settingsRepository.getEffectiveReasoningLevel(apiTypeLabel).name
            AppLogger.apiError(
                apiTypeLabel = apiTypeLabel,
                provider = providerLabel,
                model = modelLabel,
                hasImage = imageBase64 != null,
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

    private fun normalizeTokenizerTokens(tokens: List<String>): List<String> {
        return tokens.flatMap { splitTokenizerToken(it.trim()) }.filter { it.isNotBlank() }
    }

    private fun splitTokenizerToken(token: String): List<String> {
        if (token.isBlank()) return emptyList()
        if (isPunctuationToken(token)) return listOf(token)

        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        var index = 0

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                parts.add(buffer.toString())
                buffer.clear()
            }
        }

        while (index < token.length) {
            val char = token[index]
            val dotRunLength = if (char == '.') countDotRun(token, index) else 0
            if (dotRunLength >= 2) {
                flushBuffer()
                parts.add(token.substring(index, index + dotRunLength))
                index += dotRunLength
            } else if (isTokenizerBoundaryPunctuation(char)) {
                flushBuffer()
                parts.add(char.toString())
                index++
            } else {
                buffer.append(char)
                index++
            }
        }
        flushBuffer()

        return parts.ifEmpty { listOf(token) }
    }

    private fun countDotRun(token: String, start: Int): Int {
        var end = start
        while (end < token.length && token[end] == '.') {
            end++
        }
        return end - start
    }

    private fun isTokenizerBoundaryPunctuation(c: Char): Boolean {
        return c in TOKENIZER_BOUNDARY_PUNCTUATION_CHARS
    }

    private fun cleanWordForMatching(text: String?): String {
        if (text == null) return ""
        return text.replace(Regex("[\\s\\uFE00-\\uFE0F]"), "")
            .trim { it in PUNCTUATION_CHARS || it.isWhitespace() }
    }

    private fun alignTokensWithSegments(
        tokens: List<String>,
        returnedSegments: List<WordSegment>
    ): List<WordSegment> {
        val finalSegments = mutableListOf<WordSegment>()
        var returnedIdx = 0

        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.isBlank()) continue

            if (isPunctuationToken(token)) {
                finalSegments.add(getPunctuationSegment(token))
                continue
            }

            if (returnedIdx >= returnedSegments.size) {
                finalSegments.add(WordSegment(text = token))
                continue
            }

            val currentReturned = returnedSegments[returnedIdx]
            val tokenClean = cleanWordForMatching(token)
            val returnedClean = cleanWordForMatching(currentReturned.text)

            val directMatch = (token == currentReturned.text) ||
                (tokenClean.isNotEmpty() && tokenClean == returnedClean) ||
                (tokenClean.isNotEmpty() && (token.startsWith(returnedClean) || currentReturned.text?.startsWith(tokenClean) == true))

            if (directMatch) {
                finalSegments.add(currentReturned.copy(text = token))
                returnedIdx++
                continue
            }

            // Lookahead in returnedSegments (up to window of 3)
            var matchedLookaheadIdx = -1
            val maxLookahead = minOf(returnedSegments.size, returnedIdx + 4)
            for (j in (returnedIdx + 1) until maxLookahead) {
                val cand = returnedSegments[j]
                val candClean = cleanWordForMatching(cand.text)
                if (token == cand.text || (tokenClean.isNotEmpty() && tokenClean == candClean)) {
                    matchedLookaheadIdx = j
                    break
                }
            }

            // Check if currentReturned matches a future token in tokens
            var currentMatchesFutureToken = false
            val maxFutureToken = minOf(tokens.size, i + 4)
            for (k in (i + 1) until maxFutureToken) {
                val futureTokenClean = cleanWordForMatching(tokens[k])
                if (futureTokenClean.isNotEmpty() && futureTokenClean == returnedClean) {
                    currentMatchesFutureToken = true
                    break
                }
            }

            if (matchedLookaheadIdx != -1 && !currentMatchesFutureToken) {
                finalSegments.add(returnedSegments[matchedLookaheadIdx].copy(text = token))
                returnedIdx = matchedLookaheadIdx + 1
            } else if (currentMatchesFutureToken) {
                finalSegments.add(WordSegment(text = token))
            } else {
                finalSegments.add(currentReturned.copy(text = token))
                returnedIdx++
            }
        }

        return finalSegments
    }

    private fun getPunctuationSegment(token: String): WordSegment {
        val trimmed = token.trim()
        val cleaned = trimmed.replace(Regex("[\uFE00-\uFE0F]"), "")
        val (reading, meaning, role) = when {
            cleaned == "。" -> Triple("くてん", "（句号）", "文の終わりを示す句点。")
            cleaned == "、" -> Triple("とうてん", "（逗号）", "文の区切りを示す読点。")
            cleaned == "・" -> Triple("なかぐろ", "（间隔号）", "名詞の並列や、外来語・外国語の区切りを示す中黒。")
            cleaned == "？" || cleaned == "?" -> Triple("ぎもんふ", "（问号）", "疑問や問いかけを表す疑問符（クエスチョンマーク）。")
            cleaned == "！" || cleaned == "!" -> Triple("かんたんふ", "（感叹号）", "強い感情、驚き、または命令を表す感嘆符。")
            cleaned == "‼" || cleaned == "!!" || cleaned == "！！" -> Triple("にじゅうかんたんふ", "（双感叹号）", "強い感情、驚き、または命令を強調して表す感嘆符。")
            cleaned == "⁉" || cleaned == "⁈" || cleaned == "!?" || cleaned == "?!" || cleaned == "！？" || cleaned == "？！" -> Triple("ぎもんかんたんふ", "（疑问感叹号）", "驚きと疑問が混ざった感情を表す感嘆符疑問符。")
            cleaned == "⁇" || cleaned == "??" || cleaned == "？？" -> Triple("ぎもんふ", "（双问号）", "強い疑問や困惑を表す疑問符。")
            cleaned == "「" -> Triple("かぎかっこ", "（前引号）", "会話、引用、または強調する語句の開始を示す鉤括弧。")
            cleaned == "」" -> Triple("かぎかっこ", "（后引号）", "会話、引用、または強調する語句の終了を示す鉤括弧。")
            cleaned == "〝" -> Triple("かぎかっこ", "（前双引号）", "引用や強調を示す二重引用符の開始（ノの字形ダブルクォーテーション）。")
            cleaned == "〟" -> Triple("かぎかっこ", "（后双引号）", "引用や強調を示す二重引用符の終了（ノの字形ダブルクォーテーション）。")
            cleaned == "『" -> Triple("にじゅうかぎかっこ", "（前双引号）", "書名、作品名、または鉤括弧内での引用の開始を示す二重鉤括弧。")
            cleaned == "』" -> Triple("にじゅうかぎかっこ", "（后双引号）", "書名、作品名、または鉤括弧内での引用の終了を示す二重鉤括弧。")
            cleaned == "（" || cleaned == "(" -> Triple("かっこ", "（前括号）", "注記、補足説明、または読み仮名の開始を示す丸括弧。")
            cleaned == "）" || cleaned == ")" -> Triple("かっこ", "（后括号）", "注記、補足説明、または読み仮名の終了を示す丸括弧。")
            cleaned == "【" -> Triple("すみつきかっこ", "（前方括号）", "見出し、強調、または特別な分類の開始を示す隅付き括弧。")
            cleaned == "】" -> Triple("すみつきかっこ", "（后方括号）", "見出し、強調、または特別な分類の終了を示す隅付き括弧。")
            cleaned == "〔" -> Triple("きっこうかっこ", "（前六角括号）", "引用内の補足、注記、または記号の開始を示す亀甲括弧。")
            cleaned == "〕" -> Triple("きっこうかっこ", "（后六角括号）", "引用内の補足、注記、または記号の終了を示す亀甲括弧。")
            cleaned == "［" || cleaned == "[" -> Triple("かくかっこ", "（前中括号）", "注記、編集上の挿入、またはグループ化の開始を示す角括弧。")
            cleaned == "］" || cleaned == "]" -> Triple("かくかっこ", "（后中括号）", "注記、編集上の挿入、またはグループ化の終了を示す角括弧。")
            cleaned == "｛" || cleaned == "{" -> Triple("なみかっこ", "（前大括号）", "複数の選択肢やグループの開始を示す波括弧。")
            cleaned == "｝" || cleaned == "}" -> Triple("なみかっこ", "（后大括号）", "複数の選択肢やグループの終了を示す波括弧。")
            cleaned == "〜" || cleaned == "～" || cleaned == "~" || cleaned == "〰" -> Triple("なみだっしゅ", "（波浪号）", "範囲（〜から〜まで）や起点を示す波ダッシュ。")
            cleaned.isEllipsisLike() -> Triple("さんてんりーだー", "（省略号）", "言葉の省略、余韻、または沈黙を示す三点リーダー。")
            cleaned == "：" || cleaned == ":" -> Triple("ころん", "（冒号）", "説明、例示、または引用の提示を示すコロン。")
            cleaned == "；" || cleaned == ";" -> Triple("せみころん", "（分号）", "文の緊密な関係にある節を区切るセミコロン。")
            cleaned == "―" || cleaned == "—" -> Triple("だっしゅ", "（破折号）", "話の転換、説明の挿入、または余韻を示すダッシュ。")
            cleaned.matches(Regex("^[wW]+$")) -> Triple("わら", "（笑）", "ネットスラングで笑いを表す記号。")
            else -> Triple("きごう", "（符号）", "記号または補助記号。")
        }
        return WordSegment(
            text = token,
            reading = reading,
            partOfSpeech = "補助記号",
            posCategory = "OTHER",
            dictionaryForm = null,
            dictionaryFormReading = null,
            meaning = meaning,
            inflection = null,
            role = role
        )
    }

    private fun String.isEllipsisLike(): Boolean {
        return isNotBlank() && all { it == '.' || it == '…' }
    }
}
