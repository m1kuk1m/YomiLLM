package com.example.japanesegrammarapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Abc
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.japanesegrammarapp.R
import com.example.japanesegrammarapp.domain.model.OcrBoxDetectorEngine
import com.example.japanesegrammarapp.domain.model.WordSegment
import com.example.japanesegrammarapp.ui.SettingsUiState
import com.example.japanesegrammarapp.ui.screens.components.SegmentChip

@Composable
fun SettingsAppearanceSection(
    uiState: SettingsUiState,
    onThemeModeChange: (String) -> Unit,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground

    SettingsGroup(title = stringResource(R.string.settings_appearance_theme_group)) {
        var themeDropdownExpanded by remember { mutableStateOf(false) }
        val themeLabel = when (uiState.themeMode) {
            "Light" -> stringResource(R.string.theme_light)
            "Dark" -> stringResource(R.string.theme_dark)
            else -> stringResource(R.string.theme_system)
        }
        SettingsItem(
            icon = Icons.Default.BrightnessMedium,
            title = stringResource(R.string.theme_mode),
            onClick = { themeDropdownExpanded = true },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsValueBadge(text = themeLabel)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = sumiInk.copy(alpha = 0.5f)
                        )
                    }
                    DropdownMenu(
                        expanded = themeDropdownExpanded,
                        onDismissRequest = { themeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.theme_system)) },
                            onClick = {
                                onThemeModeChange("System")
                                themeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.theme_light)) },
                            onClick = {
                                onThemeModeChange("Light")
                                themeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.theme_dark)) },
                            onClick = {
                                onThemeModeChange("Dark")
                                themeDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.Wallpaper,
            title = stringResource(R.string.wallpaper),
            subtitle = if (uiState.wallpaperUri.isNotBlank()) {
                stringResource(R.string.custom_image_set)
            } else {
                stringResource(R.string.none)
            },
            onClick = onPickWallpaper,
            trailingContent = {
                if (uiState.wallpaperUri.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = uiState.wallpaperUri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onClearWallpaper,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear_wallpaper),
                                tint = sumiInk.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else {
                    TextButton(onClick = onPickWallpaper) {
                        Text(stringResource(R.string.pick_wallpaper), color = sumiInk)
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsGeneralSection(
    uiState: SettingsUiState,
    currentLangLabel: String,
    onAutoNavigateResultChange: (Boolean) -> Unit,
    onSilentBackgroundModeChange: (Boolean) -> Unit,
    onRemoveAccidentalSpacesChange: (Boolean) -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    SettingsGroup(title = stringResource(R.string.settings_general_language_group)) {
        var langDropdownExpanded by remember { mutableStateOf(false) }
        val displayLangLabel = if (currentLangLabel == "AUTO") {
            stringResource(R.string.language_auto)
        } else {
            currentLangLabel
        }

        SettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.language),
            onClick = { langDropdownExpanded = true },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsValueBadge(text = displayLangLabel)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = sumiInk.copy(alpha = 0.5f)
                        )
                    }
                    DropdownMenu(
                        expanded = langDropdownExpanded,
                        onDismissRequest = { langDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.language_auto)) },
                            onClick = {
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                                )
                                langDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("English") },
                            onClick = {
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    androidx.core.os.LocaleListCompat.forLanguageTags("en")
                                )
                                langDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("简体中文") },
                            onClick = {
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    androidx.core.os.LocaleListCompat.forLanguageTags("zh")
                                )
                                langDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("日本語") },
                            onClick = {
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    androidx.core.os.LocaleListCompat.forLanguageTags("ja")
                                )
                                langDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        )
    }

    SettingsGroup(title = stringResource(R.string.settings_general_workflow_group)) {
        SettingsItem(
            icon = Icons.Default.Launch,
            title = stringResource(R.string.auto_navigate_result),
            subtitle = stringResource(R.string.auto_navigate_result_desc),
            onClick = { onAutoNavigateResultChange(!uiState.autoNavigateResult) },
            trailingContent = {
                Switch(
                    checked = uiState.autoNavigateResult,
                    onCheckedChange = onAutoNavigateResultChange,
                    colors = SettingsSwitchColors(onPrimaryColor, primaryColor, sumiInk)
                )
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.PictureInPictureAlt,
            title = stringResource(R.string.silent_background_mode),
            subtitle = stringResource(R.string.silent_background_mode_desc),
            onClick = { onSilentBackgroundModeChange(!uiState.silentBackgroundMode) },
            trailingContent = {
                Switch(
                    checked = uiState.silentBackgroundMode,
                    onCheckedChange = onSilentBackgroundModeChange,
                    colors = SettingsSwitchColors(onPrimaryColor, primaryColor, sumiInk)
                )
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.AutoFixHigh,
            title = stringResource(R.string.remove_accidental_spaces),
            subtitle = stringResource(R.string.remove_accidental_spaces_desc),
            onClick = { onRemoveAccidentalSpacesChange(!uiState.removeAccidentalSpaces) },
            trailingContent = {
                Switch(
                    checked = uiState.removeAccidentalSpaces,
                    onCheckedChange = onRemoveAccidentalSpacesChange,
                    colors = SettingsSwitchColors(onPrimaryColor, primaryColor, sumiInk)
                )
            }
        )
    }
}

@Composable
fun SettingsOcrSection(
    uiState: SettingsUiState,
    onUseOcrChange: (Boolean) -> Unit,
    onOcrBoxDetectorEngineChange: (OcrBoxDetectorEngine) -> Unit,
    onTextSelectEngineChange: (OcrBoxDetectorEngine) -> Unit,
    onAutoDeskewAfterCaptureChange: (Boolean) -> Unit,
    onShowOcrDebug: () -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    SettingsGroup(title = stringResource(R.string.settings_ocr_basic_group)) {
        SettingsItem(
            icon = Icons.Default.ImageSearch,
            title = stringResource(R.string.local_ocr),
            subtitle = stringResource(R.string.local_ocr_desc),
            onClick = { onUseOcrChange(!uiState.useOcr) },
            trailingContent = {
                Switch(
                    checked = uiState.useOcr,
                    onCheckedChange = onUseOcrChange,
                    colors = SettingsSwitchColors(onPrimaryColor, primaryColor, sumiInk)
                )
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.AutoFixHigh,
            title = stringResource(R.string.settings_auto_deskew),
            subtitle = stringResource(R.string.settings_auto_deskew_desc),
            onClick = { onAutoDeskewAfterCaptureChange(!uiState.autoDeskewAfterCapture) },
            trailingContent = {
                Switch(
                    checked = uiState.autoDeskewAfterCapture,
                    onCheckedChange = onAutoDeskewAfterCaptureChange,
                    colors = SettingsSwitchColors(onPrimaryColor, primaryColor, sumiInk)
                )
            }
        )

        SettingsDivider()

        var detectorEngineDropdownExpanded by remember { mutableStateOf(false) }
        val detectorEngineLabel = ocrBoxDetectorEngineLabel(uiState.ocrBoxDetectionSettings.detectorEngine)

        SettingsItem(
            icon = Icons.Default.CenterFocusStrong,
            title = stringResource(R.string.ocr_detector_engine_title),
            onClick = { detectorEngineDropdownExpanded = true },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsValueBadge(text = detectorEngineLabel)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = sumiInk.copy(alpha = 0.5f)
                        )
                    }
                    DropdownMenu(
                        expanded = detectorEngineDropdownExpanded,
                        onDismissRequest = { detectorEngineDropdownExpanded = false }
                    ) {
                        OcrBoxDetectorEngine.entries.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(ocrBoxDetectorEngineLabel(engine)) },
                                onClick = {
                                    onOcrBoxDetectorEngineChange(engine)
                                    detectorEngineDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        SettingsDivider()

        var textSelectEngineDropdownExpanded by remember { mutableStateOf(false) }
        val textSelectEngineLabel = ocrBoxDetectorEngineLabel(uiState.ocrBoxDetectionSettings.textSelectEngine)

        SettingsItem(
            icon = Icons.Default.Abc,
            title = stringResource(R.string.ocr_text_select_engine_title),
            onClick = { textSelectEngineDropdownExpanded = true },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsValueBadge(text = textSelectEngineLabel)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = sumiInk.copy(alpha = 0.5f)
                        )
                    }
                    DropdownMenu(
                        expanded = textSelectEngineDropdownExpanded,
                        onDismissRequest = { textSelectEngineDropdownExpanded = false }
                    ) {
                        listOf(OcrBoxDetectorEngine.ML_KIT, OcrBoxDetectorEngine.RAPID_OCR).forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(ocrBoxDetectorEngineLabel(engine)) },
                                onClick = {
                                    onTextSelectEngineChange(engine)
                                    textSelectEngineDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    SettingsGroup(title = stringResource(R.string.settings_ocr_advanced_group)) {
        SettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.ocr_debug_title),
            subtitle = stringResource(R.string.ocr_debug_entry_desc),
            onClick = onShowOcrDebug,
            trailingContent = {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = sumiInk.copy(alpha = 0.4f)
                )
            }
        )
    }
}

