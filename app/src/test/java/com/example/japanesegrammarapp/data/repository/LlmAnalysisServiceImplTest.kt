package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.LlmEndpoint
import com.example.japanesegrammarapp.domain.model.OcrBoxDetectionSettings
import com.example.japanesegrammarapp.domain.model.ReasoningLevel
import com.example.japanesegrammarapp.domain.model.ComponentReasoningLevel
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.LlmRepository
import com.example.japanesegrammarapp.domain.repository.LlmResult
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmAnalysisServiceImplTest {
    @Test
    fun executeTokenizerSplitsSafePunctuationOutOfMixedTokens() = runBlocking {
        val repository = FakeLlmRepository(
            response = """
                か…必ずしも…はっ歓迎…て事じゃ…ない…だろう…し…
                か…
                必ずしも…
                はっ
                歓迎…
                て
                事
                じゃ…
                ない…
                だろう…
                し…
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val (result, _) = service.executeTokenizer(
            text = "",
            imageBase64 = "image",
            mimeType = "image/jpeg",
            isOcrMode = false,
            imageTokenizerMode = "repair",
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        val expectedTokens = listOf(
            "か", "…",
            "必ずしも", "…",
            "はっ",
            "歓迎", "…",
            "て",
            "事",
            "じゃ", "…",
            "ない", "…",
            "だろう", "…",
            "し", "…"
        )
        assertEquals(expectedTokens, result?.tokens)
        assertEquals(result?.recognizedText, result?.tokens?.joinToString(""))
    }

    @Test
    fun executeSegmentsPreservesOriginalTokenSurfaceWhenAnalyzerDropsPunctuation() = runBlocking {
        val repository = FakeLlmRepository(
            response = """
                か|か|吗/或者|副助詞||||
                必ずしも|かならずしも|未必|副詞||||
                歓迎|かんげい|欢迎|名詞||||
                じゃ|じゃ|不是|助動詞||||
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val tokens = listOf("か…", "必ずしも…", "歓迎…", "じゃ…")
        val (result, _) = service.executeSegments(
            text = tokens.joinToString(""),
            tokens = tokens,
            imageBase64 = null,
            mimeType = null,
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        assertEquals(tokens, result?.segments?.map { it.text })
    }

    @Test
    fun executeSegmentsTreatsMultiCharacterEllipsisAsPunctuation() = runBlocking {
        assertEllipsisTokenIsKeptOutOfDetailedAnalysis("……")
        assertEllipsisTokenIsKeptOutOfDetailedAnalysis("......")
    }

    @Test
    fun executeSegmentsDropsWhitespaceOnlyTokensFromDisplaySegments() = runBlocking {
        val repository = FakeLlmRepository(
            response = """
                あの|あの||
                極端に|きょくたんに||
                蹴落とさない|けおとさない||
                で|で||
                もらえます|もらえます||
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val (result, _) = service.executeSegments(
            text = "あの、極端に\n蹴落とさないでもらえます？",
            tokens = listOf("あの", "、", "極端に", "\n", "蹴落とさない", "で", "もらえます", "？"),
            imageBase64 = null,
            mimeType = null,
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        assertTrue(repository.lastUserPrompt.contains("あの, 極端に, 蹴落とさない, で, もらえます"))
        assertEquals(
            listOf("あの", "、", "極端に", "蹴落とさない", "で", "もらえます", "？"),
            result?.segments?.map { it.text }
        )
    }

    private suspend fun assertEllipsisTokenIsKeptOutOfDetailedAnalysis(ellipsisToken: String) {
        val repository = FakeLlmRepository(
            response = """
                あたし|あたし||
                みなしご|みなしご||
                なの|なの||
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val (result, _) = service.executeSegments(
            text = "${ellipsisToken}あたし、みなしごなの。",
            tokens = listOf(ellipsisToken, "あたし", "、", "みなしご", "なの", "。"),
            imageBase64 = null,
            mimeType = null,
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        assertTrue(repository.lastUserPrompt.contains("あたし, みなしご, なの"))
        assertEquals(
            listOf(ellipsisToken, "あたし", "、", "みなしご", "なの", "。"),
            result?.segments?.map { it.text }
        )
        assertEquals("補助記号", result?.segments?.firstOrNull()?.partOfSpeech)
    }

    @Test
    fun executeSegmentsFiltersPunctuationAndUnknownSymbolsLocally() = runBlocking {
        val repository = FakeLlmRepository(
            response = """
                あの|あの||
                元気|げんき||
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val (result, _) = service.executeSegments(
            text = "あの★元気",
            tokens = listOf("あの", "★", "元気"),
            imageBase64 = null,
            mimeType = null,
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        assertTrue(repository.lastUserPrompt.contains("あの, 元気"))
        // The reconstructed final segments should correctly put ★ back in the middle
        assertEquals(
            listOf("あの", "★", "元気"),
            result?.segments?.map { it.text }
        )
        assertEquals("補助記号", result?.segments?.get(1)?.partOfSpeech)
        assertEquals("きごう", result?.segments?.get(1)?.reading)
    }

    @Test
    fun executeSegmentsFiltersCJKDoubleQuotesAndProvidesCustomDescriptions() = runBlocking {
        val repository = FakeLlmRepository(
            response = """
                はなまる|はなまる||
                大正解|たいせいかい||
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val (result, _) = service.executeSegments(
            text = "〝はなまる大正解〟",
            tokens = listOf("〝", "はなまる", "大正解", "〟"),
            imageBase64 = null,
            mimeType = null,
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        assertTrue(repository.lastUserPrompt.contains("はなまる, 大正解"))
        // The final segments should be perfectly aligned with symbols reconstructed locally
        assertEquals(
            listOf("〝", "はなまる", "大正解", "〟"),
            result?.segments?.map { it.text }
        )
        assertEquals("（前双引号）", result?.segments?.get(0)?.meaning)
        assertEquals("（后双引号）", result?.segments?.get(3)?.meaning)
    }

    @Test
    fun executeImageOcrReturnsRecognizedTextAndTrimsProperly() = runBlocking {
        val repository = FakeLlmRepository(
            response = "  これは画像テストです。 \n\n"
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val (result, _) = service.executeImageOcr(
            imageBase64 = "base64image",
            mimeType = "image/png",
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        assertEquals("これは画像テストです。", result)
    }

    @Test
    fun executeSegmentsCorrectlyAlignsEmojiPunctuationWithoutOffByOneCascading() = runBlocking {
        // Reproduces the exact case from the bug report:
        // Text: "見て‼️キチガイ風車だよ‼️"
        // Tokens contains "‼️" with variation selector U+FE0F.
        // LLM only returns segment analysis for actual vocabulary, ignoring the punctuation tokens.
        val repository = FakeLlmRepository(
            response = """
                見て|みて|看|動詞|見る|みる|連用形（て形）|動作を要求する命令・呼びかけ。
                キチガイ|きちがい|疯子|名詞||||名詞の修飾要素。
                風車|かざぐるま|风车|名詞||||述語名詞。
                だ|だ|是|助動詞|だ|だ|終止形|判断や断定を表す。
                よ|よ|呢|終助詞||||語気を和らげ相手に教え知らせる。
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val doubleExclamation = "‼️" // \u203C\uFE0F
        val tokens = listOf("見て", doubleExclamation, "キチガイ", "風車", "だ", "よ", doubleExclamation)

        val (result, _) = service.executeSegments(
            text = "見て‼️キチガイ風車だよ‼️",
            tokens = tokens,
            imageBase64 = null,
            mimeType = null,
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        val segments = result?.segments ?: emptyList()
        assertEquals(7, segments.size)

        // 1. "見て" -> matched with reading "みて"
        assertEquals("見て", segments[0].text)
        assertEquals("みて", segments[0].reading)
        assertEquals("動詞", segments[0].partOfSpeech)

        // 2. "‼️" -> recognized as punctuation, NOT hijacking "キチガイ"
        assertEquals(doubleExclamation, segments[1].text)
        assertEquals("にじゅうかんたんふ", segments[1].reading)
        assertEquals("補助記号", segments[1].partOfSpeech)
        assertTrue(com.example.japanesegrammarapp.domain.model.isPunctuationToken(segments[1].text))

        // 3. "キチガイ" -> has its OWN reading "きちがい", NOT shifted to "かざぐるま"
        assertEquals("キチガイ", segments[2].text)
        assertEquals("きちがい", segments[2].reading)
        assertEquals("名詞", segments[2].partOfSpeech)

        // 4. "風車" -> has its OWN reading "かざぐるま", NOT shifted to "だ"
        assertEquals("風車", segments[3].text)
        assertEquals("かざぐるま", segments[3].reading)

        // 5. "だ" -> reading "だ"
        assertEquals("だ", segments[4].text)
        assertEquals("だ", segments[4].reading)

        // 6. "よ" -> reading "よ"
        assertEquals("よ", segments[5].text)
        assertEquals("よ", segments[5].reading)

        // 7. "‼️" -> recognized as punctuation
        assertEquals(doubleExclamation, segments[6].text)
        assertEquals("にじゅうかんたんふ", segments[6].reading)
        assertEquals("補助記号", segments[6].partOfSpeech)
    }

    @Test
    fun executeSegmentsHeuristicAlignmentHandlesSkippedTokensAndLookahead() = runBlocking {
        // Case: LLM completely omitted "突然" from its output
        val repository = FakeLlmRepository(
            response = """
                彼|かれ|他|代名詞||||
                笑った|わらった|笑了|動詞||||
            """.trimIndent()
        )
        val service = LlmAnalysisServiceImpl(
            llmRepository = repository,
            settingsRepository = FakeSettingsRepository,
            gson = Gson()
        )

        val tokens = listOf("彼", "突然", "笑った")
        val (result, _) = service.executeSegments(
            text = "彼突然笑った",
            tokens = tokens,
            imageBase64 = null,
            mimeType = null,
            primaryConfigs = listOf(testConfig),
            backupConfigs = emptyList()
        ).last()

        val segments = result?.segments ?: emptyList()
        assertEquals(3, segments.size)
        assertEquals("彼", segments[0].text)
        assertEquals("かれ", segments[0].reading)

        // "突然" was skipped by LLM: should remain as fallback token and NOT hijack "笑った"
        assertEquals("突然", segments[1].text)
        assertEquals(null, segments[1].reading)

        // "笑った" is correctly aligned via lookahead
        assertEquals("笑った", segments[2].text)
        assertEquals("わらった", segments[2].reading)
    }

    private companion object {
        val testConfig = LlmApiConfig(
            provider = "Gemini",
            baseProvider = "Gemini",
            modelName = "gemini-test",
            url = "https://example.test",
            key = "key"
        )
    }
}

private class FakeLlmRepository(
    private val response: String
) : LlmRepository {
    var lastUserPrompt: String = ""
        private set

    override suspend fun fetchModels(provider: String, baseUrl: String, apiKey: String): List<String> {
        error("Not used")
    }

    override suspend fun callLlmApi(
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        provider: String,
        baseProvider: String,
        modelName: String,
        effectiveUrl: String,
        apiKey: String,
        apiTypeLabel: String
    ): LlmResult {
        error("Not used")
    }

    override suspend fun executeWithFailover(
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        apiTypeLabel: String,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        recordId: Int?,
        stepName: String?,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit
    ): LlmResult {
        lastUserPrompt = userPrompt
        return LlmResult(
            text = response,
            consumedTokens = 3,
            inputTokens = 2,
            outputTokens = 1,
            provider = "Gemini",
            modelName = "gemini-test"
        )
    }

    override suspend fun executeWithStreaming(
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        apiTypeLabel: String,
        primaryConfigs: List<LlmApiConfig>,
        backupConfigs: List<LlmApiConfig>,
        recordId: Int?,
        stepName: String?,
        onRetry: (attempt: Int) -> Unit,
        onBackup: (backupProvider: String) -> Unit
    ): kotlinx.coroutines.flow.Flow<com.example.japanesegrammarapp.domain.model.LlmStreamEvent> {
        lastUserPrompt = userPrompt
        return kotlinx.coroutines.flow.flowOf(com.example.japanesegrammarapp.domain.model.LlmStreamEvent.Chunk(response))
    }
}

private object FakeSettingsRepository : SettingsRepository {
    override fun getReasoningLevel(): ReasoningLevel = ReasoningLevel.AUTO
    override fun setReasoningLevel(level: ReasoningLevel) = Unit
    override fun getComponentReasoningLevel(apiTypeLabel: String): ComponentReasoningLevel = ComponentReasoningLevel.GLOBAL
    override fun setComponentReasoningLevel(apiTypeLabel: String, level: ComponentReasoningLevel) = Unit
    override fun getEffectiveReasoningLevel(apiTypeLabel: String): ReasoningLevel = ReasoningLevel.AUTO
    override fun getComponentModelConfig(apiTypeLabel: String): com.example.japanesegrammarapp.domain.model.ComponentModelConfig = com.example.japanesegrammarapp.domain.model.ComponentModelConfig()
    override fun setComponentModelConfig(apiTypeLabel: String, config: com.example.japanesegrammarapp.domain.model.ComponentModelConfig) = Unit
    override fun getUseBackupApi(): Boolean = false
    override fun setUseBackupApi(value: Boolean) = Unit
    override fun getAutoRetryOnError(): Boolean = false
    override fun setAutoRetryOnError(value: Boolean) = Unit
    override fun getFailoverToNextEndpoint(): Boolean = false
    override fun setFailoverToNextEndpoint(value: Boolean) = Unit

    override fun getAllProviders(): List<String> = listOf("Gemini")
    override fun getBaseProviderType(providerId: String): String = providerId
    override fun getApiKey(provider: String): String = "key"
    override fun saveApiKey(provider: String, key: String): Boolean = true
    override fun getApiUrl(provider: String): String = "https://example.test"
    override fun saveApiUrl(provider: String, url: String) = Unit
    override fun getEndpoints(provider: String): List<LlmEndpoint> = emptyList()
    override fun getApiKeyForEndpoint(endpointId: String): String = "key"
    override fun saveEndpoint(endpoint: LlmEndpoint, apiKey: String?): Boolean = true
    override fun deleteEndpoint(provider: String, endpointId: String): Boolean = true
    override fun markEndpointSuccess(provider: String, endpointId: String) = Unit
    override fun markEndpointFailure(provider: String, endpointId: String, error: String, cooldownMs: Long) = Unit
    override fun touchEndpoint(provider: String, endpointId: String) = Unit
    override fun buildLlmApiConfigs(provider: String, modelName: String): List<LlmApiConfig> = listOf(
        LlmApiConfig(provider, provider, modelName, "https://example.test", "key")
    )
    override fun getActiveProvider(): String = "Gemini"
    override fun setActiveProvider(provider: String) = Unit
    override fun getActiveModel(provider: String): String = "gemini-test"
    override fun setActiveModel(provider: String, model: String) = Unit
    override fun getUseOcr(): Boolean = false
    override fun setUseOcr(value: Boolean) = Unit
    override fun getImageTokenizerMode(): String = "faithful"
    override fun setImageTokenizerMode(mode: String) = Unit
    override fun getOcrBoxDetectionSettings(): OcrBoxDetectionSettings = OcrBoxDetectionSettings.DEFAULT
    override fun setOcrBoxDetectionSettings(settings: OcrBoxDetectionSettings) = Unit
    override fun resetOcrBoxDetectionSettings() = Unit
    override fun getAutoNavigateResult(): Boolean = true
    override fun setAutoNavigateResult(value: Boolean) = Unit
    override fun getSilentBackgroundMode(): Boolean = false
    override fun setSilentBackgroundMode(value: Boolean) = Unit
    override fun getAutoDeskewAfterCapture(): Boolean = false
    override fun setAutoDeskewAfterCapture(value: Boolean) = Unit
    override fun getModelsForProvider(provider: String): List<String> = emptyList()
    override fun saveModelsForProvider(provider: String, models: List<String>) = Unit
    override fun getBackupProvider(): String = ""
    override fun setBackupProvider(provider: String) = Unit
    override fun getBackupModel(): String = ""
    override fun setBackupModel(model: String) = Unit
    override fun getThemeMode(): String = "System"
    override fun setThemeMode(mode: String) = Unit
    override fun getWallpaperUri(): String = ""
    override fun setWallpaperUri(uri: String) = Unit
    override val themeMode = MutableStateFlow("System")
    override val wallpaperUri = MutableStateFlow("")
    override val silentBackgroundMode = MutableStateFlow(false)
    override fun getCustomPrompt(promptKey: String): String = ""
    override fun saveCustomPrompt(promptKey: String, prompt: String) = Unit
    override fun resetCustomPrompt(promptKey: String) = Unit
    override fun resetAllCustomPrompts() = Unit
    override fun getTtsProvider(): String = "OpenAI"
    override fun setTtsProvider(provider: String) = Unit
    override fun getTtsApiUrl(provider: String): String = ""
    override fun setTtsApiUrl(provider: String, url: String) = Unit
    override fun getTtsApiKey(provider: String): String = ""
    override fun setTtsApiKey(provider: String, key: String): Boolean = true
    override fun getTtsModel(provider: String): String = ""
    override fun setTtsModel(provider: String, model: String) = Unit
    override fun getTtsVoice(provider: String): String = ""
    override fun setTtsVoice(provider: String, voice: String) = Unit
    override fun getTtsRegion(provider: String): String = ""
    override fun setTtsRegion(provider: String, region: String) = Unit
    override fun getPromptPresets(): List<com.example.japanesegrammarapp.domain.model.PromptPreset> = emptyList()
    override fun getActivePromptPresetId(): String = ""
    override fun setActivePromptPresetId(id: String) = Unit
    override fun savePromptPreset(preset: com.example.japanesegrammarapp.domain.model.PromptPreset) = Unit
    override fun deletePromptPreset(id: String) = Unit

    override fun getCardFontSizeScale(): Float = 1.0f
    override fun setCardFontSizeScale(scale: Float) = Unit
    override fun getCardSpacingScale(): Float = 1.0f
    override fun setCardSpacingScale(scale: Float) = Unit
    override fun getFuriganaSizeScale(): Float = 1.0f
    override fun setFuriganaSizeScale(scale: Float) = Unit
    override fun getCardInternalPaddingScale(): Float = 1.0f
    override fun setCardInternalPaddingScale(scale: Float) = Unit
    override fun getFuriganaGapScale(): Float = 1.0f
    override fun setFuriganaGapScale(scale: Float) = Unit
    override val cardFontSizeScale = MutableStateFlow(1.0f)
    override val cardSpacingScale = MutableStateFlow(1.0f)
    override val furiganaSizeScale = MutableStateFlow(1.0f)
    override val cardInternalPaddingScale = MutableStateFlow(1.0f)
    override val furiganaGapScale = MutableStateFlow(1.0f)
    override fun getCardDetailDisplayMode(): String = "POPUP"
    override fun setCardDetailDisplayMode(mode: String) = Unit
    override val cardDetailDisplayMode = MutableStateFlow("POPUP")
    override fun getRemoveAccidentalSpaces(): Boolean = true
    override fun setRemoveAccidentalSpaces(value: Boolean) = Unit

    override fun getDeckSyncSettings(): com.example.japanesegrammarapp.domain.model.DeckSyncSettings = com.example.japanesegrammarapp.domain.model.DeckSyncSettings()
    override fun setDeckSyncEnabled(enabled: Boolean) = Unit
    override fun setDeckSyncPort(port: Int) = Unit
    override fun setDeckSyncPin(pin: String) = Unit
    override fun regenerateDeckSyncPin(): String = "8848"
    override fun getDeckSyncAuthToken(): String = ""
    override fun setDeckSyncAuthToken(token: String) = Unit
    override val deckSyncSettingsFlow = MutableStateFlow(com.example.japanesegrammarapp.domain.model.DeckSyncSettings())
}

