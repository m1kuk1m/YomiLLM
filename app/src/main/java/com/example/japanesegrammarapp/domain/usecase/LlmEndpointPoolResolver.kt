package com.example.japanesegrammarapp.domain.usecase

import com.example.japanesegrammarapp.domain.model.AnalysisModule
import com.example.japanesegrammarapp.domain.model.DecisionConfig
import com.example.japanesegrammarapp.domain.model.LlmConfig
import com.example.japanesegrammarapp.domain.model.LlmEndpoint
import com.example.japanesegrammarapp.domain.repository.EndpointHealthTracker
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmEndpointPoolResolver @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val endpointHealthTracker: EndpointHealthTracker
) {
    fun resolveConfigs(
        provider: String,
        modelName: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<LlmApiConfig> {
        val baseProvider = settingsRepository.getBaseProviderType(provider)
        val endpoints = settingsRepository.getEndpoints(provider)
            .filter { endpoint ->
                val health = endpointHealthTracker.getHealth(endpoint.id)
                endpoint.enabled &&
                    settingsRepository.getApiKeyForEndpoint(endpoint.id).isNotBlank() &&
                    !health.isCoolingDown(nowMs)
            }
            .sortedWith(
                compareBy<LlmEndpoint> { it.priority }
                    .thenByDescending { endpoint ->
                        val health = endpointHealthTracker.getHealth(endpoint.id)
                        endpointRotationScore(endpoint.weight, health.lastUsedAtMs, nowMs)
                    }
                    .thenBy { endpoint -> endpointHealthTracker.getHealth(endpoint.id).consecutiveFailures }
                    .thenBy { it.name }
            )

        return endpoints.map { endpoint ->
            LlmApiConfig(
                provider = provider,
                baseProvider = baseProvider,
                modelName = modelName,
                url = endpoint.baseUrl.ifBlank { defaultUrlForProvider(provider) },
                key = settingsRepository.getApiKeyForEndpoint(endpoint.id),
                endpointId = endpoint.id,
                endpointName = endpoint.name
            )
        }
    }

    fun resolveModuleConfigs(
        module: AnalysisModule,
        fallbackProvider: String? = null,
        fallbackModel: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        onOverrideUnavailable: ((overrideProvider: String, overrideModel: String) -> Unit)? = null
    ): List<LlmApiConfig> {
        val override = settingsRepository.getComponentModelConfig(module.id)
        if (!override.isGlobal) {
            val overrideConfigs = resolveConfigs(override.provider, override.model, nowMs)
            if (overrideConfigs.isNotEmpty()) {
                return overrideConfigs
            }
            onOverrideUnavailable?.invoke(override.provider, override.model)
        }
        val provider = fallbackProvider ?: settingsRepository.getActiveProvider()
        val model = fallbackModel ?: settingsRepository.getActiveModel(provider)
        return resolveConfigs(provider, model, nowMs)
    }

    fun resolveBackupConfigs(nowMs: Long = System.currentTimeMillis()): List<LlmApiConfig> {
        if (!settingsRepository.getUseBackupApi()) return emptyList()
        val backupProvider = settingsRepository.getBackupProvider()
        val backupModel = settingsRepository.getBackupModel()
        if (backupProvider.isBlank() || backupModel.isBlank()) return emptyList()
        return resolveConfigs(backupProvider, backupModel, nowMs)
    }

    private fun defaultUrlForProvider(provider: String): String {
        return if (provider == DecisionConfig.PROVIDER_ID) {
            DecisionConfig.DEFAULT_URL
        } else {
            LlmConfig.defaultUrls[provider] ?: ""
        }
    }

    private fun endpointRotationScore(weight: Int, lastUsedAtMs: Long, nowMs: Long): Long {
        val safeWeight = weight.coerceAtLeast(1)
        if (lastUsedAtMs <= 0L) {
            return Long.MAX_VALUE
        }
        val idleSeconds = ((nowMs - lastUsedAtMs).coerceAtLeast(0L) / 1000L)
        return idleSeconds.coerceAtMost(Long.MAX_VALUE / safeWeight) * safeWeight
    }
}
