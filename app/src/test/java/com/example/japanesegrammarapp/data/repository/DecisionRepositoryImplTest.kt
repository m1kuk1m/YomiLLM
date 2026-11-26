package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.DecisionConfig
import com.example.japanesegrammarapp.domain.model.DecisionPosTaxonomy
import com.example.japanesegrammarapp.domain.model.DecisionQuestion
import com.example.japanesegrammarapp.domain.repository.EndpointHealthTracker
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.network.DecisionAnswerPayload
import com.example.japanesegrammarapp.network.DecisionRequest
import com.example.japanesegrammarapp.network.DecisionResponse
import com.example.japanesegrammarapp.network.DecisionUsagePayload
import com.example.japanesegrammarapp.network.LlmApiService
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException

class DecisionRepositoryImplTest {

    private fun testConfig(
        endpointId: String = "ep_1",
        endpointName: String = "Primary",
        url: String = DecisionConfig.DEFAULT_URL,
        key: String = "secret-key"
    ) = LlmApiConfig(
        provider = DecisionConfig.PROVIDER_ID,
        baseProvider = DecisionConfig.PROVIDER_ID,
        modelName = DecisionConfig.DEFAULT_MODEL,
        url = url,
        key = key,
        endpointId = endpointId,
        endpointName = endpointName
    )

    @Test
    fun classifyTokenPosSkipsPunctuationLocallyAndMapsAnswersAndUsage() = runBlocking {
        val api = mock(LlmApiService::class.java)
        val settings = mock(SettingsRepository::class.java)
        val healthTracker = InMemoryEndpointHealthTracker()
        `when`(settings.getAutoRetryOnError()).thenReturn(false)
        `when`(settings.getFailoverToNextEndpoint()).thenReturn(false)

        val expectedUrl = "${DecisionConfig.DEFAULT_URL}?key=secret-key"
        val capturedRequests = mutableListOf<DecisionRequest>()
        val repo = DecisionRepositoryImpl(
            llmApiService = api,
            settingsRepository = settings,
            endpointHealthTracker = healthTracker,
            gson = Gson()
        )

        val sentence = "猫が走る。"
        val tokens = listOf("猫", "が", "走る", "。")

        val expectedQuestions = linkedMapOf(
            "tok_0" to com.example.japanesegrammarapp.network.DecisionQuestionPayload(
                type = "choice",
                instructions = "文中の 1 番目のトークン「猫」（局所文脈：「猫が」）の品詞を選択してください。",
                criteria = DecisionPosTaxonomy.STANDARD_POS_CRITERIA
            ),
            "tok_1" to com.example.japanesegrammarapp.network.DecisionQuestionPayload(
                type = "choice",
                instructions = "文中の 2 番目のトークン「が」（局所文脈：「猫が走る」）の品詞を選択してください。",
                criteria = DecisionPosTaxonomy.STANDARD_POS_CRITERIA
            ),
            "tok_2" to com.example.japanesegrammarapp.network.DecisionQuestionPayload(
                type = "choice",
                instructions = "文中の 3 番目のトークン「走る」（局所文脈：「が走る。」）の品詞を選択してください。",
                criteria = DecisionPosTaxonomy.STANDARD_POS_CRITERIA
            )
        )
        val expectedRequest = DecisionRequest(
            model = DecisionConfig.DEFAULT_MODEL,
            input = sentence,
            questions = expectedQuestions
        )
        `when`(api.executeDecisions(expectedUrl, expectedRequest)).thenAnswer { invocation ->
            capturedRequests.add(invocation.getArgument(1))
            DecisionResponse(
                answers = mapOf(
                    "tok_0" to DecisionAnswerPayload(choice = "noun", confidence = 0.99),
                    "tok_1" to DecisionAnswerPayload(choice = "particle", confidence = 0.98),
                    "tok_2" to DecisionAnswerPayload(choice = "verb", confidence = 0.97)
                ),
                usage = DecisionUsagePayload(totalTokens = 30, promptTokens = 25, candidatesTokens = 5)
            )
        }

        val (segments, meta) = repo.classifyTokenPos(
            sentence = sentence,
            tokens = tokens,
            configs = listOf(testConfig())
        )

        assertEquals(1, capturedRequests.size)
        assertFalse(capturedRequests.first().questions.containsKey("tok_3"))
        assertEquals(listOf("NOUN", "PARTICLE", "VERB", "SYMBOL"), segments.map { it.posCategory })
        assertEquals(listOf("名詞", "助詞", "動詞", "補助記号"), segments.map { it.partOfSpeech })
        assertEquals(30, meta.consumedTokens)
        assertEquals(25, meta.inputTokens)
        assertEquals(5, meta.outputTokens)
        val ep1Health = healthTracker.getHealth("ep_1")
        assertEquals(0, ep1Health.consecutiveFailures)
        assertTrue(ep1Health.lastUsedAtMs > 0L)
    }

