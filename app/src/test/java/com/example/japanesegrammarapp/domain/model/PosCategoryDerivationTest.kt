package com.example.japanesegrammarapp.domain.model

import com.example.japanesegrammarapp.ui.theme.ZenColors
import com.example.japanesegrammarapp.ui.theme.ZenThemeColors
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PosCategoryDerivationTest {

    @Test
    fun `affix color is updated to soft wisteria mauve and not washed out`() {
        val lightAffixColor = ZenThemeColors.PosColorsLight["AFFIX"]
        val darkAffixColor = ZenThemeColors.PosColorsDark["AFFIX"]

        // Must be the new soft wisteria mauve (#E0CEE3 / #36223B)
        assertEquals(Color(0xFFE0CEE3), lightAffixColor)
        assertEquals(Color(0xFF36223B), darkAffixColor)
        assertEquals(ZenColors.FujiMauve, lightAffixColor)

        // Must not be the old HaiMist (#E5E4E2) or white
        assertNotEquals(Color(0xFFE5E4E2), lightAffixColor)
        assertNotEquals(Color.White, lightAffixColor)
    }

    @Test
    fun `canonical categories pass through uppercase`() {
        assertEquals("NOUN", derivePosCategory("NOUN"))
        assertEquals("VERB", derivePosCategory("verb"))
        assertEquals("PARTICLE", derivePosCategory("Particle"))
        assertEquals("AFFIX", derivePosCategory("affix"))
        assertEquals("AUXILIARY", derivePosCategory("AUXILIARY"))
        assertEquals("ADJECTIVE", derivePosCategory("adjective"))
        assertEquals("ADVERB", derivePosCategory("adverb"))
        assertEquals("CONJUNCTION", derivePosCategory("conjunction"))
        assertEquals("PRONOUN", derivePosCategory("pronoun"))
        assertEquals("PRE_NOUN_ADJECTIVAL", derivePosCategory("pre_noun_adjectival"))
        assertEquals("INTERJECTION", derivePosCategory("interjection"))
        assertEquals("SYMBOL", derivePosCategory("symbol"))
        assertEquals("PHRASE", derivePosCategory("phrase"))
        assertEquals("OTHER", derivePosCategory("other"))
    }

    @Test
    fun `affix mappings support Japanese and Simplified Chinese variations`() {
        // Japanese standard
        assertEquals("AFFIX", derivePosCategory("接尾辞"))
        assertEquals("AFFIX", derivePosCategory("接頭辞"))
        assertEquals("AFFIX", derivePosCategory("接辞"))
        assertEquals("AFFIX", derivePosCategory("接尾語"))
        assertEquals("AFFIX", derivePosCategory("接頭語"))
        assertEquals("AFFIX", derivePosCategory("接尾辞-名詞的"))
        assertEquals("AFFIX", derivePosCategory("名詞-接尾辞"))
        assertEquals("AFFIX", derivePosCategory("名詞接尾辞"))
        assertEquals("AFFIX", derivePosCategory("動詞接頭辞"))

        // Simplified Chinese & aliases
        assertEquals("AFFIX", derivePosCategory("接尾词"))
        assertEquals("AFFIX", derivePosCategory("接头词"))
        assertEquals("AFFIX", derivePosCategory("词缀"))
        assertEquals("AFFIX", derivePosCategory("后缀"))
        assertEquals("AFFIX", derivePosCategory("前缀"))
        assertEquals("AFFIX", derivePosCategory("接尾语"))
        assertEquals("AFFIX", derivePosCategory("接头语"))
        assertEquals("AFFIX", derivePosCategory("名词后缀"))
        assertEquals("AFFIX", derivePosCategory("动词前缀"))
        assertEquals("AFFIX", derivePosCategory("词尾"))
        assertEquals("AFFIX", derivePosCategory("后缀（名词性）"))

        // English
        assertEquals("AFFIX", derivePosCategory("suffix"))
        assertEquals("AFFIX", derivePosCategory("prefix"))
    }

    @Test
    fun `particle mappings support standard Japanese, Simplified Chinese, and abbreviations`() {
        // Standard Japanese
        assertEquals("PARTICLE", derivePosCategory("助詞"))
        assertEquals("PARTICLE", derivePosCategory("格助詞"))
        assertEquals("PARTICLE", derivePosCategory("副助詞"))
        assertEquals("PARTICLE", derivePosCategory("係助詞"))
        assertEquals("PARTICLE", derivePosCategory("係り助詞"))
        assertEquals("PARTICLE", derivePosCategory("接続助詞"))
        assertEquals("PARTICLE", derivePosCategory("終助詞"))
        assertEquals("PARTICLE", derivePosCategory("間投助詞"))
        assertEquals("PARTICLE", derivePosCategory("並立助詞"))
        assertEquals("PARTICLE", derivePosCategory("並列助詞"))
        assertEquals("PARTICLE", derivePosCategory("準体助詞"))
        assertEquals("PARTICLE", derivePosCategory("取り立て助詞"))
        assertEquals("PARTICLE", derivePosCategory("提示助詞"))
        assertEquals("PARTICLE", derivePosCategory("複合助詞"))
        assertEquals("PARTICLE", derivePosCategory("語気助詞"))

        // Simplified Chinese
        assertEquals("PARTICLE", derivePosCategory("助词"))
        assertEquals("PARTICLE", derivePosCategory("格助词"))
        assertEquals("PARTICLE", derivePosCategory("副助词"))
        assertEquals("PARTICLE", derivePosCategory("系助词"))
        assertEquals("PARTICLE", derivePosCategory("接续助词"))
        assertEquals("PARTICLE", derivePosCategory("连结助词"))
        assertEquals("PARTICLE", derivePosCategory("终助词"))
        assertEquals("PARTICLE", derivePosCategory("间投助词"))
        assertEquals("PARTICLE", derivePosCategory("并列助词"))
        assertEquals("PARTICLE", derivePosCategory("准体助词"))
        assertEquals("PARTICLE", derivePosCategory("提示助词"))
        assertEquals("PARTICLE", derivePosCategory("复合助词"))
        assertEquals("PARTICLE", derivePosCategory("语气助词"))
        assertEquals("PARTICLE", derivePosCategory("语气词"))
        assertEquals("PARTICLE", derivePosCategory("叹助词"))

        // Japanese & Chinese 2-character abbreviations
        assertEquals("PARTICLE", derivePosCategory("格助"))
        assertEquals("PARTICLE", derivePosCategory("副助"))
        assertEquals("PARTICLE", derivePosCategory("係助"))
        assertEquals("PARTICLE", derivePosCategory("系助"))
        assertEquals("PARTICLE", derivePosCategory("終助"))
        assertEquals("PARTICLE", derivePosCategory("终助"))
        assertEquals("PARTICLE", derivePosCategory("接助"))
        assertEquals("PARTICLE", derivePosCategory("並助"))
        assertEquals("PARTICLE", derivePosCategory("并助"))
        assertEquals("PARTICLE", derivePosCategory("間助"))
        assertEquals("PARTICLE", derivePosCategory("间助"))

        // Compound descriptions with parentheses or delimiters
        assertEquals("PARTICLE", derivePosCategory("格助词（主格）"))
        assertEquals("PARTICLE", derivePosCategory("助词/格助词"))
        assertEquals("PARTICLE", derivePosCategory("助詞-格助詞"))
        assertEquals("PARTICLE", derivePosCategory("終助詞（疑問）"))
        assertEquals("PARTICLE", derivePosCategory("格助詞・連用形"))

        // English
        assertEquals("PARTICLE", derivePosCategory("particle"))
        assertEquals("PARTICLE", derivePosCategory("prt"))
    }

    @Test
    fun `auxiliary mappings are prioritized over verbs`() {
        assertEquals("AUXILIARY", derivePosCategory("助動詞"))
        assertEquals("AUXILIARY", derivePosCategory("助動詞-一般"))
        assertEquals("AUXILIARY", derivePosCategory("助动词"))
        assertEquals("AUXILIARY", derivePosCategory("助动词（过去）"))
        assertEquals("AUXILIARY", derivePosCategory("助动"))
        assertEquals("AUXILIARY", derivePosCategory("auxiliary"))
        assertEquals("AUXILIARY", derivePosCategory("aux"))
    }

    @Test
    fun `pronoun mappings are prioritized over nouns`() {
        assertEquals("PRONOUN", derivePosCategory("代名詞"))
        assertEquals("PRONOUN", derivePosCategory("人称代名詞"))
        assertEquals("PRONOUN", derivePosCategory("指示代名詞"))
        assertEquals("PRONOUN", derivePosCategory("代名词"))
        assertEquals("PRONOUN", derivePosCategory("代词"))
        assertEquals("PRONOUN", derivePosCategory("人称代词"))
        assertEquals("PRONOUN", derivePosCategory("指示代词"))
        assertEquals("PRONOUN", derivePosCategory("pronoun"))
        assertEquals("PRONOUN", derivePosCategory("pron"))
    }

    @Test
    fun `adjective mappings handle keiyoushi and keiyoudoushi`() {
        assertEquals("ADJECTIVE", derivePosCategory("形容詞"))
        assertEquals("ADJECTIVE", derivePosCategory("形容動詞"))
        assertEquals("ADJECTIVE", derivePosCategory("形状詞"))
        assertEquals("ADJECTIVE", derivePosCategory("形容词"))
        assertEquals("ADJECTIVE", derivePosCategory("形容动词"))
        assertEquals("ADJECTIVE", derivePosCategory("形状词"))
        assertEquals("ADJECTIVE", derivePosCategory("形动"))
        assertEquals("ADJECTIVE", derivePosCategory("判定词"))
        assertEquals("ADJECTIVE", derivePosCategory("adj"))
        assertEquals("ADJECTIVE", derivePosCategory("adjective"))
    }

    @Test
    fun `verb mappings handle standard and conjugation types`() {
        assertEquals("VERB", derivePosCategory("動詞"))
        assertEquals("VERB", derivePosCategory("動詞-自立"))
        assertEquals("VERB", derivePosCategory("五段動詞"))
        assertEquals("VERB", derivePosCategory("一段動詞"))
        assertEquals("VERB", derivePosCategory("サ変動詞"))
        assertEquals("VERB", derivePosCategory("カ変動詞"))
        assertEquals("VERB", derivePosCategory("補助動詞"))
        assertEquals("VERB", derivePosCategory("自動詞"))
        assertEquals("VERB", derivePosCategory("他動詞"))

        assertEquals("VERB", derivePosCategory("动词"))
        assertEquals("VERB", derivePosCategory("五段动词"))
        assertEquals("VERB", derivePosCategory("一段动词"))
        assertEquals("VERB", derivePosCategory("自动词"))
        assertEquals("VERB", derivePosCategory("他动词"))
        assertEquals("VERB", derivePosCategory("verb"))
        assertEquals("VERB", derivePosCategory("v"))
    }

    @Test
    fun `adverb, conjunction, prenoun, and interjection mappings`() {
        // Adverb
        assertEquals("ADVERB", derivePosCategory("副詞"))
        assertEquals("ADVERB", derivePosCategory("副词"))
        assertEquals("ADVERB", derivePosCategory("擬態語"))
        assertEquals("ADVERB", derivePosCategory("拟态词"))
        assertEquals("ADVERB", derivePosCategory("拟声词"))
        assertEquals("ADVERB", derivePosCategory("オノマトペ"))
        assertEquals("ADVERB", derivePosCategory("adv"))

        // Conjunction
        assertEquals("CONJUNCTION", derivePosCategory("接続詞"))
        assertEquals("CONJUNCTION", derivePosCategory("接续词"))
        assertEquals("CONJUNCTION", derivePosCategory("连词"))
        assertEquals("CONJUNCTION", derivePosCategory("连接词"))
        assertEquals("CONJUNCTION", derivePosCategory("conj"))

        // Pre-noun Adjectival
        assertEquals("PRE_NOUN_ADJECTIVAL", derivePosCategory("連体詞"))
        assertEquals("PRE_NOUN_ADJECTIVAL", derivePosCategory("连体词"))
        assertEquals("PRE_NOUN_ADJECTIVAL", derivePosCategory("prenoun"))

        // Interjection
        assertEquals("INTERJECTION", derivePosCategory("感動詞"))
        assertEquals("INTERJECTION", derivePosCategory("感叹词"))
        assertEquals("INTERJECTION", derivePosCategory("叹词"))
        assertEquals("INTERJECTION", derivePosCategory("間投詞"))
        assertEquals("INTERJECTION", derivePosCategory("int"))
    }

    @Test
    fun `noun, symbol, phrase, and fallback other`() {
        // Noun
        assertEquals("NOUN", derivePosCategory("名詞"))
        assertEquals("NOUN", derivePosCategory("普通名詞"))
        assertEquals("NOUN", derivePosCategory("固有名詞"))
        assertEquals("NOUN", derivePosCategory("数詞"))
        assertEquals("NOUN", derivePosCategory("名词"))
        assertEquals("NOUN", derivePosCategory("数词"))
        assertEquals("NOUN", derivePosCategory("数量词"))
        assertEquals("NOUN", derivePosCategory("noun"))

        // Symbol
        assertEquals("SYMBOL", derivePosCategory("記号"))
        assertEquals("SYMBOL", derivePosCategory("符号"))
        assertEquals("SYMBOL", derivePosCategory("标点"))
        assertEquals("SYMBOL", derivePosCategory("标点符号"))
        assertEquals("SYMBOL", derivePosCategory("句読点"))

        // Phrase
        assertEquals("PHRASE", derivePosCategory("連語"))
        assertEquals("PHRASE", derivePosCategory("慣用句"))
        assertEquals("PHRASE", derivePosCategory("短语"))
        assertEquals("PHRASE", derivePosCategory("词组"))
        assertEquals("PHRASE", derivePosCategory("成语"))
        assertEquals("PHRASE", derivePosCategory("熟语"))

        // Fallbacks
        assertEquals("OTHER", derivePosCategory(null))
        assertEquals("OTHER", derivePosCategory(""))
        assertEquals("OTHER", derivePosCategory("   "))
        assertEquals("OTHER", derivePosCategory("UNKNOWN_POS_XYZ"))
    }

    @Test
    fun `effectivePosCategory on BookmarkedSegmentDomain delegates to derivePosCategory`() {
        val segmentWithDirectCategory = BookmarkedSegmentDomain(
            recordId = 1,
            segmentText = "さ",
            partOfSpeech = "接尾辞",
            posCategory = "AFFIX"
        )
        assertEquals("AFFIX", segmentWithDirectCategory.effectivePosCategory)

        val segmentNeedingDerivation = BookmarkedSegmentDomain(
            recordId = 1,
            segmentText = "さ",
            partOfSpeech = "接尾词",
            posCategory = null
        )
        assertEquals("AFFIX", segmentNeedingDerivation.effectivePosCategory)

        val particleSegment = BookmarkedSegmentDomain(
            recordId = 1,
            segmentText = "を",
            partOfSpeech = "格助词",
            posCategory = null
        )
        assertEquals("PARTICLE", particleSegment.effectivePosCategory)
    }
}
