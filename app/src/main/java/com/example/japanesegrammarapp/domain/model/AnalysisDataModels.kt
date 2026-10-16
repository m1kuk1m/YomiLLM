package com.example.japanesegrammarapp.domain.model

data class TokenizationResult(
    val tokens: List<String>? = null,
    val correctedText: String? = null,
    val recognizedText: String? = null
)

data class WordSegment(
    val text: String? = null,
    val reading: String? = null,
    val partOfSpeech: String? = null,
    val posCategory: String? = null,
    val dictionaryForm: String? = null,
    val dictionaryFormReading: String? = null,
    val meaning: String? = null,
    val inflection: String? = null,
    val role: String? = null
)

/**
 * Single source of truth for "is this segment a punctuation / symbol card".
 *
 * Primary signal is the analyzer's POS tag: the analysis layer tags punctuation
 * with partOfSpeech = "補助記号" (covers both 記号 and 補助記号) and posCategory = "OTHER".
 * We also accept posCategory == "symbol" (any case) as a fallback for other producers.
 *
 * If the POS is missing/ambiguous, fall back to inspecting the surface text: if every
 * character is a known punctuation/symbol char, treat it as punctuation. This char set
 * is the union of what the UI and the two TTS paths used to check separately, so all
 * three now agree.
 */
val PUNCTUATION_CHARS = setOf(
    '。', '、', '・', '？', '！', '「', '」', '『', '』', '（', '）',
    '〔', '〕', '［', '］', '｛', '｝', '〜', '～', '…', '：', '；', '―', '—',
    '【', '】', '《', '》', '〈', '〉', '〝', '〟', '，', '．',
    '?', '!', '(', ')', '[', ']', '{', '}', ':', ';', ',', '.', '~', '-', '_',
    '/', '\\', '|', '<', '>', '"', '\'',
    '‼', '⁉', '⁇', '⁈', '〰'
)

fun isJapaneseTextChar(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
           block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
           block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
           block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
           block == Character.UnicodeBlock.HIRAGANA ||
           block == Character.UnicodeBlock.KATAKANA ||
           block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
}

fun isPunctuationToken(token: String?): Boolean {
    if (token == null) return false
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return true

    // Remove Unicode variation selectors (U+FE00 through U+FE0F)
    val cleaned = trimmed.replace(Regex("[\uFE00-\uFE0F]"), "")
    if (cleaned.isEmpty()) return true
    if (cleaned.all { it in PUNCTUATION_CHARS }) return true
    if (cleaned.matches(Regex("^[wW]+$"))) return true

    return cleaned.all { c ->
        if (c.isLetterOrDigit()) return@all false
        if (isJapaneseTextChar(c)) return@all false

        val type = Character.getType(c).toByte()
        val block = Character.UnicodeBlock.of(c)

        type == Character.START_PUNCTUATION ||
        type == Character.END_PUNCTUATION ||
        type == Character.OTHER_PUNCTUATION ||
        type == Character.CONNECTOR_PUNCTUATION ||
        type == Character.DASH_PUNCTUATION ||
        type == Character.INITIAL_QUOTE_PUNCTUATION ||
        type == Character.FINAL_QUOTE_PUNCTUATION ||
        type == Character.MATH_SYMBOL ||
        type == Character.CURRENCY_SYMBOL ||
        type == Character.MODIFIER_SYMBOL ||
        type == Character.OTHER_SYMBOL ||
        type == Character.SURROGATE ||
        type == Character.FORMAT ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
        block == Character.UnicodeBlock.GENERAL_PUNCTUATION ||
        block == Character.UnicodeBlock.SUPPLEMENTAL_PUNCTUATION ||
        block == Character.UnicodeBlock.VARIATION_SELECTORS
    }
}

fun WordSegment.isPunctuation(): Boolean {
    if (partOfSpeech?.contains("記号") == true) return true
    if (posCategory?.equals("symbol", ignoreCase = true) == true) return true
    val text = text?.trim() ?: return false
    if (text.isEmpty()) return false
    return isPunctuationToken(text)
}

