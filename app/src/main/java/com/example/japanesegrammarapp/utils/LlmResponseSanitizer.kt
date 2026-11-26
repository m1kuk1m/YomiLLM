package com.example.japanesegrammarapp.utils

object LlmResponseSanitizer {
    val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
    private val OPENING_FENCE_REGEX = Regex("^```[a-zA-Z0-9_-]*\\r?\\n")
    private val CLOSING_FENCE_REGEX = Regex("\\r?\\n```\\s*$")
    private val FENCE_ONLY_REGEX = Regex("^```[a-zA-Z0-9_-]*$")
    private val OPENING_OUTPUT_TAG_REGEX = Regex("^<output>\\s*", RegexOption.IGNORE_CASE)
    private val CLOSING_OUTPUT_TAG_REGEX = Regex("\\s*</output>$", RegexOption.IGNORE_CASE)

    /**
     * 思考ブロック（<think>）、最外層の Markdown コード囲み（```）、
     * および偶発的に出力された <output>...</output> ラッパーを安全に除去する（ストリーミング対応）。
     */
    fun cleanModelOutput(raw: String): String {
        var cleaned = raw.replace(THINKING_REGEX, "").trim()
        repeat(2) {
            if (cleaned.isEmpty() || FENCE_ONLY_REGEX.matches(cleaned)) return ""
            val before = cleaned
            if (OPENING_FENCE_REGEX.containsMatchIn(cleaned)) {
                cleaned = cleaned.replaceFirst(OPENING_FENCE_REGEX, "")
                cleaned = cleaned.replaceFirst(CLOSING_FENCE_REGEX, "").trim()
            }
            if (OPENING_OUTPUT_TAG_REGEX.containsMatchIn(cleaned)) {
                cleaned = cleaned.replaceFirst(OPENING_OUTPUT_TAG_REGEX, "")
                cleaned = cleaned.replaceFirst(CLOSING_OUTPUT_TAG_REGEX, "").trim()
            } else if (CLOSING_OUTPUT_TAG_REGEX.containsMatchIn(cleaned)) {
                cleaned = cleaned.replaceFirst(CLOSING_OUTPUT_TAG_REGEX, "").trim()
            }
            if (cleaned == before) return cleaned
        }
        return if (FENCE_ONLY_REGEX.matches(cleaned)) "" else cleaned
    }

    /**
     * 読み欄に含まれる全角カタカナ（ァ–ヶ、ヽ/ヾ）をひらがな（ぁ–ゖ、ゝ/ゞ）へ正規化する。
     * 長音符「ー」やその他の文字はそのまま保持する。
     */
    fun normalizeReadingToHiragana(reading: String?): String? {
        if (reading.isNullOrBlank()) return reading
        val sb = StringBuilder(reading.length)
        for (ch in reading) {
            sb.append(
                when (ch) {
                    in '\u30A1'..'\u30F6' -> (ch.code - 0x60).toChar()
                    '\u30FD' -> '\u309D'
                    '\u30FE' -> '\u309E'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }
}
