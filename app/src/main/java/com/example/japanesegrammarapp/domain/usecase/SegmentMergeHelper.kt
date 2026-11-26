package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.WordSegment

/**
 * Pure stateless helper for merging Decision Model POS classifications with
 * streaming SEGMENTS detail results without overwriting fields from either side.
 */
object SegmentMergeHelper {
    fun mergeDecisionPos(
        existingSegments: List<WordSegment>?,
        decisionSegments: List<WordSegment>
    ): List<WordSegment> {
        if (decisionSegments.isEmpty()) return existingSegments.orEmpty()
        if (existingSegments.isNullOrEmpty()) return decisionSegments

        if (existingSegments.size == decisionSegments.size &&
            existingSegments.indices.all { existingSegments[it].text == decisionSegments[it].text }
        ) {
            return existingSegments.mapIndexed { index, existing ->
                applyDecisionFields(existing, decisionSegments[index])
            }
        }

        val result = ArrayList<WordSegment>(existingSegments.size)
        var decisionIdx = 0
        for (existing in existingSegments) {
            var matched: WordSegment? = null
            for (j in decisionIdx until decisionSegments.size) {
                if (decisionSegments[j].text == existing.text) {
                    matched = decisionSegments[j]
                    decisionIdx = j + 1
                    break
                }
            }
            result.add(if (matched != null) applyDecisionFields(existing, matched) else existing)
        }
        return result
    }

    fun mergeDetailSegments(
        detailSegments: List<WordSegment>,
        decisionSegments: List<WordSegment>?
    ): List<WordSegment> {
        if (decisionSegments.isNullOrEmpty()) return detailSegments
        return mergeDecisionPos(detailSegments, decisionSegments)
    }

    private fun applyDecisionFields(
        target: WordSegment,
        decision: WordSegment
    ): WordSegment {
        val resolvedCategory = decision.posCategory?.takeIf { it.isNotBlank() && it != "OTHER" }
            ?: target.posCategory
            ?: decision.posCategory
        val resolvedPos = target.partOfSpeech?.takeIf { it.isNotBlank() }
            ?: decision.partOfSpeech
        return target.copy(
            posCategory = resolvedCategory,
            partOfSpeech = resolvedPos
        )
    }
}
