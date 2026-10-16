package com.example.japanesegrammarapp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.japanesegrammarapp.R

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsTtsSection(
    selectedTtsProvider: String,
    onSelectedTtsProviderChange: (String) -> Unit,
    ttsUrls: Map<String, String>,
    onTtsUrlChange: (String, String) -> Unit,
    ttsKeys: Map<String, String>,
    savedTtsKeys: Map<String, String>,
    onTtsKeyChange: (String, String) -> Unit,
    ttsModels: Map<String, String>,
    onTtsModelChange: (String, String) -> Unit,
    ttsVoices: Map<String, String>,
    onTtsVoiceChange: (String, String) -> Unit,
    ttsRegions: Map<String, String>,
    onTtsRegionChange: (String, String) -> Unit,
    onSaveTtsSettings: () -> Unit,
    onRequestClearTtsKey: (String) -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val defaultKeyboardActions = KeyboardActions(onDone = {
        keyboardController?.hide()
        focusManager.clearFocus()
    })

    val isKeySaved = savedTtsKeys[selectedTtsProvider]?.isNotBlank() == true

    SettingsGroup(
        title = stringResource(R.string.settings_tts_group_config),
        headerTrailing = {
            SettingsValueBadge(
                text = if (isKeySaved) {
                    stringResource(R.string.tts_key_configured)
                } else {
                    stringResource(R.string.tts_key_not_configured)
                },
                highlighted = isKeySaved
            )
        }
    ) {
        var ttsProviderExpanded by remember { mutableStateOf(false) }
        val ttsProviders = listOf("OpenAI", "Google", "Microsoft")

        SettingsItem(
            icon = Icons.Default.RecordVoiceOver,
            title = stringResource(R.string.tts_provider),
            onClick = { ttsProviderExpanded = true },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsValueBadge(text = selectedTtsProvider, highlighted = true)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = sumiInk.copy(alpha = 0.5f))
                    }
                    DropdownMenu(
                        expanded = ttsProviderExpanded,
                        onDismissRequest = { ttsProviderExpanded = false }
                    ) {
                        ttsProviders.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider) },
                                onClick = {
                                    onSelectedTtsProviderChange(provider)
                                    ttsProviderExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        SettingsDivider()

        Column(modifier = Modifier.padding(16.dp)) {
            var ttsKeyVisible by remember { mutableStateOf(false) }

            if (selectedTtsProvider == "OpenAI" || selectedTtsProvider == "Google") {
                OutlinedTextField(
                    value = ttsUrls[selectedTtsProvider] ?: "",
                    onValueChange = { onTtsUrlChange(selectedTtsProvider, it) },
                    label = { Text(stringResource(R.string.base_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = defaultKeyboardActions
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (selectedTtsProvider == "Microsoft") {
                OutlinedTextField(
                    value = ttsRegions[selectedTtsProvider] ?: "",
                    onValueChange = { onTtsRegionChange(selectedTtsProvider, it) },
                    label = { Text(stringResource(R.string.tts_region_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = defaultKeyboardActions
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedTextField(
                value = ttsKeys[selectedTtsProvider] ?: "",
                onValueChange = { onTtsKeyChange(selectedTtsProvider, it) },
                label = { Text(stringResource(R.string.api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (ttsKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { ttsKeyVisible = !ttsKeyVisible }) {
                        Icon(if (ttsKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)
                    }
                },
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = defaultKeyboardActions
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.settings_tts_group_voice),
                style = MaterialTheme.typography.labelMedium,
                color = sumiInk.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            if (selectedTtsProvider == "OpenAI") {
                OutlinedTextField(
                    value = ttsModels[selectedTtsProvider] ?: "",
                    onValueChange = { onTtsModelChange(selectedTtsProvider, it) },
                    label = { Text(stringResource(R.string.tts_model_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = defaultKeyboardActions
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedTextField(
                value = ttsVoices[selectedTtsProvider] ?: "",
                onValueChange = { onTtsVoiceChange(selectedTtsProvider, it) },
                label = { Text(stringResource(R.string.tts_voice_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = defaultKeyboardActions
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val key = ttsKeys[selectedTtsProvider] ?: ""
                    if (key.isBlank() && savedTtsKeys[selectedTtsProvider]?.isNotBlank() == true) {
                        onRequestClearTtsKey(selectedTtsProvider)
                    } else {
                        onSaveTtsSettings()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp), tint = onPrimaryColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.save_tts_settings),
                    color = onPrimaryColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

