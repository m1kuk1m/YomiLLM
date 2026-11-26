package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.WordSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentMergeHelperTest {

    @Test
    fun mergeDecisionPosAppliesColorCategoryAndFallbackPosOnExactAlignment() {
        val existing = listOf(
            WordSegment(text = "猫", reading = "ねこ", partOfSpeech = "", meaning = "猫"),
            WordSegment(text = "が", reading = "が", partOfSpeech = "格助詞", meaning = "主格")
        )
        val decision = listOf(
            WordSegment(text = "猫", partOfSpeech = "名詞", posCategory = "NOUN"),
            WordSegment(text = "が", partOfSpeech = "助詞", posCategory = "PARTICLE")
        )

        val merged = SegmentMergeHelper.mergeDecisionPos(existing, decision)

        assertEquals(listOf("NOUN", "PARTICLE"), merged.map { it.posCategory })
        // Blank partOfSpeech takes decision fallback; non-blank detail partOfSpeech is preserved
        assertEquals(listOf("名詞", "格助詞"), merged.map { it.partOfSpeech })
        assertEquals(listOf("ねこ", "が"), merged.map { it.reading })
        assertEquals(listOf("猫", "主格"), merged.map { it.meaning })
    }

    @Test
    fun mergeDetailSegmentsAppliesDecisionPosWithShiftedTokenAlignment() {
        val detail = listOf(
            WordSegment(text = "猫", reading = "ねこ", partOfSpeech = "", meaning = "猫", role = "主语"),
            WordSegment(text = "！", reading = "", partOfSpeech = "補助記号", meaning = "感叹号"),
            WordSegment(text = "走る", reading = "はしる", partOfSpeech = "", meaning = "跑", role = "谓语")
        )
        val decision = listOf(
            WordSegment(text = "猫", partOfSpeech = "名詞", posCategory = "NOUN"),
            WordSegment(text = "走る", partOfSpeech = "動詞", posCategory = "VERB")
        )

        val merged = SegmentMergeHelper.mergeDetailSegments(detail, decision)

        assertEquals(listOf("NOUN", null, "VERB"), merged.map { it.posCategory })
        assertEquals(listOf("名詞", "補助記号", "動詞"), merged.map { it.partOfSpeech })
        assertEquals(listOf("主语", null, "谓语"), merged.map { it.role })
    }

    @Test
    fun mergeDecisionPosReturnsDecisionDirectlyWhenExistingEmpty() {
        val decision = listOf(
            WordSegment(text = "走っ", partOfSpeech = "動詞", posCategory = "VERB")
        )
        assertEquals(decision, SegmentMergeHelper.mergeDecisionPos(null, decision))
        assertEquals(decision, SegmentMergeHelper.mergeDecisionPos(emptyList(), decision))
    }
}