@Composable
fun SettingsLlmPromptsSection(
    totalTokensConsumed: Int,
    activePresetName: String? = null,
    onShowTokenDialog: () -> Unit,
    onShowPromptEditor: () -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground

    SettingsGroup(title = stringResource(R.string.settings_llm_group_prompts_usage)) {
        SettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.custom_prompts_title),
            subtitle = stringResource(R.string.custom_prompts_desc),
            onClick = onShowPromptEditor,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!activePresetName.isNullOrBlank()) {
                        SettingsValueBadge(text = activePresetName)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = sumiInk.copy(alpha = 0.4f)
                    )
                }
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.DataUsage,
            title = stringResource(R.string.token_usage),
            subtitle = stringResource(R.string.token_usage_desc),
            onClick = onShowTokenDialog,
            trailingContent = {
                val formattedTotal = if (totalTokensConsumed >= 1000) {
                    String.format(java.util.Locale.US, "%.1fk", totalTokensConsumed / 1000.0)
                } else {
                    totalTokensConsumed.toString()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsValueBadge(text = formattedTotal, highlighted = true)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = sumiInk.copy(alpha = 0.4f)
                    )
                }
            }
        )
    }
}

@Composable
fun SettingsAdvancedSection(
    onShowApiLogs: () -> Unit,
    onShowAppLogs: () -> Unit,
    onShowOcrDebug: (() -> Unit)? = null
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground

    SettingsGroup(title = stringResource(R.string.settings_advanced_logs_group)) {
        SettingsItem(
            icon = Icons.Default.Code,
            title = stringResource(R.string.view_api_debug_logs),
            subtitle = stringResource(R.string.api_debug_logs_title),
            onClick = onShowApiLogs,
            trailingContent = {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = sumiInk.copy(alpha = 0.4f)
                )
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.Default.BugReport,
            title = stringResource(R.string.app_logs_title),
            subtitle = stringResource(R.string.view_dev_logs),
            onClick = onShowAppLogs,
            trailingContent = {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = sumiInk.copy(alpha = 0.4f)
                )
            }
        )

        if (onShowOcrDebug != null) {
            SettingsDivider()

            SettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.ocr_debug_title),
                subtitle = stringResource(R.string.ocr_debug_entry_desc),
                onClick = onShowOcrDebug,
                trailingContent = {
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = sumiInk.copy(alpha = 0.4f)
                    )
                }
            )
        }
    }
}

