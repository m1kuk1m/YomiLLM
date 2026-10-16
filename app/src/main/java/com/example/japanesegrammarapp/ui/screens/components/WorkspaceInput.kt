package com.example.japanesegrammarapp.ui.screens.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex
import com.example.japanesegrammarapp.R
import com.example.japanesegrammarapp.domain.model.AnalysisStatus
import com.example.japanesegrammarapp.domain.repository.UiPreferencesRepository
import com.example.japanesegrammarapp.ui.WorkspaceUiState
import com.example.japanesegrammarapp.ui.theme.ZenColors
import com.example.japanesegrammarapp.ui.theme.ZenThemeColors
import kotlin.math.roundToInt

enum class WorkspaceMediaAction {
    PHOTO_INPUT,
    CAMERA,
    ALBUM;

    companion object {
        val DEFAULT_ORDER = listOf(PHOTO_INPUT, CAMERA, ALBUM)

        fun parseOrder(raw: String?): List<WorkspaceMediaAction> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = raw.split(",")
                .mapNotNull { token ->
                    entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
                }
                .distinct()
            if (parsed.size == entries.size) return parsed
            val remaining = entries.filter { it !in parsed }
            return parsed + remaining
        }

        fun serializeOrder(order: List<WorkspaceMediaAction>): String {
            return order.joinToString(",") { it.name }
        }
    }
}

