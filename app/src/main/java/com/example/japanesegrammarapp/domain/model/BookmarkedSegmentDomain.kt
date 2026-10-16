package com.example.japanesegrammarapp.domain.model

data class BookmarkedSegmentDomain(
    val id: Int = 0,
    val recordId: Int,
    /** The dictionary/citation form used as the bookmark key */
    val segmentText: String,
    /** The original surface form as it appeared in the sentence */
    val surfaceForm: String? = null,
    val reading: String? = null,
    val partOfSpeech: String? = null,
    val posCategory: String? = null,
    val dictionaryForm: String? = null,
    val dictionaryFormReading: String? = null,
    val meaning: String? = null,
    val inflection: String? = null,
    val role: String? = null,
    val bookmarkedAt: Long = System.currentTimeMillis(),
    val sourceText: String = "",
    val isArchived: Boolean = false
)

val BookmarkedSegmentDomain.effectivePosCategory: String
    get() {
        val cat = posCategory ?: ""
        if (cat.isNotBlank() && cat != "OTHER") return cat
        return derivePosCategory(partOfSpeech)
    }

