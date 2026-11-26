package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.DecisionAnswer
import com.example.japanesegrammarapp.domain.model.DecisionBatchResult
import com.example.japanesegrammarapp.domain.model.DecisionConfig
import com.example.japanesegrammarapp.domain.model.DecisionPosTaxonomy
import com.example.japanesegrammarapp.domain.model.DecisionQuestion
import com.example.japanesegrammarapp.domain.model.WordSegment
import com.example.japanesegrammarapp.domain.model.isPunctuationToken
import com.example.japanesegrammarapp.domain.repository.DecisionRepository
import com.example.japanesegrammarapp.domain.repository.EndpointHealthTracker
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.LlmResultMetadata
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.network.DecisionQuestionPayload
import com.example.japanesegrammarapp.network.DecisionRequest
import com.example.japanesegrammarapp.network.LlmApiService
import com.example.japanesegrammarapp.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DecisionRepositoryImpl @Inject constructor(
    private val llmApiService: LlmApiService,
    private val settingsRepository: SettingsRepository,
    private val endpointHealthTracker: EndpointHealthTracker,
    private val gson: Gson
) : DecisionRepository {

    private companion object {
        const val DECISION_TIMEOUT_MS = 30_000L
        const val MAX_RETRY_PER_ENDPOINT = 1
        const val RETRY_DELAY_MS = 800L
    }

    override suspend fun evaluateDecisions(
        input: String,
        questions: Map<String, DecisionQuestion>,
        configs: List<LlmApiConfig>,
        apiTypeLabel: String,
        recordId: Int?,
        stepName: String?
    ): DecisionBatchResult {
        if (configs.isEmpty()) {
            throw IllegalArgumentException("No enabled Decision API endpoint is configured.")
        }
        if (questions.isEmpty()) {
            val first = configs.first()
            return DecisionBatchResult(
                answers = emptyMap(),
                provider = first.provider,
                model = first.modelName
            )
        }

        val payloadQuestions = questions.mapValues { (_, q) ->
            DecisionQuestionPayload(
                type = q.type,
                instructions = q.instructions,
                criteria = q.criteria
            )
        }
        val requestSummary = "Questions: ${questions.size} (${questions.keys.take(8).joinToString(", ")})"
        var lastException: Exception? = null

        for ((index, config) in configs.withIndex()) {
            val attemptNumber = index + 1
            val endpointLabel = config.endpointName.ifBlank {
                config.endpointId.ifBlank { "default" }
            }
            val modelName = config.modelName.ifBlank { DecisionConfig.DEFAULT_MODEL }
            val request = DecisionRequest(
                model = modelName,
                input = input,
                questions = payloadQuestions
            )
            val baseUrl = config.url.ifBlank { DecisionConfig.DEFAULT_URL }.trimEnd('/')
            val fullUrl = "$baseUrl?key=${config.key.trim()}"

            val maxTries = if (settingsRepository.getAutoRetryOnError()) 1 + MAX_RETRY_PER_ENDPOINT else 1
            for (tryIdx in 0 until maxTries) {
                val startMs = System.currentTimeMillis()
                try {
                    if (config.endpointId.isNotBlank()) {
                        endpointHealthTracker.touch(config.endpointId)
                    }
                    AppLogger.apiEvent(
                        apiTypeLabel = apiTypeLabel,
                        provider = config.provider,
                        model = modelName,
                        status = "START",
                        hasImage = false,
                        userPrompt = input,
                        systemPrompt = requestSummary,
                        message = "Decision request attempt $attemptNumber.${tryIdx + 1} via $endpointLabel",
                        recordId = recordId,
                        stepName = stepName,
                        attempt = attemptNumber
                    )

                    val response = withTimeout(DECISION_TIMEOUT_MS) {
                        llmApiService.executeDecisions(fullUrl, request)
                    }

                    if (config.endpointId.isNotBlank()) {
                        endpointHealthTracker.markSuccess(config.endpointId)
                    }

                    val mappedAnswers = response.answers.orEmpty().mapValues { (_, a) ->
                        DecisionAnswer(
                            choice = a.choice,
                            noul = a.noul,
                            confidence = a.confidence ?: 0.0,
                            probabilities = a.probabilities.orEmpty()
                        )
                    }
                    val totalTokens = response.usage?.totalTokens ?: 0
                    val promptTokens = response.usage?.promptTokens ?: 0
                    val candidateTokens = response.usage?.candidatesTokens ?: 0
                    val elapsedMs = System.currentTimeMillis() - startMs

                    val preview = mappedAnswers.entries.take(8).joinToString(", ") { (k, v) ->
                        "$k=${v.choice ?: v.noul}"
                    }
                    AppLogger.apiSuccess(
                        apiTypeLabel = apiTypeLabel,
                        provider = config.provider,
                        model = modelName,
                        hasImage = false,
                        userPrompt = input,
                        systemPrompt = requestSummary,
                        rawResponse = gson.toJson(response),
                        parsedPreview = preview,
                        consumedTokens = totalTokens,
                        inputTokens = promptTokens,
                        outputTokens = candidateTokens,
                        recordId = recordId,
                        stepName = stepName,
                        attempt = attemptNumber,
                        elapsedMs = elapsedMs
                    )

                    return DecisionBatchResult(
                        answers = mappedAnswers,
                        provider = config.provider,
                        model = modelName,
                        consumedTokens = totalTokens,
                        inputTokens = promptTokens,
                        outputTokens = candidateTokens
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val elapsedMs = System.currentTimeMillis() - startMs
                    lastException = e

                    val canRetrySameEndpoint = tryIdx + 1 < maxTries && isRetryable(e)
                    if (canRetrySameEndpoint) {
                        AppLogger.apiEvent(
                            apiTypeLabel = apiTypeLabel,
                            provider = config.provider,
                            model = modelName,
                            status = "RETRY",
                            hasImage = false,
                            userPrompt = input,
                            systemPrompt = requestSummary,
                            message = "Decision attempt failed (${e.localizedMessage}); retrying same endpoint $endpointLabel",
                            recordId = recordId,
                            stepName = stepName,
                            attempt = attemptNumber,
                            elapsedMs = elapsedMs
                        )
                        delay(RETRY_DELAY_MS * (tryIdx + 1))
                        continue
                    }

                    if (config.endpointId.isNotBlank()) {
                        endpointHealthTracker.markFailure(
                            endpointId = config.endpointId,
                            error = e.localizedMessage ?: "Unknown error",
                            cooldownMs = endpointCooldownMs(e)
                        )
                    }

                    val shouldFailover = settingsRepository.getFailoverToNextEndpoint() && index < configs.lastIndex
                    if (shouldFailover) {
                        AppLogger.apiEvent(
                            apiTypeLabel = apiTypeLabel,
                            provider = config.provider,
                            model = modelName,
                            status = "RETRY",
                            hasImage = false,
                            userPrompt = input,
                            systemPrompt = requestSummary,
                            message = "Decision endpoint $endpointLabel failed after ${elapsedMs}ms: ${e.localizedMessage}. Failing over.",
                            recordId = recordId,
                            stepName = stepName,
                            attempt = attemptNumber,
                            elapsedMs = elapsedMs
                        )
                        delay(400L)
                        break
                    } else {
                        AppLogger.apiError(
                            apiTypeLabel = apiTypeLabel,
                            provider = config.provider,
                            model = modelName,
                            hasImage = false,
                            userPrompt = input,
                            systemPrompt = requestSummary,
                            message = "Decision endpoint $endpointLabel failed after ${elapsedMs}ms: ${e.localizedMessage ?: "Unknown error"}",
                            throwable = e,
                            recordId = recordId,
                            stepName = stepName,
                            attempt = attemptNumber,
                            elapsedMs = elapsedMs
                        )
                        throw e
                    }
                }
            }
        }

        throw lastException ?: IllegalStateException("Decision API failed with unknown error")
    }

    override suspend fun classifyTokenPos(
        sentence: String,
        tokens: List<String>,
        configs: List<LlmApiConfig>,
        recordId: Int?,
        stepName: String?
    ): Pair<List<WordSegment>, LlmResultMetadata> {
        if (tokens.isEmpty()) {
            return Pair(emptyList(), LlmResultMetadata(0, 0, 0))
        }

        val questions = linkedMapOf<String, DecisionQuestion>()
        tokens.forEachIndexed { index, token ->
            if (!isPunctuationToken(token)) {
                val start = maxOf(0, index - 1)
                val end = minOf(tokens.size, index + 2)
                val localContext = tokens.subList(start, end).joinToString("")
                questions["tok_$index"] = DecisionQuestion(
                    type = "choice",
                    instructions = "文中の ${index + 1} 番目のトークン「$token」（局所文脈：「$localContext」）の品詞を選択してください。",
                    criteria = DecisionPosTaxonomy.STANDARD_POS_CRITERIA
                )
            }
        }

        if (questions.isEmpty()) {
            val segments = tokens.map { token ->
                WordSegment(
                    text = token,
                    partOfSpeech = "補助記号",
                    posCategory = "SYMBOL"
                )
            }
            return Pair(segments, LlmResultMetadata(0, 0, 0))
        }

        val effectiveInput = sentence.ifBlank { tokens.joinToString("") }
        val batchResult = evaluateDecisions(
            input = effectiveInput,
            questions = questions,
            configs = configs,
            apiTypeLabel = DecisionConfig.API_TYPE_LABEL,
            recordId = recordId,
            stepName = stepName
        )

        val segments = tokens.mapIndexed { index, token ->
            if (isPunctuationToken(token)) {
                WordSegment(
                    text = token,
                    partOfSpeech = "補助記号",
                    posCategory = "SYMBOL"
                )
            } else {
                val choice = batchResult.answers["tok_$index"]?.choice
                WordSegment(
                    text = token,
                    partOfSpeech = DecisionPosTaxonomy.toDisplayPartOfSpeech(choice),
                    posCategory = DecisionPosTaxonomy.toPosCategory(choice)
                )
            }
        }

        val metadata = LlmResultMetadata(
            consumedTokens = batchResult.consumedTokens,
            inputTokens = batchResult.inputTokens,
            outputTokens = batchResult.outputTokens
        )
        return Pair(segments, metadata)
    }

    private fun isRetryable(e: Exception): Boolean {
        val http = e.cause as? HttpException ?: e as? HttpException
        if (http != null) {
            return http.code() == 429 || http.code() >= 500
        }
        return e is java.io.IOException || e is kotlinx.coroutines.TimeoutCancellationException
    }

    private fun endpointCooldownMs(error: Exception): Long {
        val http = error.cause as? HttpException ?: error as? HttpException
        return when (http?.code()) {
            401, 403 -> 24 * 60 * 60 * 1000L
            429 -> 2 * 60 * 1000L
            in 500..599 -> 60 * 1000L
            else -> 30 * 1000L
        }
    }
}
