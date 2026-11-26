package com.example.japanesegrammarapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecisionPosTaxonomyTest {

    @Test
    fun standardCriteriaContainsAllTwelveCategories() {
        assertEquals(12, DecisionPosTaxonomy.STANDARD_POS_CRITERIA.size)
    }

    @Test
    fun mapsAllTwelveChoicesToPosCategoryAndDisplayLabel() {
        val expected = mapOf(
            "noun" to ("NOUN" to "名詞"),
            "verb" to ("VERB" to "動詞"),
            "adjective" to ("ADJECTIVE" to "形容詞"),
            "adjectival_noun" to ("ADJECTIVE" to "形容動詞"),
            "adverb" to ("ADVERB" to "副詞"),
            "adnominal" to ("PRE_NOUN_ADJECTIVAL" to "連体詞"),
            "conjunction" to ("CONJUNCTION" to "接続詞"),
            "interjection" to ("INTERJECTION" to "感動詞"),
            "particle" to ("PARTICLE" to "助詞"),
            "auxiliary" to ("AUXILIARY" to "助動詞"),
            "affix" to ("AFFIX" to "接辞"),
            "symbol" to ("SYMBOL" to "補助記号")
        )

        expected.forEach { (choice, pair) ->
            assertEquals(pair.first, DecisionPosTaxonomy.toPosCategory(choice))
            assertEquals(pair.second, DecisionPosTaxonomy.toDisplayPartOfSpeech(choice))
        }
    }

    @Test
    fun normalizesWhitespaceAndCasingAndHandlesUnknowns() {
        assertEquals("VERB", DecisionPosTaxonomy.toPosCategory("  VeRb \n"))
        assertEquals("動詞", DecisionPosTaxonomy.toDisplayPartOfSpeech("  VERB "))
        assertEquals("OTHER", DecisionPosTaxonomy.toPosCategory("unknown_tag"))
        assertEquals("OTHER", DecisionPosTaxonomy.toPosCategory(null))
        assertNull(DecisionPosTaxonomy.toDisplayPartOfSpeech("unknown_tag"))
        assertNull(DecisionPosTaxonomy.toDisplayPartOfSpeech(null))
    }
}