@Composable
fun ocrBoxDetectorEngineLabel(engine: OcrBoxDetectorEngine): String {
    return when (engine) {
        OcrBoxDetectorEngine.ML_KIT -> stringResource(R.string.ocr_debug_engine_mlkit)
        OcrBoxDetectorEngine.RAPID_OCR -> stringResource(R.string.ocr_debug_engine_rapidocr)
        OcrBoxDetectorEngine.HYBRID -> stringResource(R.string.ocr_debug_engine_hybrid)
        OcrBoxDetectorEngine.AUTO -> stringResource(R.string.ocr_debug_engine_auto)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsCardAppearanceSection(
    uiState: SettingsUiState,
    onFontSizeScaleChange: (Float) -> Unit,
    onSpacingScaleChange: (Float) -> Unit,
    onFuriganaSizeScaleChange: (Float) -> Unit,
    onCardInternalPaddingScaleChange: (Float) -> Unit,
    onFuriganaGapScaleChange: (Float) -> Unit,
    onCardDetailDisplayModeChange: (String) -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val mockSegments = remember {
        listOf(
            WordSegment(text = "今日", reading = "きょう", partOfSpeech = "名詞", posCategory = "NOUN", meaning = "preview"),
            WordSegment(text = "は", reading = "は", partOfSpeech = "助词", posCategory = "PARTICLE", meaning = "preview"),
            WordSegment(text = "とても", reading = "とても", partOfSpeech = "副詞", posCategory = "ADVERB", meaning = "preview"),
            WordSegment(text = "天気", reading = "てんき", partOfSpeech = "名詞", posCategory = "NOUN", meaning = "preview"),
            WordSegment(text = "が", reading = "が", partOfSpeech = "助词", posCategory = "PARTICLE", meaning = "preview"),
            WordSegment(text = "いい", reading = "いい", partOfSpeech = "形容詞", posCategory = "ADJECTIVE", meaning = "preview"),
            WordSegment(text = "です", reading = "です", partOfSpeech = "助動詞", posCategory = "AUXILIARY", meaning = "preview"),
            WordSegment(text = "ね", reading = "ね", partOfSpeech = "助词", posCategory = "PARTICLE", meaning = "preview")
        )
    }

    val resetDefaultsAction = {
        onFontSizeScaleChange(1.0f)
        onSpacingScaleChange(1.0f)
        onFuriganaSizeScaleChange(1.0f)
        onCardInternalPaddingScaleChange(1.0f)
        onFuriganaGapScaleChange(1.0f)
        onCardDetailDisplayModeChange("POPUP")
    }

    SettingsGroup(title = stringResource(R.string.card_appearance)) {
        // 1. Top-mounted Live Card Preview Area so fingers don't block the preview while dragging sliders
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.card_preview_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = sumiInk.copy(alpha = 0.65f)
                )
                TextButton(
                    onClick = resetDefaultsAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = sumiInk.copy(alpha = 0.65f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.restore_defaults),
                        fontSize = 12.sp,
                        color = sumiInk.copy(alpha = 0.7f)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .border(BorderStroke(1.dp, sumiInk.copy(alpha = 0.06f)), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp * uiState.cardSpacingScale),
                    verticalArrangement = Arrangement.spacedBy(10.dp * uiState.cardSpacingScale)
                ) {
                    mockSegments.forEach { segment ->
                        SegmentChip(
                            segment = segment,
                            isSelected = false,
                            fontScale = uiState.cardFontSizeScale,
                            spacingScale = uiState.cardSpacingScale,
                            furiganaScale = uiState.furiganaSizeScale,
                            internalPaddingScale = uiState.cardInternalPaddingScale,
                            furiganaGapScale = uiState.furiganaGapScale,
                            onClick = {}
                        )
                    }
                }
            }
        }

        SettingsDivider()

        // 2. Detail Display Mode Selector
        var displayModeDropdownExpanded by remember { mutableStateOf(false) }
        val displayModeLabel = when (uiState.cardDetailDisplayMode) {
            "POPUP" -> stringResource(R.string.card_detail_popup)
            else -> stringResource(R.string.card_detail_inline)
        }
        SettingsItem(
            icon = Icons.Default.PictureInPictureAlt,
            title = stringResource(R.string.card_detail_display_mode),
            onClick = { displayModeDropdownExpanded = true },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsValueBadge(text = displayModeLabel)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = sumiInk.copy(alpha = 0.5f)
                        )
                    }
                    DropdownMenu(
                        expanded = displayModeDropdownExpanded,
                        onDismissRequest = { displayModeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_detail_inline)) },
                            onClick = {
                                onCardDetailDisplayModeChange("INLINE")
                                displayModeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_detail_popup)) },
                            onClick = {
                                onCardDetailDisplayModeChange("POPUP")
                                displayModeDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        )

        SettingsDivider()

        // 3. Typography Sliders (Font Size -> Furigana Size -> Furigana Gap)
        SettingsSliderItem(
            icon = Icons.Default.FormatSize,
            title = stringResource(R.string.card_font_size),
            value = uiState.cardFontSizeScale,
            onValueChange = onFontSizeScaleChange,
            valueRange = 0.5f..2.0f
        )

        SettingsDivider()

        SettingsSliderItem(
            icon = Icons.Default.Abc,
            title = stringResource(R.string.furigana_size),
            value = uiState.furiganaSizeScale,
            onValueChange = onFuriganaSizeScaleChange,
            valueRange = 0.5f..2.0f
        )

        SettingsDivider()

        SettingsSliderItem(
            icon = Icons.Default.Height,
            title = stringResource(R.string.furigana_gap),
            value = uiState.furiganaGapScale,
            onValueChange = onFuriganaGapScaleChange,
            valueRange = 0.0f..3.0f
        )

        SettingsDivider()

        // 4. Layout Sliders (Internal Padding -> External Spacing)
        SettingsSliderItem(
            icon = Icons.Default.CropFree,
            title = stringResource(R.string.card_internal_padding),
            value = uiState.cardInternalPaddingScale,
            onValueChange = onCardInternalPaddingScaleChange,
            valueRange = 0.5f..2.0f
        )

        SettingsDivider()

        SettingsSliderItem(
            icon = Icons.Default.SpaceBar,
            title = stringResource(R.string.card_spacing),
            value = uiState.cardSpacingScale,
            onValueChange = onSpacingScaleChange,
            valueRange = 0.5f..2.0f
        )
    }
}


