package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.data.repository.InMemoryEndpointHealthTracker
import com.example.japanesegrammarapp.domain.model.AnalysisModule
import com.example.japanesegrammarapp.domain.model.ComponentModelConfig
import com.example.japanesegrammarapp.domain.model.LlmEndpoint
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LlmEndpointPoolResolverTest {

    @Test
    fun resolveConfigsFiltersDisabledMissingKeyAndCooldownEndpointsAndAppliesWeightedRotation() {
        val settings = mock(SettingsRepository::class.java)
        val healthTracker = InMemoryEndpointHealthTracker()

        val epReadyFresh = LlmEndpoint("ep_fresh", "Gemini", "Fresh", "https://fresh.test", enabled = true, priority = 1, weight = 1)
        val epReadyHeavy = LlmEndpoint("ep_heavy", "Gemini", "Heavy", "https://heavy.test", enabled = true, priority = 1, weight = 5)
        val epReadyLight = LlmEndpoint("ep_light", "Gemini", "Light", "https://light.test", enabled = true, priority = 1, weight = 1)
        val epPriorityZero = LlmEndpoint("ep_p0", "Gemini", "Top", "https://p0.test", enabled = true, priority = 0, weight = 1)
        val epCooldown = LlmEndpoint("ep_cool", "Gemini", "Cool", "https://cool.test", enabled = true, priority = 0, weight = 1)
        val epDisabled = LlmEndpoint("ep_off", "Gemini", "Off", "https://off.test", enabled = false, priority = 0, weight = 1)
        val epNoKey = LlmEndpoint("ep_nokey", "Gemini", "NoKey", "https://nokey.test", enabled = true, priority = 0, weight = 1)

        `when`(settings.getBaseProviderType("Gemini")).thenReturn("Gemini")
        `when`(settings.getEndpoints("Gemini")).thenReturn(
            listOf(epReadyLight, epReadyHeavy, epReadyFresh, epPriorityZero, epCooldown, epDisabled, epNoKey)
        )
        `when`(settings.getApiKeyForEndpoint("ep_fresh")).thenReturn("k_fresh")
        `when`(settings.getApiKeyForEndpoint("ep_heavy")).thenReturn("k_heavy")
        `when`(settings.getApiKeyForEndpoint("ep_light")).thenReturn("k_light")
        `when`(settings.getApiKeyForEndpoint("ep_p0")).thenReturn("k_p0")
        `when`(settings.getApiKeyForEndpoint("ep_cool")).thenReturn("k_cool")
        `when`(settings.getApiKeyForEndpoint("ep_off")).thenReturn("k_off")
        `when`(settings.getApiKeyForEndpoint("ep_nokey")).thenReturn("")

        val now = 100_000L
        healthTracker.touch("ep_p0", nowMs = 90_000L)
        healthTracker.touch("ep_heavy", nowMs = 90_000L) // idle 10s * weight 5 = 50
        healthTracker.touch("ep_light", nowMs = 80_000L) // idle 20s * weight 1 = 20
        healthTracker.markFailure("ep_cool", "429", cooldownMs = 60_000L, nowMs = 90_000L)

        val resolver = LlmEndpointPoolResolver(settings, healthTracker)
        val configs = resolver.resolveConfigs("Gemini", "gemini-2.5-flash", nowMs = now)

        // Priority 0 first, then priority 1 ordered by rotation score: ep_fresh (never used = MAX) > ep_heavy (50) > ep_light (20)
        assertEquals(listOf("ep_p0", "ep_fresh", "ep_heavy", "ep_light"), configs.map { it.endpointId })
    }

    @Test
    fun resolveModuleConfigsFallsBackToGlobalWhenOverrideHasNoUsableEndpoint() {
        val settings = mock(SettingsRepository::class.java)
        val healthTracker = InMemoryEndpointHealthTracker()

        `when`(settings.getComponentModelConfig(AnalysisModule.IMAGE_OCR.id))
            .thenReturn(ComponentModelConfig(provider = "DeepSeek", model = "deepseek-chat"))
        `when`(settings.getBaseProviderType("DeepSeek")).thenReturn("OpenAI Compatible")
        `when`(settings.getEndpoints("DeepSeek")).thenReturn(
            listOf(LlmEndpoint("ds_1", "DeepSeek", "DS", "https://ds.test", enabled = true))
        )
        `when`(settings.getApiKeyForEndpoint("ds_1")).thenReturn("ds_key")
        healthTracker.markFailure("ds_1", "429", cooldownMs = 60_000L, nowMs = 1_000L)

        `when`(settings.getActiveProvider()).thenReturn("Gemini")
        `when`(settings.getActiveModel("Gemini")).thenReturn("gemini-global")
        `when`(settings.getBaseProviderType("Gemini")).thenReturn("Gemini")
        `when`(settings.getEndpoints("Gemini")).thenReturn(
            listOf(LlmEndpoint("gm_1", "Gemini", "GM", "https://gm.test", enabled = true))
        )
        `when`(settings.getApiKeyForEndpoint("gm_1")).thenReturn("gm_key")

        var fallbackReported: Pair<String, String>? = null
        val resolver = LlmEndpointPoolResolver(settings, healthTracker)
        val configs = resolver.resolveModuleConfigs(
            module = AnalysisModule.IMAGE_OCR,
            nowMs = 2_000L,
            onOverrideUnavailable = { p, m -> fallbackReported = p to m }
        )

        assertEquals("DeepSeek" to "deepseek-chat", fallbackReported)
        assertEquals(1, configs.size)
        assertEquals("gm_1", configs.single().endpointId)
        assertEquals("gemini-global", configs.single().modelName)
    }

    @Test
    fun resolveBackupConfigsRespectsToggleAndBlankChecks() {
        val settings = mock(SettingsRepository::class.java)
        val healthTracker = InMemoryEndpointHealthTracker()
        val resolver = LlmEndpointPoolResolver(settings, healthTracker)

        `when`(settings.getUseBackupApi()).thenReturn(false)
        assertTrue(resolver.resolveBackupConfigs().isEmpty())

        `when`(settings.getUseBackupApi()).thenReturn(true)
        `when`(settings.getBackupProvider()).thenReturn("DeepSeek")
        `when`(settings.getBackupModel()).thenReturn("deepseek-chat")
        `when`(settings.getBaseProviderType("DeepSeek")).thenReturn("OpenAI Compatible")
        `when`(settings.getEndpoints("DeepSeek")).thenReturn(
            listOf(LlmEndpoint("bk_1", "DeepSeek", "Backup", "https://bk.test", enabled = true))
        )
        `when`(settings.getApiKeyForEndpoint("bk_1")).thenReturn("bk_key")

        val backup = resolver.resolveBackupConfigs()
        assertEquals(1, backup.size)
        assertEquals("bk_1", backup.single().endpointId)
    }
}