    @Test
    fun classifyTokenPosWithOnlyPunctuationSkipsNetworkCall() = runBlocking {
        val api = mock(LlmApiService::class.java)
        val settings = mock(SettingsRepository::class.java)
        val repo = DecisionRepositoryImpl(api, settings, InMemoryEndpointHealthTracker(), Gson())

        val (segments, meta) = repo.classifyTokenPos(
            sentence = "「。」",
            tokens = listOf("「", "。", "」"),
            configs = listOf(testConfig())
        )

        assertEquals(listOf("SYMBOL", "SYMBOL", "SYMBOL"), segments.map { it.posCategory })
        assertEquals(0, meta.consumedTokens)
    }

    @Test
    fun evaluateDecisionsFailsOverToSecondEndpointWhenEnabled() = runBlocking {
        val api = mock(LlmApiService::class.java)
        val settings = mock(SettingsRepository::class.java)
        `when`(settings.getAutoRetryOnError()).thenReturn(false)
        `when`(settings.getFailoverToNextEndpoint()).thenReturn(true)

        val q = mapOf("q1" to DecisionQuestion(type = "choice", instructions = "check", criteria = mapOf("a" to "A")))
        val payloadQ = mapOf(
            "q1" to com.example.japanesegrammarapp.network.DecisionQuestionPayload(
                type = "choice",
                instructions = "check",
                criteria = mapOf("a" to "A")
            )
        )
        val req = DecisionRequest(model = DecisionConfig.DEFAULT_MODEL, input = "test", questions = payloadQ)

        `when`(api.executeDecisions("https://ep1.test?key=k1", req)).thenAnswer {
            throw IOException("timeout")
        }
        `when`(api.executeDecisions("https://ep2.test?key=k2", req)).thenReturn(
            DecisionResponse(
                answers = mapOf("q1" to DecisionAnswerPayload(choice = "a", confidence = 0.9)),
                usage = DecisionUsagePayload(totalTokens = 10, promptTokens = 8, candidatesTokens = 2)
            )
        )

        val healthTracker = InMemoryEndpointHealthTracker()
        val result = DecisionRepositoryImpl(api, settings, healthTracker, Gson()).evaluateDecisions(
            input = "test",
            questions = q,
            configs = listOf(
                testConfig(endpointId = "ep_1", url = "https://ep1.test", key = "k1"),
                testConfig(endpointId = "ep_2", url = "https://ep2.test", key = "k2")
            ),
            apiTypeLabel = DecisionConfig.API_TYPE_LABEL
        )

        assertEquals("a", result.answers["q1"]?.choice)
        assertTrue(result.consumedTokens == 10)
        assertEquals(1, healthTracker.getHealth("ep_1").consecutiveFailures)
        assertTrue(healthTracker.getHealth("ep_1").isCoolingDown())
        assertEquals(0, healthTracker.getHealth("ep_2").consecutiveFailures)
        assertTrue(healthTracker.getHealth("ep_2").lastUsedAtMs > 0L)
    }
}