@Composable
fun WorkspaceInputForm(
    uiState: WorkspaceUiState,
    textInput: String,
    onTextInputChanged: (String) -> Unit,
    selectedImageUri: Uri?,
    onSelectedImageUriChanged: (Uri?) -> Unit,
    onModelSelected: (String) -> Unit,
    onStartAnalysis: (String, Uri?) -> Unit,
    onCancelAnalysis: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToPhotoInputCamera: () -> Unit = {},
    photoInputStatus: com.example.japanesegrammarapp.domain.usecase.PhotoInputUiStatus = com.example.japanesegrammarapp.domain.usecase.PhotoInputUiStatus(),
    onSkipPhotoInput: () -> Unit = {},
    onCancelPhotoInput: () -> Unit = {},
    onPickImage: (Uri) -> Unit,
    onNavigateToSettings: () -> Unit,
    uiPreferencesRepository: UiPreferencesRepository? = null
) {
    val SumiInk = MaterialTheme.colorScheme.onBackground
    val PrimaryColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    val activeProvider = uiState.activeProvider
    val activeModel = uiState.activeModel
    val providerModels = uiState.providerModels
    val modelsList = providerModels[activeProvider] ?: emptyList()
    var modelExpanded by remember { mutableStateOf(false) }

    val isAnalyzing = uiState.selectedRecord?.status == AnalysisStatus.PENDING
    val useOcr = uiState.useOcr
    val canSubmit = (textInput.isNotBlank() || selectedImageUri != null) && !isAnalyzing

    val latestTextInput by rememberUpdatedState(textInput)
    val latestSelectedImageUri by rememberUpdatedState(selectedImageUri)
    val latestCanSubmit by rememberUpdatedState(canSubmit)
    val latestOnStartAnalysis by rememberUpdatedState(onStartAnalysis)
    val latestOnSelectedImageUriChanged by rememberUpdatedState(onSelectedImageUriChanged)
    val latestOnTextInputChanged by rememberUpdatedState(onTextInputChanged)

    val submitIfPossible = remember {
        {
            if (latestCanSubmit) {
                focusManager.clearFocus()
                latestOnStartAnalysis(latestTextInput, latestSelectedImageUri)
                latestOnSelectedImageUriChanged(null)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            onPickImage(sourceUri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() } // Clear focus when clicking outside
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                ))
        ) {
            // Model Pill Selector
        Box {
            Surface(
                color = ZenThemeColors.pillBg(),
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clip(CircleShape)
                    .clickable { modelExpanded = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFC5E2C6))) // MatchaGreen dot
                    Text(
                        text = activeModel.ifBlank { activeProvider },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SumiInk.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.select),
                        tint = SumiInk.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                modelsList.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = SumiInk, fontSize = 13.sp) },
                        onClick = {
                            onModelSelected(option)
                            modelExpanded = false
                        }
                    )
                }
                if (modelsList.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_not_set), color = SumiInk.copy(alpha = 0.5f), fontSize = 13.sp) },
                        onClick = {
                            modelExpanded = false
                            onNavigateToSettings()
                        }
                    )
                }
            }
        }

        // Photo Input Status Pill
        AnimatedVisibility(
            visible = photoInputStatus.isRunning && (photoInputStatus.totalTasks > 0 || photoInputStatus.isTyping),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                color = ZenThemeColors.pillBg(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryColor
                        )
                        val statusText = if (photoInputStatus.isTyping) {
                            stringResource(R.string.photo_input_status_typing)
                        } else {
                            stringResource(
                                R.string.photo_input_status_recognizing,
                                photoInputStatus.currentTaskIndex,
                                photoInputStatus.totalTasks
                            )
                        }
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            color = SumiInk.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!photoInputStatus.isSkipped && photoInputStatus.isTyping) {
                            TextButton(
                                onClick = onSkipPhotoInput,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.photo_input_skip),
                                    fontSize = 12.sp,
                                    color = PrimaryColor
                                )
                            }
                        }
                        IconButton(
                            onClick = onCancelPhotoInput,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.photo_input_cancel),
                                tint = SumiInk.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Text Area (Borderless AppCompatEditText to support Gboard paste)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    androidx.appcompat.widget.AppCompatEditText(ctx).apply {
                        // Multi-line configuration
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or
                                     android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        isSingleLine = false
                        setHorizontallyScrolling(false)
                        background = null
                        setPadding(0, 0, 0, 0)
                        
                        // Text styles matching Compose design
                        textSize = 16f
                        setTextColor(SumiInk.toArgb())
                        setHintTextColor(SumiInk.copy(alpha = 0.2f).toArgb())
                        hint = context.getString(R.string.input_hint_elegant)
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        
                        setText(textInput)
                        setSelection(textInput.length)
                        
                        // Custom MatchaGreen cursor color matching the Zen design
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                                setSize(2.dp.value.toInt(), 0)
                                setColor(PrimaryColor.toArgb())
                            }
                        }
                        
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                val newText = s?.toString() ?: ""
                                if (newText != latestTextInput) {
                                    latestOnTextInputChanged(newText)
                                }
                            }
                            override fun afterTextChanged(s: android.text.Editable?) {}
                        })

                        setOnEditorActionListener { v, actionId, event ->
                            val isImeSubmit = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                            val isPlainEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                                !event.isShiftPressed

                            val hideKeyboardAndClearFocus = {
                                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                                v.clearFocus()
                            }

                            when {
                                isImeSubmit -> {
                                    hideKeyboardAndClearFocus()
                                    submitIfPossible()
                                    true
                                }
                                isPlainEnter -> {
                                    if (event?.action == android.view.KeyEvent.ACTION_UP) {
                                        hideKeyboardAndClearFocus()
                                        submitIfPossible()
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        
                        // Clipboard / Keyboard Content Receiver for Gboard images
                        androidx.core.view.ViewCompat.setOnReceiveContentListener(
                            this,
                            arrayOf("image/*"),
                            androidx.core.view.OnReceiveContentListener { _, contentInfo ->
                                val split = contentInfo.partition { item -> item.uri != null }
                                val parts = split.first
                                val remaining = split.second
                                if (parts != null) {
                                    val uri = parts.clip.getItemAt(0).uri
                                    if (uri != null) {
                                        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                                        imm?.hideSoftInputFromWindow(this@apply.windowToken, 0)
                                        this@apply.clearFocus()

                                        onPickImage(uri)
                                    }
                                }
                                remaining
                            }
                        )
                    }
                },
                update = { editText ->
                    if (editText.text.toString() != textInput) {
                        // Remember cursor position before overwriting text so the user's
                        // in-place edits don't cause the cursor to jump to the end.
                        val prevCursor = editText.selectionEnd.coerceAtMost(textInput.length)
                        editText.setText(textInput)
                        editText.setSelection(prevCursor)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
            )
        }

        // Selected Image Preview (if any, not using OCR)
        if (!useOcr && selectedImageUri != null) {
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .size(64.dp)
            ) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = stringResource(R.string.attached_image),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = { onSelectedImageUriChanged(null) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .offset(x = 6.dp, y = (-6).dp)
                        .background(SumiInk, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_remove_image),
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

        // Bottom Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Media Actions (Reorderable via Long-Press Drag)
            val density = LocalDensity.current
            val hapticFeedback = LocalHapticFeedback.current
            val buttonSizeDp = 48.dp
            val buttonSpacingDp = 12.dp
            val slotStridePx = with(density) { (buttonSizeDp + buttonSpacingDp).toPx() }

            var buttonOrder by remember(uiPreferencesRepository) {
                mutableStateOf(
                    WorkspaceMediaAction.parseOrder(
                        uiPreferencesRepository?.getWorkspaceMediaButtonOrder()
                    )
                )
            }
            var draggedAction by remember { mutableStateOf<WorkspaceMediaAction?>(null) }
            var dragStartSlotIndex by remember { mutableIntStateOf(0) }
            var totalDragX by remember { mutableFloatStateOf(0f) }

            Row(
                horizontalArrangement = Arrangement.spacedBy(buttonSpacingDp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(slotStridePx, uiPreferencesRepository) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            val startSlot = (startOffset.x / slotStridePx)
                                .toInt()
                                .coerceIn(0, buttonOrder.lastIndex)
                            dragStartSlotIndex = startSlot
                            draggedAction = buttonOrder[startSlot]
                            totalDragX = 0f
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = {
                            draggedAction = null
                            totalDragX = 0f
                        },
                        onDragCancel = {
                            draggedAction = null
                            totalDragX = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val active = draggedAction ?: return@detectDragGesturesAfterLongPress
                            totalDragX += dragAmount.x
                            val desiredLeftX = dragStartSlotIndex * slotStridePx + totalDragX
                            val targetIndex = (desiredLeftX / slotStridePx)
                                .roundToInt()
                                .coerceIn(0, buttonOrder.lastIndex)
                            val currentIndex = buttonOrder.indexOf(active)
                            if (currentIndex != -1 && targetIndex != currentIndex) {
                                val updated = buttonOrder.toMutableList().apply {
                                    removeAt(currentIndex)
                                    add(targetIndex, active)
                                }
                                buttonOrder = updated
                                uiPreferencesRepository?.saveWorkspaceMediaButtonOrder(
                                    WorkspaceMediaAction.serializeOrder(updated)
                                )
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    )
                }
            ) {
                val isDarkMode = ZenThemeColors.isDark()
                val spotColor = if (isDarkMode) Color.Black.copy(alpha = 0.45f) else ZenColors.SumiInk.copy(alpha = 0.22f)
                val ambientColor = if (isDarkMode) Color.Black.copy(alpha = 0.25f) else ZenColors.SumiInk.copy(alpha = 0.10f)

                buttonOrder.forEachIndexed { index, action ->
                    key(action) {
                        val isDragged = draggedAction == action
                        val scale by animateFloatAsState(
                            targetValue = if (isDragged) 1.08f else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "mediaButtonScale"
                        )
                        val animatedElevation by animateDpAsState(
                            targetValue = if (isDragged) 3.5.dp else 0.dp,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "mediaButtonElevation"
                        )
                        val animatedLiftY by animateFloatAsState(
                            targetValue = if (isDragged) with(density) { (-2).dp.toPx() } else 0f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "mediaButtonLiftY"
                        )
                        val isElevated = isDragged || scale > 1.005f

                        val draggedTranslationX = if (isDragged) {
                            val minLeftX = -slotStridePx * 0.25f
                            val maxLeftX = buttonOrder.lastIndex * slotStridePx + slotStridePx * 0.25f
                            val clampedLeftX = (dragStartSlotIndex * slotStridePx + totalDragX)
                                .coerceIn(minLeftX, maxLeftX)
                            clampedLeftX - index * slotStridePx
                        } else {
                            0f
                        }

                        val icon = when (action) {
                            WorkspaceMediaAction.PHOTO_INPUT -> Icons.Default.DocumentScanner
                            WorkspaceMediaAction.CAMERA -> Icons.Default.PhotoCamera
                            WorkspaceMediaAction.ALBUM -> Icons.Default.Image
                        }
                        val contentDescRes = when (action) {
                            WorkspaceMediaAction.PHOTO_INPUT -> R.string.photo_input_camera
                            WorkspaceMediaAction.CAMERA -> R.string.direct_image_analysis
                            WorkspaceMediaAction.ALBUM -> R.string.album
                        }
                        val iconAlpha = when (action) {
                            WorkspaceMediaAction.PHOTO_INPUT -> 0.6f
                            WorkspaceMediaAction.CAMERA -> 0.5f
                            WorkspaceMediaAction.ALBUM -> 0.5f
                        }
                        val onClickAction: () -> Unit = when (action) {
                            WorkspaceMediaAction.PHOTO_INPUT -> onNavigateToPhotoInputCamera
                            WorkspaceMediaAction.CAMERA -> onNavigateToCamera
                            WorkspaceMediaAction.ALBUM -> ({ galleryLauncher.launch("image/*") })
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .zIndex(if (isElevated) 1f else 0f)
                                .graphicsLayer {
                                    translationX = draggedTranslationX
                                    translationY = animatedLiftY
                                    scaleX = scale
                                    scaleY = scale
                                    shadowElevation = animatedElevation.toPx()
                                    shape = RoundedCornerShape(12.dp)
                                    clip = false
                                    spotShadowColor = spotColor
                                    ambientShadowColor = ambientColor
                                }
                                .size(buttonSizeDp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ZenThemeColors.buttonBg(), RoundedCornerShape(12.dp))
                                .drawBehind {
                                    drawRoundRect(
                                        color = if (isDragged) PrimaryColor.copy(alpha = 0.6f) else SumiInk.copy(alpha = 0.15f),
                                        style = Stroke(
                                            width = if (isDragged) 1.5.dp.toPx() else 1.dp.toPx(),
                                            pathEffect = if (isDragged) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        ),
                                        cornerRadius = CornerRadius(12.dp.toPx())
                                    )
                                }
                                .clickable(
                                    enabled = draggedAction == null,
                                    role = Role.Button,
                                    onClick = onClickAction
                                )
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(contentDescRes),
                                tint = SumiInk.copy(alpha = if (isDragged) 0.85f else iconAlpha),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Primary Action Button (Circular)
            IconButton(
                onClick = submitIfPossible,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (canSubmit) SumiInk else SumiInk.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = stringResource(R.string.start_parse),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
