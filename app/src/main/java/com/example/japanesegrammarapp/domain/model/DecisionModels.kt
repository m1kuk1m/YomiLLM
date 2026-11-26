package com.example.japanesegrammarapp.domain.model

/**
 * Configuration constants for the standalone Gemini Decisions API.
 */
object DecisionConfig {
    const val PROVIDER_ID = "Gemini Decisions"
    const val DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta/decisions"
    const val DEFAULT_MODEL = "gemini-decision-1"
    const val API_TYPE_LABEL = "decision_pos"
}

/**
 * Generic single question inside a Decisions API batch request.
 */
data class DecisionQuestion(
    val type: String,
    val instructions: String,
    val criteria: Map<String, String>? = null
)

/**
 * Generic answer returned by the Decisions API for a single question key.
 */
data class DecisionAnswer(
    val choice: String? = null,
    val noul: Double? = null,
    val confidence: Double = 0.0,
    val probabilities: Map<String, Double> = emptyMap()
)

/**
 * Aggregated result from a single-pass Decisions API request.
 */
data class DecisionBatchResult(
    val answers: Map<String, DecisionAnswer>,
    val provider: String,
    val model: String,
    val consumedTokens: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

/**
 * 12-category Japanese POS taxonomy benchmarked with gemini-decision-1 and mapped to
 * the app's ZenThemeColors posCategory and display partOfSpeech labels.
 */
object DecisionPosTaxonomy {
    val STANDARD_POS_CRITERIA: Map<String, String> = linkedMapOf(
        "noun" to "名詞（普通名詞、固有名詞、代名詞、形式名詞、数詞など）",
        "verb" to "動詞（動詞、複合動詞、補助動詞、活用語など）",
        "adjective" to "形容詞（イ形容詞など）",
        "adjectival_noun" to "形容動詞（ナ形容詞、形状詞など）",
        "adverb" to "副詞（状態・程度修飾、擬音語・擬態語など）",
        "adnominal" to "連体詞（体言のみを修飾する不変化詞：「この」「その」「ある」など）",
        "conjunction" to "接続詞（文や節を接続する語：「そして」「しかし」「だから」など）",
        "interjection" to "感動詞（応答、呼びかけ、感嘆：「はい」「ああ」「ええ」など）",
        "particle" to "助詞（格助詞、係助詞、副助詞、接続助詞、終助詞、並立助詞など）",
        "auxiliary" to "助動詞（「だ」「です」「ない」「れる」「られる」「た」「ます」など）",
        "affix" to "接辞（接頭辞、接尾辞など）",
        "symbol" to "記号（句点、読点、括弧、補助記号、約物など）"
    )

    private val CATEGORY_MAP = mapOf(
        "noun" to "NOUN",
        "verb" to "VERB",
        "adjective" to "ADJECTIVE",
        "adjectival_noun" to "ADJECTIVE",
        "adverb" to "ADVERB",
        "adnominal" to "PRE_NOUN_ADJECTIVAL",
        "conjunction" to "CONJUNCTION",
        "interjection" to "INTERJECTION",
        "particle" to "PARTICLE",
        "auxiliary" to "AUXILIARY",
        "affix" to "AFFIX",
        "symbol" to "SYMBOL"
    )

    private val DISPLAY_POS_MAP = mapOf(
        "noun" to "名詞",
        "verb" to "動詞",
        "adjective" to "形容詞",
        "adjectival_noun" to "形容動詞",
        "adverb" to "副詞",
        "adnominal" to "連体詞",
        "conjunction" to "接続詞",
        "interjection" to "感動詞",
        "particle" to "助詞",
        "auxiliary" to "助動詞",
        "affix" to "接辞",
        "symbol" to "補助記号"
    )

    fun toPosCategory(choice: String?): String {
        val normalized = choice?.trim()?.lowercase() ?: return "OTHER"
        return CATEGORY_MAP[normalized] ?: "OTHER"
    }

    fun toDisplayPartOfSpeech(choice: String?): String? {
        val normalized = choice?.trim()?.lowercase() ?: return null
        return DISPLAY_POS_MAP[normalized]
    }
}
