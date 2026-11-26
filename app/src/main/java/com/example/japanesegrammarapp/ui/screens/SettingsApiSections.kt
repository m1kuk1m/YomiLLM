package com.example.japanesegrammarapp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SwapHoriz
import com.example.japanesegrammarapp.R
import com.example.japanesegrammarapp.domain.model.LlmEndpoint
import com.example.japanesegrammarapp.domain.model.ReasoningLevel
import com.example.japanesegrammarapp.domain.model.ComponentReasoningLevel
import com.example.japanesegrammarapp.ui.SettingsUiState

@Composable
fun SettingsApiModelSection(
    uiState: SettingsUiState,
    providers: List<String>,
    providerModels: Map<String, List<String>>,
    onActiveProviderChange: (String) -> Unit,
    onActiveModelChange: (String) -> Unit,
    onOpenCotDialog: () -> Unit,
    onOpenComponentModelDialog: () -> Unit,
    onStreamingEnabledChange: (Boolean) -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val activeProvider = uiState.activeProvider

    SettingsGroup(title = stringResource(R.string.settings_llm_group_model)) {
        var mainProviderExpanded by remember { mutableStateOf(false) }
        SettingsItem(
            icon = Icons.Default.Star,
            title = stringResource(R.string.main_api),
            onClick = { mainProviderExpanded = true },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsValueBadge(text = activeProvider, highlighted = true)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = sumiInk.copy(alpha = 0.5f)
                        )
                    }
                    DropdownMenu(
                        expanded = mainProviderExpanded,
                        onDismissRequest = { mainProviderExpanded = false }
                    ) {
                        providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider) },
                                onClick = {
                                    onActiveProviderChange(provider)
                                    mainProviderExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        SettingsDivider()

        var mainModelExpanded by remember { mutableStateOf(false) }
        val mainModels = providerModels[activeProvider].orEmpty()
        SettingsItem(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.main_model),
            subtitle = uiState.activeModel.ifBlank { stringResource(R.string.unselected) },
            onClick = { mainModelExpanded = true },
            trailingContent = {
                Box {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = sumiInk.copy(alpha = 0.5f))
                    DropdownMenu(
                        expanded = mainModelExpanded,
                        onDismissRequest = { mainModelExpanded = false }
                    ) {
                        mainModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onActiveModelChange(model)
                                    mainModelExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        SettingsDivider()

        val componentLabels = com.example.japanesegrammarapp.domain.model.AnalysisModule.entries.map { it.id }

        // モジュール別モデル設定 - 弹窗式单独配置
        val overrideCount = componentLabels.count {
            uiState.componentModelConfigs[it]?.isGlobal == false
        }
        val moduleModelSubtitle = if (overrideCount == 0) {
            stringResource(R.string.module_model_desc)
        } else {
            stringResource(R.string.module_model_overrides_count, overrideCount)
        }
        SettingsItem(
            icon = Icons.Default.Hub,
            title = stringResource(R.string.module_model_title),
            subtitle = moduleModelSubtitle,
            onClick = onOpenComponentModelDialog,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (overrideCount > 0) {
                        SettingsValueBadge(text = overrideCount.toString(), highlighted = true)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = sumiInk.copy(alpha = 0.5f)
                    )
                }
            }
        )

        SettingsDivider()

        // 思考链 (CoT) 思考深度 - 弹窗式单独配置
        val strOff = stringResource(R.string.cot_level_off)
        val strAuto = stringResource(R.string.cot_level_auto)
        val strLow = stringResource(R.string.cot_level_low)
        val strMedium = stringResource(R.string.cot_level_medium)
        val strHigh = stringResource(R.string.cot_level_high)
        val strDesc = stringResource(R.string.cot_dialog_desc)

        val levelCounts = componentLabels.map { uiState.componentReasoningLevels[it] ?: ComponentReasoningLevel.GLOBAL }
            .groupingBy { it }
            .eachCount()

        val summaryText = if (levelCounts.size == 1) {
            val singleLevel = levelCounts.keys.first()
            val levelLabel = when (singleLevel) {
                ComponentReasoningLevel.OFF -> strOff
                ComponentReasoningLevel.AUTO, ComponentReasoningLevel.GLOBAL -> strAuto
                ComponentReasoningLevel.LOW -> strLow
                ComponentReasoningLevel.MEDIUM -> strMedium
                ComponentReasoningLevel.HIGH -> strHigh
            }
            "$strDesc ($levelLabel)"
        } else {
            val details = levelCounts.entries.joinToString(", ") { (level, count) ->
                val name = when (level) {
                    ComponentReasoningLevel.OFF -> strOff
                    ComponentReasoningLevel.AUTO, ComponentReasoningLevel.GLOBAL -> strAuto
                    ComponentReasoningLevel.LOW -> strLow
                    ComponentReasoningLevel.MEDIUM -> strMedium
                    ComponentReasoningLevel.HIGH -> strHigh
                }
                "$name: $count"
            }
            details
        }

        SettingsItem(
            icon = Icons.Default.Psychology,
            title = stringResource(R.string.reasoning_level),
            subtitle = summaryText,
            onClick = onOpenCotDialog,
            trailingContent = {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = sumiInk.copy(alpha = 0.5f)
                )
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.streaming_enabled),
            subtitle = stringResource(R.string.streaming_enabled_desc),
            onClick = { onStreamingEnabledChange(!uiState.streamingEnabled) },
            trailingContent = {
                Switch(
                    checked = uiState.streamingEnabled,
                    onCheckedChange = onStreamingEnabledChange,
                    colors = SettingsSwitchColors()
                )
            }
        )
    }
}

