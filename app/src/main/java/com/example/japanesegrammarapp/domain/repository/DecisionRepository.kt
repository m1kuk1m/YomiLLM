package com.example.japanesegrammarapp.domain.repository

import com.example.japanesegrammarapp.domain.model.DecisionBatchResult
import com.example.japanesegrammarapp.domain.model.DecisionQuestion
import com.example.japanesegrammarapp.domain.model.WordSegment

/**
 * Abstraction for single-pass decision-model inference (e.g. Gemini Decisions API).
 * Supports both generic multi-question decision evaluation and specialized Japanese
 * token POS classification for immediate card coloring.
 */
interface DecisionRepository {
    suspend fun evaluateDecisions(
        input: String,
        questions: Map<String, DecisionQuestion>,
        configs: List<LlmApiConfig>,
        apiTypeLabel: String,
        recordId: Int? = null,
        stepName: String? = null
    ): DecisionBatchResult

    suspend fun classifyTokenPos(
        sentence: String,
        tokens: List<String>,
        configs: List<LlmApiConfig>,
        recordId: Int? = null,
        stepName: String? = null
    ): Pair<List<WordSegment>, LlmResultMetadata>
}
