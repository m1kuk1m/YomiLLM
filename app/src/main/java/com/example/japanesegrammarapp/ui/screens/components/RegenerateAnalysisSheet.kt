package com.example.japanesegrammarapp.ui.screens.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.japanesegrammarapp.R
import com.example.japanesegrammarapp.domain.model.AnalysisStep
import com.example.japanesegrammarapp.domain.model.RegeneratableModule
import com.example.japanesegrammarapp.ui.theme.ZenColors.AizomeIndigo
import com.example.japanesegrammarapp.ui.theme.ZenColors.MatchaGreen
import com.example.japanesegrammarapp.ui.theme.ZenColors.SakuraPink
import com.example.japanesegrammarapp.ui.theme.ZenThemeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegenerateAnalysisBottomSheet(
    regeneratingModules: Set<RegeneratableModule>,
    canRegenerateSegmentsOnly: Boolean,
    moduleErrors: Map<String, String>,
    onRetryAll: () -> Unit,
    onRegenerateModule: (RegeneratableModule) -> Unit,
    onDismiss: () -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val surfaceColor = MaterialTheme.colorScheme.surface
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeConflictGroups = regeneratingModules.map { it.conflictGroup }.toSet()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp)
        ) {
            // Subtle centered pill handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(sumiInk.copy(alpha = 0.16f))
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.regenerate_sheet_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = sumiInk,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Primary action: Re-analyze All
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = surfaceColor,
                shadowElevation = 1.dp,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, sumiInk.copy(alpha = 0.06f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onRetryAll()
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ZenThemeColors.pillBg()),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = sumiInk,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.regenerate_all_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = sumiInk
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.regenerate_all_desc),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = sumiInk.copy(alpha = 0.62f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = sumiInk.copy(alpha = 0.35f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.regenerate_by_module_header),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = sumiInk.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Grouped module options
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = surfaceColor,
                shadowElevation = 1.dp,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, sumiInk.copy(alpha = 0.06f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val hasTokenizerOrSegmentsError =
                        moduleErrors.containsKey(AnalysisStep.TOKENIZATION.name) ||
                            moduleErrors.containsKey(AnalysisStep.DETAILED_GRAMMAR.name)
                    val hasSegmentsOnlyError =
                        moduleErrors.containsKey(AnalysisStep.DETAILED_GRAMMAR.name)
                    val hasTranslationError =
                        moduleErrors.containsKey(AnalysisStep.TRANSLATION.name)
                    val hasClausesError =
                        moduleErrors.containsKey(AnalysisStep.CLAUSE_ANALYSIS.name)
                    val hasGrammarError =
                        moduleErrors.containsKey(AnalysisStep.GRAMMAR_EXPLANATION.name)

                    RegenerateModuleRow(
                        dotColor = sumiInk.copy(alpha = 0.65f),
                        title = stringResource(R.string.regenerate_tokenize_and_segments),
                        subtitle = stringResource(R.string.regenerate_tokenize_and_segments_desc),
                        isRegenerating = RegeneratableModule.TOKENIZATION_AND_SEGMENTS in regeneratingModules,
                        isConflictBusy = RegeneratableModule.TOKENIZATION_AND_SEGMENTS.conflictGroup in activeConflictGroups,
                        enabled = true,
                        hasError = hasTokenizerOrSegmentsError,
                        onClick = {
                            onDismiss()
                            onRegenerateModule(RegeneratableModule.TOKENIZATION_AND_SEGMENTS)
                        }
                    )

                    SheetRowDivider(sumiInk)

                    RegenerateModuleRow(
                        dotColor = sumiInk.copy(alpha = 0.35f),
                        title = stringResource(R.string.regenerate_segments_only),
                        subtitle = stringResource(R.string.regenerate_segments_only_desc),
                        isRegenerating = RegeneratableModule.SEGMENTS_ONLY in regeneratingModules,
                        isConflictBusy = RegeneratableModule.SEGMENTS_ONLY.conflictGroup in activeConflictGroups,
                        enabled = canRegenerateSegmentsOnly,
                        hasError = hasSegmentsOnlyError,
                        onClick = {
                            onDismiss()
                            onRegenerateModule(RegeneratableModule.SEGMENTS_ONLY)
                        }
                    )

                    SheetRowDivider(sumiInk)

                    RegenerateModuleRow(
                        dotColor = SakuraPink,
                        title = stringResource(R.string.overall_translation),
                        subtitle = null,
                        isRegenerating = RegeneratableModule.TRANSLATION in regeneratingModules,
                        isConflictBusy = RegeneratableModule.TRANSLATION.conflictGroup in activeConflictGroups,
                        enabled = true,
                        hasError = hasTranslationError,
                        onClick = {
                            onDismiss()
                            onRegenerateModule(RegeneratableModule.TRANSLATION)
                        }
                    )

                    SheetRowDivider(sumiInk)

                    RegenerateModuleRow(
                        dotColor = AizomeIndigo,
                        title = stringResource(R.string.sentence_clauses),
                        subtitle = null,
                        isRegenerating = RegeneratableModule.CLAUSES in regeneratingModules,
                        isConflictBusy = RegeneratableModule.CLAUSES.conflictGroup in activeConflictGroups,
                        enabled = true,
                        hasError = hasClausesError,
                        onClick = {
                            onDismiss()
                            onRegenerateModule(RegeneratableModule.CLAUSES)
                        }
                    )

                    SheetRowDivider(sumiInk)

                    RegenerateModuleRow(
                        dotColor = MatchaGreen,
                        title = stringResource(R.string.grammar_points),
                        subtitle = null,
                        isRegenerating = RegeneratableModule.GRAMMAR in regeneratingModules,
                        isConflictBusy = RegeneratableModule.GRAMMAR.conflictGroup in activeConflictGroups,
                        enabled = true,
                        hasError = hasGrammarError,
                        onClick = {
                            onDismiss()
                            onRegenerateModule(RegeneratableModule.GRAMMAR)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRowDivider(sumiInk: Color) {
    HorizontalDivider(
        color = sumiInk.copy(alpha = 0.07f),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun RegenerateModuleRow(
    dotColor: Color,
    title: String,
    subtitle: String?,
    isRegenerating: Boolean,
    isConflictBusy: Boolean,
    enabled: Boolean,
    hasError: Boolean,
    onClick: () -> Unit
) {
    val sumiInk = MaterialTheme.colorScheme.onBackground
    val errorColor = MaterialTheme.colorScheme.error
    val isClickable = enabled && !isConflictBusy

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = isClickable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (isClickable || isRegenerating) 1f else 0.42f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = sumiInk
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = sumiInk.copy(alpha = 0.6f)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        when {
            isRegenerating -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = sumiInk.copy(alpha = 0.65f)
                    )
                    Text(
                        text = stringResource(R.string.regenerating_status),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = sumiInk.copy(alpha = 0.65f)
                    )
                }
            }
            hasError -> {
                Surface(
                    color = errorColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.regenerate_module_error_badge),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = errorColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            else -> {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = sumiInk.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