@Composable
fun SettingsApiFailoverSection(
    uiState: SettingsUiState,
    providers: List<String>,
    providerModels: Map<String, List<String>>,
    onBackupProviderChange: (String) -> Unit,
    onBackupModelChange: (String) -> Unit,
    onUseBackupApiChange: (Boolean) -> Unit,
    onAutoRetryOnErrorChange: (Boolean) -> Unit,
    onFailoverToNextEndpointChange: (Boolean) -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground

    SettingsGroup(title = stringResource(R.string.settings_llm_group_failover)) {
        SettingsItem(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.auto_retry_on_error),
            subtitle = stringResource(R.string.auto_retry_on_error_desc),
            onClick = { onAutoRetryOnErrorChange(!uiState.autoRetryOnError) },
            trailingContent = {
                Switch(
                    checked = uiState.autoRetryOnError,
                    onCheckedChange = onAutoRetryOnErrorChange,
                    colors = SettingsSwitchColors()
                )
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.SwapHoriz,
            title = stringResource(R.string.failover_to_next_endpoint),
            subtitle = stringResource(R.string.failover_to_next_endpoint_desc),
            onClick = { onFailoverToNextEndpointChange(!uiState.failoverToNextEndpoint) },
            trailingContent = {
                Switch(
                    checked = uiState.failoverToNextEndpoint,
                    onCheckedChange = onFailoverToNextEndpointChange,
                    colors = SettingsSwitchColors()
                )
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.Backup,
            title = stringResource(R.string.use_backup_api),
            subtitle = stringResource(R.string.use_backup_api_desc),
            onClick = { onUseBackupApiChange(!uiState.useBackupApi) },
            trailingContent = {
                Switch(
                    checked = uiState.useBackupApi,
                    onCheckedChange = onUseBackupApiChange,
                    colors = SettingsSwitchColors()
                )
            }
        )

        AnimatedVisibility(
            visible = uiState.useBackupApi,
            enter = fadeIn(animationSpec = tween(300)) + expandVertically(),
            exit = fadeOut(animationSpec = tween(300)) + shrinkVertically()
        ) {
            Column {
                SettingsDivider()

                var backupProviderExpanded by remember { mutableStateOf(false) }
                SettingsItem(
                    icon = Icons.Default.Backup,
                    title = stringResource(R.string.backup_api),
                    onClick = { backupProviderExpanded = true },
                    trailingContent = {
                        Box {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsValueBadge(text = uiState.backupProvider)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = sumiInk.copy(alpha = 0.5f))
                            }
                            DropdownMenu(
                                expanded = backupProviderExpanded,
                                onDismissRequest = { backupProviderExpanded = false }
                            ) {
                                providers.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider) },
                                        onClick = {
                                            onBackupProviderChange(provider)
                                            backupProviderExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )

                SettingsDivider()

                var backupModelExpanded by remember { mutableStateOf(false) }
                val backupModels = providerModels[uiState.backupProvider].orEmpty()
                SettingsItem(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.backup_model),
                    subtitle = uiState.backupModel.ifBlank { stringResource(R.string.unselected) },
                    onClick = { backupModelExpanded = true },
                    trailingContent = {
                        Box {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = sumiInk.copy(alpha = 0.5f))
                            DropdownMenu(
                                expanded = backupModelExpanded,
                                onDismissRequest = { backupModelExpanded = false }
                            ) {
                                backupModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            onBackupModelChange(model)
                                            backupModelExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsCredentialsSection(
    uiState: SettingsUiState,
    providers: List<String>,
    providerModels: Map<String, List<String>>,
    expandedProvider: String?,
    customModelInputs: Map<String, String>,
    onExpandedProviderChange: (String?) -> Unit,
    onAddEndpoint: (String) -> Unit,
    onEditEndpoint: (LlmEndpoint) -> Unit,
    onDeleteEndpoint: (LlmEndpoint) -> Unit,
    onToggleEndpoint: (String, LlmEndpoint, Boolean) -> Unit,
    onFetchModels: (String, LlmEndpoint) -> Unit,
    onFetchModelsForProvider: (String) -> Unit,
    onCustomModelInputChange: (String, String) -> Unit,
    onAddCustomModel: (String, List<String>) -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    Text(
        text = stringResource(R.string.credentials),
        style = MaterialTheme.typography.labelLarge,
        color = sumiInk.copy(alpha = 0.65f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
    )

    providers.forEach { provider ->
        val isExpanded = expandedProvider == provider
        val endpoints = uiState.providerEndpoints[provider].orEmpty()
        val activeEndpointCount = endpoints.count { it.enabled && uiState.endpointHasKeys[it.id] == true }
        val modelCount = providerModels[provider].orEmpty().size
        val isMainProvider = provider == uiState.activeProvider
        val isBackupProvider = uiState.useBackupApi && provider == uiState.backupProvider

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = BorderStroke(
                width = 1.dp,
                color = if (isMainProvider) primaryColor.copy(alpha = 0.35f) else sumiInk.copy(alpha = 0.08f)
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExpandedProviderChange(if (isExpanded) null else provider) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (activeEndpointCount > 0) primaryColor.copy(alpha = 0.12f)
                                    else sumiInk.copy(alpha = 0.06f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                tint = if (activeEndpointCount > 0) primaryColor else sumiInk.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = provider,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = sumiInk
                                )
                                if (isMainProvider) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    SettingsValueBadge(
                                        text = stringResource(R.string.provider_badge_main),
                                        highlighted = true
                                    )
                                }
                                if (isBackupProvider) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    SettingsValueBadge(
                                        text = stringResource(R.string.provider_badge_backup),
                                        highlighted = false
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (activeEndpointCount > 0) {
                                    stringResource(R.string.provider_summary_configured, activeEndpointCount, modelCount)
                                } else {
                                    stringResource(R.string.provider_summary_unconfigured, modelCount)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (activeEndpointCount > 0) sumiInk.copy(alpha = 0.65f) else sumiInk.copy(alpha = 0.45f)
                            )
                        }
                    }
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = sumiInk.copy(alpha = 0.5f)
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(160))
                ) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        SettingsDivider(horizontalPadding = 0.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        val isFetchingProvider = uiState.isFetchingModels && uiState.fetchingProvider == provider && uiState.fetchingEndpointId == null

                        EndpointPoolSection(
                            endpoints = endpoints,
                            endpointHasKeys = uiState.endpointHasKeys,
                            endpointHealthStates = uiState.endpointHealthStates,
                            fetchingEndpointId = uiState.fetchingEndpointId,
                            onAddEndpoint = { onAddEndpoint(provider) },
                            onEditEndpoint = onEditEndpoint,
                            onDeleteEndpoint = onDeleteEndpoint,
                            onToggleEndpoint = { endpoint, enabled ->
                                onToggleEndpoint(provider, endpoint, enabled)
                            },
                            onFetchModels = { endpoint ->
                                onFetchModels(provider, endpoint)
                            },
                            onFetchModelsForProvider = { onFetchModelsForProvider(provider) },
                            isFetchingProvider = isFetchingProvider,
                            isFetchingAnyModel = uiState.isFetchingModels
                        )

                        Spacer(modifier = Modifier.size(12.dp))
                        val customModelInput = customModelInputs[provider].orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customModelInput,
                                onValueChange = { onCustomModelInputChange(provider, it) },
                                label = { Text(stringResource(R.string.add_custom_model)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (customModelInput.isNotBlank()) {
                                        val currentModels = providerModels[provider].orEmpty()
                                        if (!currentModels.contains(customModelInput)) {
                                            onAddCustomModel(provider, currentModels + customModelInput)
                                        }
                                        onCustomModelInputChange(provider, "")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = onPrimaryColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDecisionApiSection(
    uiState: SettingsUiState,
    onDecisionApiEnabledChange: (Boolean) -> Unit,
    onDecisionModelSave: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onEditEndpoint: (LlmEndpoint) -> Unit,
    onToggleEndpoint: (LlmEndpoint, Boolean) -> Unit,
    onDeleteEndpoint: (LlmEndpoint) -> Unit
) {
    val defaultModel = com.example.japanesegrammarapp.domain.model.DecisionConfig.DEFAULT_MODEL
    val modelDraftState = remember(uiState.decisionModel) { mutableStateOf(uiState.decisionModel) }
    val trimmedDraft = modelDraftState.value.trim().ifBlank { defaultModel }
    val hasUsableEndpoint = uiState.decisionEndpoints.any {
        it.enabled && (uiState.endpointHasKeys[it.id] == true)
    }

    SettingsGroup(title = stringResource(R.string.settings_llm_group_decision)) {
        SettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.decision_api_enable),
            subtitle = stringResource(R.string.decision_api_enable_desc),
            onClick = { onDecisionApiEnabledChange(!uiState.decisionApiEnabled) },
            trailingContent = {
                Switch(
                    checked = uiState.decisionApiEnabled,
                    onCheckedChange = onDecisionApiEnabledChange,
                    colors = SettingsSwitchColors()
                )
            }
        )

        AnimatedVisibility(visible = uiState.decisionApiEnabled) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.decision_api_model),
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = modelDraftState.value,
                            onValueChange = { modelDraftState.value = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.decision_api_model_hint)) }
                        )
                        Button(
                            onClick = { onDecisionModelSave(trimmedDraft) },
                            enabled = trimmedDraft != uiState.decisionModel
                        ) {
                            Text(stringResource(R.string.decision_api_save_model))
                        }
                        if (uiState.decisionModel != defaultModel || modelDraftState.value.trim() != defaultModel) {
                            OutlinedButton(
                                onClick = {
                                    modelDraftState.value = defaultModel
                                    onDecisionModelSave(defaultModel)
                                }
                            ) {
                                Text(stringResource(R.string.decision_api_reset_model))
                            }
                        }
                    }

                    if (!hasUsableEndpoint) {
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.decision_api_missing_endpoint_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.decision_api_endpoint_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SettingsDivider()

                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    EndpointPoolSection(
                        endpoints = uiState.decisionEndpoints,
                        endpointHasKeys = uiState.endpointHasKeys,
                        endpointHealthStates = uiState.endpointHealthStates,
                        fetchingEndpointId = null,
                        onAddEndpoint = onAddEndpoint,
                        onEditEndpoint = onEditEndpoint,
                        onToggleEndpoint = onToggleEndpoint,
                        onDeleteEndpoint = onDeleteEndpoint,
                        onFetchModels = null
                    )
                }
            }
        }
    }
}

