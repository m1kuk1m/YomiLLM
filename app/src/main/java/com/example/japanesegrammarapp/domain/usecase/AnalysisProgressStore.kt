package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.WordSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AnalysisProgressStore {
    private val _progressFlow = MutableStateFlow<Map<Int, AnalysisProgress>>(emptyMap())
    val progressFlow: StateFlow<Map<Int, AnalysisProgress>> = _progressFlow.asStateFlow()

    fun start(recordId: Int) {
        _progressFlow.update { it + (recordId to AnalysisProgress()) }
    }

    fun finish(recordId: Int) {
        _progressFlow.update { it - recordId }
    }

    fun updatePartialSegments(recordId: Int, segments: List<WordSegment>) {
        update(recordId) { it.copy(partialSegments = segments) }
    }

    fun setImageTask(recordId: Int, isImage: Boolean) {
        update(recordId) { it.copy(isImageTask = isImage) }
    }

    fun markImageOcrCompleted(recordId: Int) {
        update(recordId) { it.copy(imageOcrCompleted = true) }
    }

    fun markTokenizerCompleted(recordId: Int) {
        update(recordId) { it.copy(tokenizerCompleted = true) }
    }

    fun markSegmentsCompleted(recordId: Int) {
        update(recordId) { it.copy(segmentsCompleted = true) }
    }

    fun setDecisionPosEnabled(recordId: Int, enabled: Boolean) {
        update(recordId) { it.copy(decisionPosEnabled = enabled) }
    }

    fun markDecisionPosCompleted(recordId: Int) {
        update(recordId) { it.copy(decisionPosCompleted = true) }
    }

    fun markClausesCompleted(recordId: Int) {
        update(recordId) { it.copy(clausesCompleted = true) }
    }

    fun markTranslationCompleted(recordId: Int) {
        update(recordId) { it.copy(translationCompleted = true) }
    }

    fun markGrammarCompleted(recordId: Int) {
        update(recordId) { it.copy(grammarCompleted = true) }
    }

    fun markStepError(recordId: Int, stepName: String, errorMessage: String) {
        update(recordId) { it.copy(stepErrors = it.stepErrors + (stepName to errorMessage)) }
    }

    private fun update(recordId: Int, block: (AnalysisProgress) -> AnalysisProgress) {
        _progressFlow.update { map ->
            val current = map[recordId] ?: AnalysisProgress()
            map + (recordId to block(current))
        }
    }
}