private val CANONICAL_POS_CATEGORIES = setOf(
    "NOUN", "VERB", "ADJECTIVE", "AUXILIARY", "PARTICLE",
    "ADVERB", "CONJUNCTION", "PRONOUN", "PRE_NOUN_ADJECTIVAL",
    "INTERJECTION", "SYMBOL", "AFFIX", "PHRASE", "OTHER"
)

/**
 * Maps a partOfSpeech string (e.g. "動詞-一般", "格助词", "接尾辞", "suffix") to the coarse
 * category used for chip colors, grouping, and filters (NOUN, VERB, PARTICLE, ...).
 * Single source of truth across chip rendering, LLM parsing, and bookmarking.
 *
 * Designed with high fault tolerance for Simplified Chinese variations, Japanese abbreviations
 * (格助, 副助, 接助, etc.), particle subtypes, and compound POS descriptions.
 */
fun derivePosCategory(partOfSpeech: String?): String {
    val raw = partOfSpeech?.trim() ?: return "OTHER"
    if (raw.isEmpty()) return "OTHER"

    val upper = raw.uppercase()
    if (upper in CANONICAL_POS_CATEGORIES) {
        return upper
    }

    return when {
        // 1. Symbols and punctuation
        raw.contains("記号") || raw.contains("记号") || raw.contains("符号") ||
            raw.contains("标点") || raw.contains("標点") || raw.contains("句読点") ||
            raw.contains("symbol", ignoreCase = true) || raw.contains("punct", ignoreCase = true) -> "SYMBOL"

        // 2. Affixes / Prefixes / Suffixes (prioritized before NOUN/VERB to capture e.g. 名詞接尾辞, 名词后缀)
        raw.contains("接尾") || raw.contains("接頭") || raw.contains("接头") ||
            raw.contains("接辞") || raw.contains("接缀") || raw.contains("接綴") ||
            raw.contains("后缀") || raw.contains("後綴") || raw.contains("前缀") || raw.contains("前綴") ||
            raw.contains("词缀") || raw.contains("詞綴") || raw.contains("词尾") || raw.contains("詞尾") ||
            raw.contains("affix", ignoreCase = true) || raw.contains("suffix", ignoreCase = true) ||
            raw.contains("prefix", ignoreCase = true) -> "AFFIX"

        // 3. Interjections (prioritized before VERB because 感動詞/感动词 contains 動詞/动词)
        raw.contains("感動詞") || raw.contains("感动词") ||
            raw.contains("感嘆詞") || raw.contains("感叹词") ||
            raw.contains("間投詞") || raw.contains("间投词") ||
            raw.contains("叹词") || raw.contains("嘆詞") ||
            raw.contains("interjection", ignoreCase = true) || raw.equals("int", ignoreCase = true) -> "INTERJECTION"

        // 4. Adjectives / Adjectival Nouns (prioritized before VERB because 形容動詞/形容动词 contains 動詞/动词)
        raw.contains("形容") || raw.contains("形状") || raw.contains("形動") || raw.contains("形动") ||
            raw.contains("判定詞") || raw.contains("判定词") ||
            raw.contains("adjective", ignoreCase = true) || raw.equals("adj", ignoreCase = true) -> "ADJECTIVE"

        // 5. Auxiliaries (prioritized before VERB to prevent 助動詞/助动词 matching 動詞/动词, but excluding 補助動詞/补助动词)
        (!raw.contains("補助動") && !raw.contains("补助动")) &&
            (raw.contains("助動詞") || raw.contains("助动词") || raw.contains("助動") || raw.contains("助动") ||
            raw.contains("auxiliary", ignoreCase = true) || raw.equals("aux", ignoreCase = true)) -> "AUXILIARY"

        // 6. Particles (comprehensive Japanese, Simplified Chinese & abbreviations)
        // Prioritized before ADVERB/CONJUNCTION to correctly classify 副助詞/接続助詞
        raw.contains("助詞") || raw.contains("助词") ||
            raw.contains("语气") || raw.contains("語気") ||
            raw.contains("格助") || raw.contains("副助") ||
            raw.contains("係助") || raw.contains("係り助") || raw.contains("系助") ||
            raw.contains("接助") || raw.contains("接続助") || raw.contains("接续助") ||
            raw.contains("終助") || raw.contains("终助") ||
            raw.contains("並助") || raw.contains("并助") || raw.contains("並立助") || raw.contains("并列助") ||
            raw.contains("間助") || raw.contains("间助") || raw.contains("間投助") || raw.contains("间投助") ||
            raw.contains("準体助") || raw.contains("准体助") ||
            raw.contains("複合助") || raw.contains("复合助") ||
            raw.contains("取り立て") || raw.contains("提示助") ||
            raw.contains("嘆助") || raw.contains("叹助") || raw.contains("引用助") ||
            raw.contains("particle", ignoreCase = true) || raw.equals("prt", ignoreCase = true) -> "PARTICLE"

        // 7. Pronouns (prioritized before NOUN to prevent 代名詞/代名词 from matching 名詞/名词)
        raw.contains("代名詞") || raw.contains("代名词") || raw.contains("代詞") || raw.contains("代词") ||
            raw.contains("pronoun", ignoreCase = true) || raw.equals("pron", ignoreCase = true) -> "PRONOUN"

        // 8. Verbs (including 補助動詞/补助动词, 五段, 一段, サ変, カ変)
        raw.contains("動詞") || raw.contains("动词") ||
            raw.contains("サ変") || raw.contains("カ変") || raw.contains("五段") || raw.contains("一段") ||
            raw.contains("verb", ignoreCase = true) || raw.equals("v", ignoreCase = true) -> "VERB"

        // 9. Adverbs & Onomatopoeia
        raw.contains("副詞") || raw.contains("副词") ||
            raw.contains("擬態") || raw.contains("拟态") ||
            raw.contains("擬声") || raw.contains("拟声") ||
            raw.contains("象声") || raw.contains("オノマトペ") ||
            raw.contains("adverb", ignoreCase = true) || raw.equals("adv", ignoreCase = true) -> "ADVERB"

        // 10. Conjunctions
        raw.contains("接続詞") || raw.contains("接续词") || raw.contains("接续") || raw.contains("接続") ||
            raw.contains("连词") || raw.contains("連詞") || raw.contains("连接词") || raw.contains("连结词") ||
            raw.contains("conjunction", ignoreCase = true) || raw.equals("conj", ignoreCase = true) -> "CONJUNCTION"

        // 11. Pre-noun Adjectivals (Attributives)
        raw.contains("連体詞") || raw.contains("连体词") || raw.contains("連体") || raw.contains("连体") ||
            raw.contains("adnominal", ignoreCase = true) || raw.contains("prenoun", ignoreCase = true) -> "PRE_NOUN_ADJECTIVAL"

        // 12. Nouns & Numerals
        raw.contains("名詞") || raw.contains("名词") ||
            raw.contains("数詞") || raw.contains("数词") ||
            raw.contains("数量词") || raw.contains("数量詞") ||
            raw.contains("noun", ignoreCase = true) || raw.equals("n", ignoreCase = true) -> "NOUN"

        // 13. Phrases / Idioms
        raw.contains("連語") || raw.contains("连语") ||
            raw.contains("慣用句") || raw.contains("惯用句") ||
            raw.contains("慣用語") || raw.contains("惯用语") ||
            raw.contains("熟語") || raw.contains("熟语") ||
            raw.contains("成語") || raw.contains("成语") ||
            raw.contains("短语") || raw.contains("短語") ||
            raw.contains("词组") || raw.contains("詞組") ||
            raw.contains("phrase", ignoreCase = true) || raw.contains("idiom", ignoreCase = true) -> "PHRASE"

        else -> "OTHER"
    }
}

data class SentenceClause(
    val index: Int? = null,
    val role: String? = null,
    val text: String? = null,
    val explanation: String? = null
)

data class DetailedGrammarPoint(
    val pattern: String? = null,
    val explanation: String? = null
)

data class DetailedAnalysisResult(
    val translation: String? = null,
    val segments: List<WordSegment>? = null,
    val clauses: List<SentenceClause>? = null,
    val grammarPoints: List<DetailedGrammarPoint>? = null,
    var consumedTokens: Int = 0,
    var inputTokens: Int = 0,
    var outputTokens: Int = 0
)
