package com.example.japanesegrammarapp.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.japanesegrammarapp.R
import com.example.japanesegrammarapp.domain.model.OcrBoxDetectionSettings
import com.example.japanesegrammarapp.ui.theme.ZenColors.KuriAmber
import com.example.japanesegrammarapp.ui.theme.ZenColors.SumiInk
import com.example.japanesegrammarapp.utils.AppLogger

enum class CropInteraction {
    AREA_CROP,
    TEXT_SELECT
}

@Composable
fun SegmentedControl(
    selected: CropInteraction,
    onSelected: (CropInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        val areaBg by androidx.compose.animation.animateColorAsState(if (selected == CropInteraction.AREA_CROP) Color.White.copy(alpha = 0.25f) else Color.Transparent)
        val textBg by androidx.compose.animation.animateColorAsState(if (selected == CropInteraction.TEXT_SELECT) Color.White.copy(alpha = 0.25f) else Color.Transparent)
        
        val areaTextColor by androidx.compose.animation.animateColorAsState(if (selected == CropInteraction.AREA_CROP) Color.White else Color.White.copy(alpha = 0.6f))
        val textTextColor by androidx.compose.animation.animateColorAsState(if (selected == CropInteraction.TEXT_SELECT) Color.White else Color.White.copy(alpha = 0.6f))

        Box(
            modifier = Modifier
                .background(areaBg, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { onSelected(CropInteraction.AREA_CROP) }
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.crop_interaction_area), color = areaTextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .background(textBg, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { onSelected(CropInteraction.TEXT_SELECT) }
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.crop_interaction_text_select), color = textTextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun ImageCropReviewLayout(
    originalBitmap: Bitmap,
    captureDeviceOrientation: DeviceOrientation,
    cameraMode: String = "direct_analysis",
    batchCount: Int = 0,
    ocrBoxDetectionSettings: OcrBoxDetectionSettings = OcrBoxDetectionSettings.DEFAULT,
    autoDeskewAfterCapture: Boolean = false,
    initialDeskewOutcome: DeskewOutcome = DeskewOutcome.Disabled,
    uiPreferencesRepository: com.example.japanesegrammarapp.domain.repository.UiPreferencesRepository,
    onCancel: () -> Unit,
    onConfirmNext: ((Bitmap) -> Unit)? = null,
    onConfirm: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val coroutineScope = rememberCoroutineScope()
    
    val baseBitmap = remember(originalBitmap) { originalBitmap }
    var bitmap by remember(originalBitmap) { mutableStateOf(originalBitmap) }
    var accumulatedRotation by remember(originalBitmap) { mutableStateOf(0f) }
    var deskewOutcome by remember(originalBitmap) { mutableStateOf(initialDeskewOutcome) }

    DisposableEffect(bitmap) {
        onDispose {
            if (bitmap !== baseBitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    val cropState = remember(bitmap) {
        CropState(
            bitmapWidth = bitmap.width.toFloat(),
            bitmapHeight = bitmap.height.toFloat()
        )
    }

    fun applyRotation(angleDelta: Float): Throwable? {
        if (angleDelta == 0f) {
            cropState.visualRotationAngle = 0f
            return null
        }
        val nextRotation = (accumulatedRotation + angleDelta) % 360f
        if (nextRotation == 0f) {
            accumulatedRotation = nextRotation
            bitmap = baseBitmap
            cropState.visualRotationAngle = 0f
            return null
        }
        try {
            val rotated = rotateBitmapPreservingContent(baseBitmap, nextRotation)
            accumulatedRotation = nextRotation
            bitmap = rotated
            cropState.visualRotationAngle = 0f
            return null
        } catch (e: Throwable) {
            AppLogger.e("CAMERA", "Failed to rotate bitmap", e)
            cropState.visualRotationAngle = 0f
            return e
        }
    }

    val deskewAnimatable = remember { androidx.compose.animation.core.Animatable(0f) }

    suspend fun performAnimatedDeskew(skewAngle: Float) {
        if (kotlin.math.abs(skewAngle) > 0.5f) {
            try {
                deskewAnimatable.snapTo(0f)
                deskewAnimatable.animateTo(
                    targetValue = -skewAngle,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 300,
                        easing = androidx.compose.animation.core.EaseInOutCubic
                    )
                ) {
                    cropState.visualRotationAngle = value
                }
                val rotationError = applyRotation(-skewAngle)
                deskewOutcome = if (rotationError == null) {
                    DeskewOutcome.Corrected(skewAngle)
                } else {
                    android.widget.Toast.makeText(context, R.string.camera_auto_deskew_failed, android.widget.Toast.LENGTH_SHORT).show()
                    DeskewOutcome.Failed(rotationError)
                }
                deskewAnimatable.snapTo(0f)
                cropState.visualRotationAngle = 0f
            } catch (e: Exception) {
                AppLogger.e("CAMERA", "Deskew animation failed", e)
                deskewOutcome = DeskewOutcome.Failed(e)
                cropState.visualRotationAngle = 0f
            }
        }
    }

    var interactionMode by remember { 
        val defaultMode = if (cameraMode == "text_input") {
            CropInteraction.TEXT_SELECT
        } else {
            val defaultStr = uiPreferencesRepository.getCropInteraction(CropInteraction.AREA_CROP.name)
            try { CropInteraction.valueOf(defaultStr) } catch(e: Exception) { CropInteraction.AREA_CROP }
        }
        mutableStateOf(defaultMode) 
    }
    var textSelectStart by remember { mutableStateOf<TextHandleState?>(null) }
    var textSelectEnd by remember { mutableStateOf<TextHandleState?>(null) }
    var magnifierPosition by remember { mutableStateOf<Offset?>(null) }
    var magnifierSource by remember { mutableStateOf<Offset?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var activeDraggingHandle by remember { mutableStateOf<String?>(null) }
    var workspaceSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(configuration.orientation, cropState, workspaceSize) {
        cropState.isInitialized = false
        if (workspaceSize.width > 0 && workspaceSize.height > 0) {
            cropState.initializeCropBox(workspaceSize.width.toFloat(), workspaceSize.height.toFloat())
        }
    }

    var detectedBoxes by remember(bitmap, ocrBoxDetectionSettings) { mutableStateOf<List<Rect>>(emptyList()) }
    var hideOcrBoxes by remember { mutableStateOf(false) }
    var selectedOcrBoxIndex by remember(bitmap, ocrBoxDetectionSettings) { mutableStateOf<Int?>(null) }
    var editingOcrBoxIndex by remember(bitmap, ocrBoxDetectionSettings) { mutableStateOf<Int?>(null) }
    var userSelectionMade by remember(bitmap, ocrBoxDetectionSettings) { mutableStateOf(false) }
    var isCropCleared by remember(bitmap, ocrBoxDetectionSettings) { mutableStateOf(false) }

    val unselectedBoxesAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (userSelectionMade || isCropCleared) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 300,
            easing = androidx.compose.animation.core.EaseInOutCubic
        ),
        label = "unselectedBoxesAlpha"
    )

    LaunchedEffect(bitmap, ocrBoxDetectionSettings, interactionMode) {
        try {
            if (interactionMode == CropInteraction.AREA_CROP) {
                val mergedBoxes = detectCameraOcrBoxes(
                    bitmap = bitmap,
                    settings = ocrBoxDetectionSettings,
                    context = context
                )
                detectedBoxes = mergedBoxes
                hideOcrBoxes = mergedBoxes.isEmpty()
                selectedOcrBoxIndex = mergedBoxes.indices.maxByOrNull { index ->
                    mergedBoxes[index].width().toLong() * mergedBoxes[index].height().toLong()
                }
                editingOcrBoxIndex = null
                userSelectionMade = false
                isCropCleared = false
            } else {
                val fineBoxes = detectFineGrainedCameraOcrBoxes(
                    bitmap = bitmap,
                    settings = ocrBoxDetectionSettings,
                    context = context
                )
                detectedBoxes = fineBoxes
                hideOcrBoxes = false
                textSelectStart = null
                textSelectEnd = null
                userSelectionMade = false
                isCropCleared = false
            }
        } catch (e: Exception) {
            AppLogger.e("CAMERA", "Failed to detect OCR text boxes", e)
            if (interactionMode == CropInteraction.AREA_CROP) {
                detectedBoxes = emptyList()
                hideOcrBoxes = true
                selectedOcrBoxIndex = null
                userSelectionMade = false
                isCropCleared = false
            }
        }
    }

    fun confirmBitmapRegion(x: Int, y: Int, w: Int, h: Int) {
        if (w > 0 && h > 0) {
            try {
                onConfirm(Bitmap.createBitmap(bitmap, x, y, w, h))
            } catch (e: Throwable) {
                AppLogger.e("CAMERA", "Failed to crop selected bitmap region", e)
                onConfirm(bitmap)
            }
        } else {
            onConfirm(bitmap)
        }
    }

    fun confirmOcrBox(box: Rect) {
        val x = box.left.coerceIn(0, bitmap.width)
        val y = box.top.coerceIn(0, bitmap.height)
        val w = minOf(bitmap.width - x, box.width())
        val h = minOf(bitmap.height - y, box.height())
        confirmBitmapRegion(x, y, w, h)
    }

    fun confirmCurrentCrop() {
        val bmpW = bitmap.width
        val bmpH = bitmap.height

        val x = ((cropState.cropLeft - cropState.imgOffsetX) / cropState.scaleFactor).toInt().coerceIn(0, bmpW)
        val y = ((cropState.cropTop - cropState.imgOffsetY) / cropState.scaleFactor).toInt().coerceIn(0, bmpH)

        var w = ((cropState.cropRight - cropState.cropLeft) / cropState.scaleFactor).toInt()
        var h = ((cropState.cropBottom - cropState.cropTop) / cropState.scaleFactor).toInt()

        if (x + w > bmpW) w = bmpW - x
        if (y + h > bmpH) h = bmpH - y

        confirmBitmapRegion(x, y, w, h)
    }

    fun setCropStateFromOcrBox(index: Int) {
        val box = detectedBoxes.getOrNull(index) ?: return
        cropState.cropLeft = cropState.imgOffsetX + box.left * cropState.scaleFactor
        cropState.cropTop = cropState.imgOffsetY + box.top * cropState.scaleFactor
        cropState.cropRight = cropState.imgOffsetX + box.right * cropState.scaleFactor
        cropState.cropBottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor
        selectedOcrBoxIndex = index
        editingOcrBoxIndex = index
        userSelectionMade = true
        isCropCleared = false
    }

    fun commitEditedOcrBox(index: Int) {
        if (index !in detectedBoxes.indices) return
        val left = ((cropState.cropLeft - cropState.imgOffsetX) / cropState.scaleFactor)
            .toInt().coerceIn(0, bitmap.width)
        val top = ((cropState.cropTop - cropState.imgOffsetY) / cropState.scaleFactor)
            .toInt().coerceIn(0, bitmap.height)
        val right = ((cropState.cropRight - cropState.imgOffsetX) / cropState.scaleFactor)
            .toInt().coerceIn(left, bitmap.width)
        val bottom = ((cropState.cropBottom - cropState.imgOffsetY) / cropState.scaleFactor)
            .toInt().coerceIn(top, bitmap.height)
        detectedBoxes = detectedBoxes.toMutableList().also {
            it[index] = Rect(left, top, right, bottom)
        }
        selectedOcrBoxIndex = index
        editingOcrBoxIndex = null
    }
    
    @Composable
    fun WorkspaceArea(modifier: Modifier) {
        Box(
            modifier = modifier
                .onGloballyPositioned { layoutCoordinates ->
                    workspaceSize = layoutCoordinates.size
                    cropState.initializeCropBox(
                        layoutCoordinates.size.width.toFloat(),
                        layoutCoordinates.size.height.toFloat()
                    )
                }
        ) {
            if (cropState.isInitialized) {
                // Renders the original full-size image scaled to fit
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    withTransform({
                        translate(
                            cropState.imgOffsetX + cropState.imgDispWidth / 2f,
                            cropState.imgOffsetY + cropState.imgDispHeight / 2f
                        )
                        rotate(cropState.visualRotationAngle, pivot = Offset.Zero)
                        translate(-cropState.imgDispWidth / 2f, -cropState.imgDispHeight / 2f)
                    }) {
                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(cropState.imgDispWidth.toInt(), cropState.imgDispHeight.toInt())
                        )
                    }
                }
                
                val minSizePx = with(density) { 16.dp.toPx() }
                val gridAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isDragging) 0.35f else 0f,
                    label = "gridAlpha"
                )
                
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(cropState, interactionMode) {
                            awaitPointerEventScope {
                                while (true) {
                                    val downEvent = awaitFirstDown(requireUnconsumed = false)
                                    val startPos = downEvent.position
                                    var activePointerId = downEvent.id
                                    
                                    if (cropState.visualRotationAngle != 0f) {
                                        // Ignore touches while visually rotating
                                        continue
                                    }

                                    val minTolerancePx = 32.dp.toPx() // Corner grab tolerance
                                    val dragSlopPx = 8.dp.toPx() // Drag vs tap threshold
                                    var targetBoxIndex: Int? = null
                                    var targetHandle = DragHandle.NONE
                                    var tapAction: (() -> Unit)? = null
                                    var isFreeformDrawing = false
                                    var activeTextHandle: String? = null
                                    var lockedBoxIndex = 0
                                    var isPendingDisambiguation = false
                                    var handleGrabOffsetX = 0f
                                    var handleGrabOffsetY = 0f
                                    cropState.activeHandle = DragHandle.NONE
                                    
                                    if (interactionMode == CropInteraction.TEXT_SELECT) {
                                        if (detectedBoxes.isNotEmpty()) {
                                            val R = 10.dp.toPx()
                                            val D = R * 1.414f
                                            val getHandlePos = { handle: TextHandleState, isStart: Boolean -> 
                                                val box = detectedBoxes.getOrNull(handle.index)
                                                if (box != null) {
                                                    val isVertical = box.height() > box.width()
                                                    if (isVertical) {
                                                        val y = cropState.imgOffsetY + (box.top + box.height() * handle.offsetRatio) * cropState.scaleFactor
                                                        if (isStart) {
                                                            val x = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                                            Offset(x + D, y)
                                                        } else {
                                                            val x = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                                            Offset(x - D, y)
                                                        }
                                                    } else {
                                                        val x = cropState.imgOffsetX + (box.left + box.width() * handle.offsetRatio) * cropState.scaleFactor
                                                        val y = cropState.imgOffsetY + box.bottom * cropState.scaleFactor
                                                        if (isStart) {
                                                            Offset(x - R, y + R)
                                                        } else {
                                                            Offset(x + R, y + R)
                                                        }
                                                    }
                                                } else {
                                                    Offset.Zero
                                                }
                                            }

                                            val touchTolerance = 48.dp.toPx()
                                            var handleGrabbed = false

                                            if (textSelectStart != null && textSelectEnd != null) {
                                                val (orderedStart, orderedEnd) = getOrderedHandles(textSelectStart!!, textSelectEnd!!)
                                                val startCenter = getHandlePos(orderedStart, true)
                                                val endCenter = getHandlePos(orderedEnd, false)
                                                val distStart = distance(startPos.x, startPos.y, startCenter.x, startCenter.y)
                                                val distEnd = distance(startPos.x, startPos.y, endCenter.x, endCenter.y)
                                                val handlesSeparation = distance(startCenter.x, startCenter.y, endCenter.x, endCenter.y)

                                                if (handlesSeparation < 24.dp.toPx() && (distStart < touchTolerance || distEnd < touchTolerance)) {
                                                    isPendingDisambiguation = true
                                                    handleGrabbed = true
                                                    activeTextHandle = if (distStart < distEnd) "START" else "END"
                                                    lockedBoxIndex = (if (activeTextHandle == "START") orderedStart else orderedEnd).index
                                                } else if (distStart < distEnd && distStart < touchTolerance) {
                                                    activeTextHandle = "START"
                                                    handleGrabbed = true
                                                    lockedBoxIndex = orderedStart.index
                                                } else if (distEnd < touchTolerance) {
                                                    activeTextHandle = "END"
                                                    handleGrabbed = true
                                                    lockedBoxIndex = orderedEnd.index
                                                }

                                                if (handleGrabbed) {
                                                    val grabbedHandle = if (activeTextHandle == "START") orderedStart else orderedEnd
                                                    val box = detectedBoxes.getOrNull(lockedBoxIndex)
                                                    if (box != null) {
                                                        val isVert = box.height() > box.width()
                                                        val anchorX = if (isVert) {
                                                            cropState.imgOffsetX + (box.left + box.right) / 2f * cropState.scaleFactor
                                                        } else {
                                                            cropState.imgOffsetX + (box.left + box.width() * grabbedHandle.offsetRatio) * cropState.scaleFactor
                                                        }
                                                        val anchorY = if (isVert) {
                                                            cropState.imgOffsetY + (box.top + box.height() * grabbedHandle.offsetRatio) * cropState.scaleFactor
                                                        } else {
                                                            cropState.imgOffsetY + (box.top + box.bottom) / 2f * cropState.scaleFactor
                                                        }
                                                        handleGrabOffsetX = startPos.x - anchorX
                                                        handleGrabOffsetY = startPos.y - anchorY
                                                    }
                                                }
                                            }

                                            if (!handleGrabbed) {
                                                val hitTolerance = 24.dp.toPx()
                                                val closestIdx = detectedBoxes.indices.minByOrNull { i ->
                                                    val box = detectedBoxes[i]
                                                    val displayLeft = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                                    val displayTop = cropState.imgOffsetY + box.top * cropState.scaleFactor
                                                    val displayRight = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                                    val displayBottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor
                                                    
                                                    val dx = maxOf(0f, maxOf(displayLeft - startPos.x, startPos.x - displayRight))
                                                    val dy = maxOf(0f, maxOf(displayTop - startPos.y, startPos.y - displayBottom))
                                                    dx * dx + dy * dy
                                                }
                                                val closestDistSq = closestIdx?.let { i ->
                                                    val box = detectedBoxes[i]
                                                    val displayLeft = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                                    val displayTop = cropState.imgOffsetY + box.top * cropState.scaleFactor
                                                    val displayRight = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                                    val displayBottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor
                                                    
                                                    val dx = maxOf(0f, maxOf(displayLeft - startPos.x, startPos.x - displayRight))
                                                    val dy = maxOf(0f, maxOf(displayTop - startPos.y, startPos.y - displayBottom))
                                                    dx * dx + dy * dy
                                                } ?: Float.MAX_VALUE

                                                if (closestIdx != null && closestDistSq <= hitTolerance * hitTolerance) {
                                                    val initialBox = detectedBoxes[closestIdx]
                                                    val isVertical = initialBox.height() > initialBox.width()
                                                    val initialOffsetRatio = if (isVertical) {
                                                        val displayTop = cropState.imgOffsetY + initialBox.top * cropState.scaleFactor
                                                        val displayHeight = initialBox.height() * cropState.scaleFactor
                                                        if (displayHeight > 0) ((startPos.y - displayTop) / displayHeight).coerceIn(0f, 1f) else 0f
                                                    } else {
                                                        val displayLeft = cropState.imgOffsetX + initialBox.left * cropState.scaleFactor
                                                        val displayWidth = initialBox.width() * cropState.scaleFactor
                                                        if (displayWidth > 0) ((startPos.x - displayLeft) / displayWidth).coerceIn(0f, 1f) else 0f
                                                    }
                                                    textSelectStart = TextHandleState(closestIdx, initialOffsetRatio)
                                                    textSelectEnd = TextHandleState(closestIdx, initialOffsetRatio)
                                                    activeTextHandle = "END"
                                                    lockedBoxIndex = closestIdx
                                                    tapAction = {
                                                        textSelectStart = TextHandleState(closestIdx, 0f)
                                                        textSelectEnd = TextHandleState(closestIdx, 1f)
                                                        activeTextHandle = null
                                                        activeDraggingHandle = null
                                                    }
                                                } else {
                                                    tapAction = {
                                                        textSelectStart = null
                                                        textSelectEnd = null
                                                        activeTextHandle = null
                                                        activeDraggingHandle = null
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        var minDist = Float.MAX_VALUE
                                        val edgeTolerance = 24.dp.toPx()

                                        if (isCropCleared) {
                                            targetBoxIndex = null
                                            targetHandle = DragHandle.NONE
                                            isFreeformDrawing = true
                                            tapAction = {
                                                isCropCleared = true
                                                userSelectionMade = false
                                                selectedOcrBoxIndex = null
                                                editingOcrBoxIndex = null
                                            }
                                        } else if (userSelectionMade || hideOcrBoxes) {
                                            val cropL = cropState.cropLeft
                                            val cropT = cropState.cropTop
                                            val cropR = cropState.cropRight
                                            val cropB = cropState.cropBottom

                                            // 1. Corners of active box
                                            listOf(
                                                DragHandle.TOP_LEFT to Offset(cropL, cropT),
                                                DragHandle.TOP_RIGHT to Offset(cropR, cropT),
                                                DragHandle.BOTTOM_LEFT to Offset(cropL, cropB),
                                                DragHandle.BOTTOM_RIGHT to Offset(cropR, cropB)
                                            ).forEach { (handle, point) ->
                                                val dist = distance(startPos.x, startPos.y, point.x, point.y)
                                                if (dist < minDist && dist < minTolerancePx) {
                                                    minDist = dist
                                                    targetBoxIndex = selectedOcrBoxIndex
                                                    targetHandle = handle
                                                }
                                            }

                                            // 2. Edges of active box
                                            if (targetHandle == DragHandle.NONE) {
                                                fun considerEdge(handle: DragHandle, dist: Float, inRange: Boolean) {
                                                    if (inRange && dist < minDist && dist < edgeTolerance) {
                                                        minDist = dist
                                                        targetBoxIndex = selectedOcrBoxIndex
                                                        targetHandle = handle
                                                    }
                                                }
                                                considerEdge(DragHandle.TOP, kotlin.math.abs(startPos.y - cropT), startPos.x in (cropL - edgeTolerance)..(cropR + edgeTolerance))
                                                considerEdge(DragHandle.BOTTOM, kotlin.math.abs(startPos.y - cropB), startPos.x in (cropL - edgeTolerance)..(cropR + edgeTolerance))
                                                considerEdge(DragHandle.LEFT, kotlin.math.abs(startPos.x - cropL), startPos.y in (cropT - edgeTolerance)..(cropB + edgeTolerance))
                                                considerEdge(DragHandle.RIGHT, kotlin.math.abs(startPos.x - cropR), startPos.y in (cropT - edgeTolerance)..(cropB + edgeTolerance))
                                            }

                                            // 3. Inside active box
                                            val isInsideActiveCrop = startPos.x in cropL..cropR && startPos.y in cropT..cropB
                                            if (targetHandle == DragHandle.NONE && isInsideActiveCrop) {
                                                targetBoxIndex = selectedOcrBoxIndex
                                                targetHandle = DragHandle.CENTER
                                                tapAction = { confirmCurrentCrop() }
                                            }

                                            // 4. Outside active box
                                            if (targetHandle == DragHandle.NONE && !isInsideActiveCrop) {
                                                tapAction = {
                                                    isCropCleared = true
                                                    userSelectionMade = false
                                                    selectedOcrBoxIndex = null
                                                    editingOcrBoxIndex = null
                                                }
                                                isFreeformDrawing = true
                                            }
                                        } else {
                                            // Browse mode: userSelectionMade == false && !hideOcrBoxes
                                            // 1. Corners across all candidate boxes
                                            detectedBoxes.forEachIndexed { index, box ->
                                                val displayLeft = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                                val displayTop = cropState.imgOffsetY + box.top * cropState.scaleFactor
                                                val displayRight = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                                val displayBottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor

                                                listOf(
                                                    DragHandle.TOP_LEFT to Offset(displayLeft, displayTop),
                                                    DragHandle.TOP_RIGHT to Offset(displayRight, displayTop),
                                                    DragHandle.BOTTOM_LEFT to Offset(displayLeft, displayBottom),
                                                    DragHandle.BOTTOM_RIGHT to Offset(displayRight, displayBottom)
                                                ).forEach { (handle, point) ->
                                                    val dist = distance(startPos.x, startPos.y, point.x, point.y)
                                                    if (dist < minDist && dist < minTolerancePx) {
                                                        minDist = dist
                                                        targetBoxIndex = index
                                                        targetHandle = handle
                                                    }
                                                }
                                            }

                                            // 2. Edges across all candidate boxes
                                            if (targetBoxIndex == null) {
                                                minDist = Float.MAX_VALUE
                                                detectedBoxes.forEachIndexed { index, box ->
                                                    val left = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                                    val top = cropState.imgOffsetY + box.top * cropState.scaleFactor
                                                    val right = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                                    val bottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor

                                                    fun consider(handle: DragHandle, distance: Float, inRange: Boolean) {
                                                        if (inRange && distance < minDist && distance < edgeTolerance) {
                                                            minDist = distance
                                                            targetBoxIndex = index
                                                            targetHandle = handle
                                                        }
                                                    }
                                                    consider(DragHandle.TOP, kotlin.math.abs(startPos.y - top), startPos.x in (left - edgeTolerance)..(right + edgeTolerance))
                                                    consider(DragHandle.BOTTOM, kotlin.math.abs(startPos.y - bottom), startPos.x in (left - edgeTolerance)..(right + edgeTolerance))
                                                    consider(DragHandle.LEFT, kotlin.math.abs(startPos.x - left), startPos.y in (top - edgeTolerance)..(bottom + edgeTolerance))
                                                    consider(DragHandle.RIGHT, kotlin.math.abs(startPos.x - right), startPos.y in (top - edgeTolerance)..(bottom + edgeTolerance))
                                                }
                                            }

                                            if (targetBoxIndex != null) {
                                                val hitIndex = targetBoxIndex!!
                                                tapAction = { setCropStateFromOcrBox(hitIndex) }
                                            } else {
                                                val interiorIndex = detectedBoxes.indices
                                                    .filter { index ->
                                                        val box = detectedBoxes[index]
                                                        val left = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                                        val top = cropState.imgOffsetY + box.top * cropState.scaleFactor
                                                        val right = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                                        val bottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor
                                                        startPos.x in left..right && startPos.y in top..bottom
                                                    }
                                                    .minByOrNull { index -> detectedBoxes[index].width() * detectedBoxes[index].height() }

                                                if (interiorIndex != null) {
                                                    targetBoxIndex = interiorIndex
                                                    tapAction = { setCropStateFromOcrBox(interiorIndex) }
                                                    targetHandle = DragHandle.CENTER
                                                } else {
                                                    isFreeformDrawing = true
                                                    tapAction = {
                                                        isCropCleared = true
                                                        userSelectionMade = false
                                                        selectedOcrBoxIndex = null
                                                        editingOcrBoxIndex = null
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    var isEditing = false
                                    var maxDragDistance = 0f
                                    val canEdit = targetHandle != DragHandle.NONE || activeTextHandle != null || isFreeformDrawing

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val changes = event.changes

                                        if (changes.all { !it.pressed }) {
                                            magnifierPosition = null
                                            magnifierSource = null
                                            isDragging = false
                                            activeDraggingHandle = null
                                            if (isEditing) {
                                                if (interactionMode == CropInteraction.AREA_CROP) {
                                                    cropState.stopDrag()
                                                    val boxIdx = targetBoxIndex
                                                    if (boxIdx != null && boxIdx in detectedBoxes.indices) {
                                                        commitEditedOcrBox(boxIdx)
                                                    } else if (isFreeformDrawing) {
                                                        val w = cropState.cropRight - cropState.cropLeft
                                                        val h = cropState.cropBottom - cropState.cropTop
                                                        if (w < minSizePx || h < minSizePx) {
                                                            isCropCleared = true
                                                            userSelectionMade = false
                                                            selectedOcrBoxIndex = null
                                                        } else {
                                                            isCropCleared = false
                                                            userSelectionMade = true
                                                            selectedOcrBoxIndex = null
                                                        }
                                                    }
                                                }
                                            } else if (maxDragDistance <= dragSlopPx) {
                                                downEvent.consume()
                                                tapAction?.invoke()
                                            }
                                            break
                                        }

                                        val activeChanges = changes.filter { it.pressed }
                                        if (activeChanges.size >= 2) {
                                            if (isEditing) {
                                                activeChanges.forEach { it.consume() }
                                            }
                                            continue
                                        }

                                        val currentFinger = activeChanges.find { it.id == activePointerId } ?: activeChanges.firstOrNull()
                                        if (currentFinger != null) {
                                            activePointerId = currentFinger.id
                                            val pointerDelta = currentFinger.position - startPos
                                            maxDragDistance = maxOf(
                                                maxDragDistance,
                                                kotlin.math.sqrt(pointerDelta.x * pointerDelta.x + pointerDelta.y * pointerDelta.y)
                                            )

                                            if (interactionMode == CropInteraction.TEXT_SELECT) {
                                                if (activeTextHandle != null) {
                                                    val currentPos = currentFinger.position

                                                    // Disambiguate if handles were overlapping
                                                    if (isPendingDisambiguation) {
                                                        val box = detectedBoxes.getOrNull(lockedBoxIndex)
                                                        val isVert = box != null && box.height() > box.width()
                                                        val primaryDelta = if (isVert) pointerDelta.y else pointerDelta.x
                                                        if (kotlin.math.abs(primaryDelta) > dragSlopPx / 2f) {
                                                            activeTextHandle = if (primaryDelta > 0) "END" else "START"
                                                            isPendingDisambiguation = false
                                                        }
                                                    }

                                                    isEditing = true
                                                    isDragging = true
                                                    magnifierPosition = currentPos
                                                    activeDraggingHandle = activeTextHandle
                                                    
                                                    val otherHandle = if (activeTextHandle == "START") textSelectEnd else textSelectStart
                                                    val targetOrientationIsVertical = otherHandle?.let {
                                                        val b = detectedBoxes.getOrNull(it.index)
                                                        b != null && b.height() > b.width()
                                                    } ?: detectedBoxes.getOrNull(lockedBoxIndex)?.let { it.height() > it.width() }

                                                    val allowedIndices = if (targetOrientationIsVertical != null) {
                                                        detectedBoxes.indices.filter { i ->
                                                            val b = detectedBoxes[i]
                                                            (b.height() > b.width()) == targetOrientationIsVertical
                                                        }
                                                    } else detectedBoxes.indices

                                                    val curBox = detectedBoxes.getOrNull(lockedBoxIndex) ?: detectedBoxes[allowedIndices.first()]
                                                    val isVertical = curBox.height() > curBox.width()

                                                    val effectiveTouchPos = Offset(currentPos.x - handleGrabOffsetX, currentPos.y - handleGrabOffsetY)

                                                    val targetIdx = if (isVertical) {
                                                        val colWidth = (curBox.right - curBox.left) * cropState.scaleFactor
                                                        val deadbandX = minOf(6.dp.toPx(), maxOf(4.dp.toPx(), colWidth * 0.25f))

                                                        fun columnCost(idx: Int): Float {
                                                            val b = detectedBoxes[idx]
                                                            val bL = cropState.imgOffsetX + b.left * cropState.scaleFactor
                                                            val bR = cropState.imgOffsetX + b.right * cropState.scaleFactor
                                                            val bT = cropState.imgOffsetY + b.top * cropState.scaleFactor
                                                            val bB = cropState.imgOffsetY + b.bottom * cropState.scaleFactor
                                                            val bCenterX = (bL + bR) / 2f

                                                            val distX = kotlin.math.abs(effectiveTouchPos.x - bCenterX)
                                                            val distY = if (effectiveTouchPos.y < bT) {
                                                                bT - effectiveTouchPos.y
                                                            } else if (effectiveTouchPos.y > bB) {
                                                                effectiveTouchPos.y - bB
                                                            } else {
                                                                0f
                                                            }
                                                            return distX + distY * 0.4f
                                                        }

                                                        val curCost = columnCost(lockedBoxIndex)
                                                        val bestCandidate = allowedIndices.minByOrNull { columnCost(it) } ?: lockedBoxIndex
                                                        val bestCost = columnCost(bestCandidate)

                                                        if (bestCandidate != lockedBoxIndex && bestCost + deadbandX < curCost) {
                                                            bestCandidate
                                                        } else {
                                                            lockedBoxIndex
                                                        }
                                                    } else {
                                                        val rowHeight = (curBox.bottom - curBox.top) * cropState.scaleFactor
                                                        val deadbandY = minOf(6.dp.toPx(), maxOf(4.dp.toPx(), rowHeight * 0.25f))

                                                        fun rowCost(idx: Int): Float {
                                                            val b = detectedBoxes[idx]
                                                            val bL = cropState.imgOffsetX + b.left * cropState.scaleFactor
                                                            val bR = cropState.imgOffsetX + b.right * cropState.scaleFactor
                                                            val bT = cropState.imgOffsetY + b.top * cropState.scaleFactor
                                                            val bB = cropState.imgOffsetY + b.bottom * cropState.scaleFactor
                                                            val bCenterY = (bT + bB) / 2f

                                                            val distY = kotlin.math.abs(effectiveTouchPos.y - bCenterY)
                                                            val distX = if (effectiveTouchPos.x < bL) {
                                                                bL - effectiveTouchPos.x
                                                            } else if (effectiveTouchPos.x > bR) {
                                                                effectiveTouchPos.x - bR
                                                            } else {
                                                                0f
                                                            }
                                                            return distY + distX * 0.4f
                                                        }

                                                        val curCost = rowCost(lockedBoxIndex)
                                                        val bestCandidate = allowedIndices.minByOrNull { rowCost(it) } ?: lockedBoxIndex
                                                        val bestCost = rowCost(bestCandidate)

                                                        if (bestCandidate != lockedBoxIndex && bestCost + deadbandY < curCost) {
                                                            bestCandidate
                                                        } else {
                                                            lockedBoxIndex
                                                        }
                                                    }
                                                    lockedBoxIndex = targetIdx

                                                    val activeBox = detectedBoxes[lockedBoxIndex]
                                                    val activeLeft = cropState.imgOffsetX + activeBox.left * cropState.scaleFactor
                                                    val activeTop = cropState.imgOffsetY + activeBox.top * cropState.scaleFactor
                                                    val activeWidth = activeBox.width() * cropState.scaleFactor
                                                    val activeHeight = activeBox.height() * cropState.scaleFactor

                                                    val rawOffsetRatio = if (isVertical) {
                                                        if (activeHeight > 0) ((effectiveTouchPos.y - activeTop) / activeHeight) else 0f
                                                    } else {
                                                        if (activeWidth > 0) ((effectiveTouchPos.x - activeLeft) / activeWidth) else 0f
                                                    }
                                                    val offsetRatio = snapOffsetRatio(rawOffsetRatio, 0.06f)
                                                    val candidateState = TextHandleState(lockedBoxIndex, offsetRatio)

                                                    // Role Swapping (Handle Crossing)
                                                    if (activeTextHandle == "START") {
                                                        val fixedEnd = textSelectEnd ?: candidateState
                                                        if (isHandleAfter(candidateState, fixedEnd)) {
                                                            textSelectStart = fixedEnd
                                                            textSelectEnd = candidateState
                                                            activeTextHandle = "END"
                                                        } else {
                                                            textSelectStart = candidateState
                                                        }
                                                    } else {
                                                        val fixedStart = textSelectStart ?: candidateState
                                                        if (isHandleAfter(fixedStart, candidateState)) {
                                                            textSelectEnd = fixedStart
                                                            textSelectStart = candidateState
                                                            activeTextHandle = "START"
                                                        } else {
                                                            textSelectEnd = candidateState
                                                        }
                                                    }
                                                    activeDraggingHandle = activeTextHandle

                                                    // Magnifier source tracks text cursor
                                                    val finalHandle = if (activeTextHandle == "START") textSelectStart!! else textSelectEnd!!
                                                    val finalBox = detectedBoxes[finalHandle.index]
                                                    if (isVertical) {
                                                        val hY = cropState.imgOffsetY + (finalBox.top + finalBox.height() * finalHandle.offsetRatio) * cropState.scaleFactor
                                                        val hX = cropState.imgOffsetX + (finalBox.left + finalBox.right) / 2f * cropState.scaleFactor
                                                        magnifierSource = Offset(hX, hY)
                                                    } else {
                                                        val hX = cropState.imgOffsetX + (finalBox.left + finalBox.width() * finalHandle.offsetRatio) * cropState.scaleFactor
                                                        val hY = cropState.imgOffsetY + (finalBox.top + finalBox.bottom) / 2f * cropState.scaleFactor
                                                        magnifierSource = Offset(hX, hY)
                                                    }
                                                    
                                                    currentFinger.consume()
                                                }
                                            } else if (!isEditing && canEdit) {
                                                val currentPos = currentFinger.position
                                                val totalDelta = currentPos - startPos
                                                val dist = kotlin.math.sqrt(totalDelta.x * totalDelta.x + totalDelta.y * totalDelta.y)
                                                maxDragDistance = maxOf(maxDragDistance, dist)
                                                val shouldStartDrag = dist > dragSlopPx

                                                if (shouldStartDrag) {
                                                    if (isFreeformDrawing) {
                                                        val imgLeft = cropState.imgOffsetX
                                                        val imgTop = cropState.imgOffsetY
                                                        val imgRight = imgLeft + cropState.imgDispWidth
                                                        val imgBottom = imgTop + cropState.imgDispHeight

                                                        cropState.cropLeft = minOf(startPos.x, currentPos.x).coerceIn(imgLeft, imgRight)
                                                        cropState.cropTop = minOf(startPos.y, currentPos.y).coerceIn(imgTop, imgBottom)
                                                        cropState.cropRight = maxOf(startPos.x, currentPos.x).coerceIn(imgLeft, imgRight)
                                                        cropState.cropBottom = maxOf(startPos.y, currentPos.y).coerceIn(imgTop, imgBottom)
                                                        userSelectionMade = true
                                                        isCropCleared = false
                                                        selectedOcrBoxIndex = null
                                                        editingOcrBoxIndex = null
                                                        targetBoxIndex = null
                                                        cropState.activeHandle = DragHandle.BOTTOM_RIGHT
                                                        targetHandle = DragHandle.BOTTOM_RIGHT
                                                        isEditing = true
                                                        isDragging = true
                                                        downEvent.consume()
                                                        currentFinger.consume()
                                                    } else {
                                                        targetBoxIndex?.let { setCropStateFromOcrBox(it) }
                                                        cropState.activeHandle = targetHandle
                                                        downEvent.consume()
                                                        currentFinger.consume()
                                                        cropState.onDrag(currentFinger.position - currentFinger.previousPosition, minSizePx)
                                                        isEditing = true
                                                        isDragging = true
                                                    }
                                                }
                                            } else if (isEditing) {
                                                val totalDelta = currentFinger.position - startPos
                                                maxDragDistance = maxOf(
                                                    maxDragDistance,
                                                    kotlin.math.sqrt(totalDelta.x * totalDelta.x + totalDelta.y * totalDelta.y)
                                                )
                                                if (isFreeformDrawing) {
                                                    val imgLeft = cropState.imgOffsetX
                                                    val imgTop = cropState.imgOffsetY
                                                    val imgRight = imgLeft + cropState.imgDispWidth
                                                    val imgBottom = imgTop + cropState.imgDispHeight

                                                    cropState.cropLeft = minOf(startPos.x, currentFinger.position.x).coerceIn(imgLeft, imgRight)
                                                    cropState.cropTop = minOf(startPos.y, currentFinger.position.y).coerceIn(imgTop, imgBottom)
                                                    cropState.cropRight = maxOf(startPos.x, currentFinger.position.x).coerceIn(imgLeft, imgRight)
                                                    cropState.cropBottom = maxOf(startPos.y, currentFinger.position.y).coerceIn(imgTop, imgBottom)
                                                    currentFinger.consume()
                                                } else {
                                                    currentFinger.consume()
                                                    cropState.onDrag(currentFinger.position - currentFinger.previousPosition, minSizePx)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    if (cropState.visualRotationAngle != 0f) {
                        // Draw nothing, just let user see the rotating image
                        return@Canvas
                    }

                    if (interactionMode == CropInteraction.TEXT_SELECT) {
                        if (detectedBoxes.isNotEmpty()) {
                            val imageLeft = cropState.imgOffsetX
                            val imageTop = cropState.imgOffsetY
                            val imageRight = cropState.imgOffsetX + cropState.imgDispWidth
                            val imageBottom = cropState.imgOffsetY + cropState.imgDispHeight

                            // Draw shadow with holes for all selectable text
                            val highlightedRects = detectedBoxes.mapNotNull { box ->
                                val displayLeft = (cropState.imgOffsetX + box.left * cropState.scaleFactor).coerceIn(imageLeft, imageRight)
                                val displayTop = (cropState.imgOffsetY + box.top * cropState.scaleFactor).coerceIn(imageTop, imageBottom)
                                val displayRight = (cropState.imgOffsetX + box.right * cropState.scaleFactor).coerceIn(imageLeft, imageRight)
                                val displayBottom = (cropState.imgOffsetY + box.bottom * cropState.scaleFactor).coerceIn(imageTop, imageBottom)

                                if (displayRight > displayLeft && displayBottom > displayTop) {
                                    ComposeRect(displayLeft, displayTop, displayRight, displayBottom)
                                } else {
                                    null
                                }
                            }

                            drawContext.canvas.saveLayer(ComposeRect(imageLeft, imageTop, imageRight, imageBottom), Paint())
                            drawRect(
                                color = Color.Black.copy(alpha = 0.6f),
                                topLeft = Offset(imageLeft, imageTop),
                                size = Size(imageRight - imageLeft, imageBottom - imageTop)
                            )
                            highlightedRects.forEach { rect ->
                                drawRect(
                                    color = Color.Transparent,
                                    topLeft = Offset(rect.left, rect.top),
                                    size = Size(rect.width, rect.height),
                                    blendMode = BlendMode.Clear
                                )
                            }
                            drawContext.canvas.restore()

                            if (textSelectStart != null && textSelectEnd != null) {
                                val (orderedStart, orderedEnd) = getOrderedHandles(textSelectStart!!, textSelectEnd!!)
                                
                                val slicedBoxes = calculateSubLineRects(detectedBoxes, orderedStart, orderedEnd)
                                
                                slicedBoxes.forEach { box ->
                                    val displayLeft = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                    val displayTop = cropState.imgOffsetY + box.top * cropState.scaleFactor
                                    val displayRight = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                    val displayBottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor
                                    
                                    val radius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                    drawRoundRect(
                                        color = KuriAmber.copy(alpha = 0.25f),
                                        topLeft = Offset(displayLeft, displayTop),
                                        size = Size(displayRight - displayLeft, displayBottom - displayTop),
                                        cornerRadius = radius
                                    )
                                    drawRoundRect(
                                        color = KuriAmber.copy(alpha = 0.8f),
                                        topLeft = Offset(displayLeft, displayTop),
                                        size = Size(displayRight - displayLeft, displayBottom - displayTop),
                                        cornerRadius = radius,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                                    )
                                }

                                if (isDragging && activeDraggingHandle != null) {
                                    val activeHandle = if (activeDraggingHandle == "START") textSelectStart else textSelectEnd
                                    val activeBox = activeHandle?.let { detectedBoxes.getOrNull(it.index) }
                                    if (activeHandle != null && activeBox != null) {
                                        val isVertical = activeBox.height() > activeBox.width()
                                        val cursorStroke = 2.dp.toPx()
                                        if (isVertical) {
                                            val y = cropState.imgOffsetY + (activeBox.top + activeBox.height() * activeHandle.offsetRatio) * cropState.scaleFactor
                                            val startX = cropState.imgOffsetX + activeBox.left * cropState.scaleFactor
                                            val endX = cropState.imgOffsetX + activeBox.right * cropState.scaleFactor
                                            drawLine(
                                                color = KuriAmber.copy(alpha = 0.95f),
                                                start = Offset(startX, y),
                                                end = Offset(endX, y),
                                                strokeWidth = cursorStroke,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        } else {
                                            val x = cropState.imgOffsetX + (activeBox.left + activeBox.width() * activeHandle.offsetRatio) * cropState.scaleFactor
                                            val startY = cropState.imgOffsetY + activeBox.top * cropState.scaleFactor
                                            val endY = cropState.imgOffsetY + activeBox.bottom * cropState.scaleFactor
                                            drawLine(
                                                color = KuriAmber.copy(alpha = 0.95f),
                                                start = Offset(x, startY),
                                                end = Offset(x, endY),
                                                strokeWidth = cursorStroke,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        }
                                    }
                                }
                                
                                val drawTeardrop = { handle: TextHandleState, isStart: Boolean -> 
                                    val box = detectedBoxes.getOrNull(handle.index)
                                    if (box != null) {
                                        val isVertical = box.height() > box.width()
                                        val path = androidx.compose.ui.graphics.Path()
                                        val R = 10.dp.toPx()
                                        val D = R * 1.414f
                                        val cx: Float
                                        val cy: Float
                                        
                                        if (isVertical) {
                                            val y = cropState.imgOffsetY + (box.top + box.height() * handle.offsetRatio) * cropState.scaleFactor
                                            if (isStart) {
                                                val x = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                                cx = x + D
                                                cy = y
                                                path.moveTo(x, y)
                                                path.lineTo(cx - R * 0.707f, cy - R * 0.707f)
                                                path.arcTo(
                                                    rect = ComposeRect(cx - R, cy - R, cx + R, cy + R),
                                                    startAngleDegrees = 225f,
                                                    sweepAngleDegrees = 270f,
                                                    forceMoveTo = false
                                                )
                                                path.close()
                                            } else {
                                                val x = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                                cx = x - D
                                                cy = y
                                                path.moveTo(x, y)
                                                path.lineTo(cx + R * 0.707f, cy + R * 0.707f)
                                                path.arcTo(
                                                    rect = ComposeRect(cx - R, cy - R, cx + R, cy + R),
                                                    startAngleDegrees = 45f,
                                                    sweepAngleDegrees = 270f,
                                                    forceMoveTo = false
                                                )
                                                path.close()
                                            }
                                        } else {
                                            val x = cropState.imgOffsetX + (box.left + box.width() * handle.offsetRatio) * cropState.scaleFactor
                                            val y = cropState.imgOffsetY + box.bottom * cropState.scaleFactor
                                            if (isStart) {
                                                cx = x - R
                                                cy = y + R
                                                path.moveTo(x, y)
                                                path.lineTo(x, y + R)
                                                path.arcTo(
                                                    rect = ComposeRect(x - 2f * R, y, x, y + 2f * R),
                                                    startAngleDegrees = 0f,
                                                    sweepAngleDegrees = 270f,
                                                    forceMoveTo = false
                                                )
                                                path.close()
                                            } else {
                                                cx = x + R
                                                cy = y + R
                                                path.moveTo(x, y)
                                                path.lineTo(x + R, y)
                                                path.arcTo(
                                                    rect = ComposeRect(x, y, x + 2f * R, y + 2f * R),
                                                    startAngleDegrees = 270f,
                                                    sweepAngleDegrees = 270f,
                                                    forceMoveTo = false
                                                )
                                                path.close()
                                            }
                                        }
                                        
                                        drawPath(path, color = Color.White)
                                        drawCircle(color = KuriAmber, radius = 6.dp.toPx(), center = Offset(cx, cy))
                                    }
                                }
                                
                                drawTeardrop(orderedStart, true)
                                drawTeardrop(orderedEnd, false)
                            }
                        }
                    } else if (isCropCleared && !isDragging) {
                        // Global subtle dimming mask over full image, waiting for user to drag and draw
                        val imageLeft = cropState.imgOffsetX
                        val imageTop = cropState.imgOffsetY
                        val imageWidth = cropState.imgDispWidth
                        val imageHeight = cropState.imgDispHeight
                        if (imageWidth > 0f && imageHeight > 0f) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.24f),
                                topLeft = Offset(imageLeft, imageTop),
                                size = Size(imageWidth, imageHeight)
                            )
                        }
                    } else if (hideOcrBoxes) {
                        // Draw dimming mask outside crop box bounds
                        // Top mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.6f),
                            topLeft = Offset(cropState.imgOffsetX, cropState.imgOffsetY),
                            size = Size(cropState.imgDispWidth, cropState.cropTop - cropState.imgOffsetY)
                        )
                        // Bottom mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.6f),
                            topLeft = Offset(cropState.imgOffsetX, cropState.cropBottom),
                            size = Size(cropState.imgDispWidth, cropState.imgOffsetY + cropState.imgDispHeight - cropState.cropBottom)
                        )
                        // Left mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.6f),
                            topLeft = Offset(cropState.imgOffsetX, cropState.cropTop),
                            size = Size(cropState.cropLeft - cropState.imgOffsetX, cropState.cropBottom - cropState.cropTop)
                        )
                        // Right mask
                        drawRect(
                            color = Color.Black.copy(alpha = 0.6f),
                            topLeft = Offset(cropState.cropRight, cropState.cropTop),
                            size = Size(cropState.imgOffsetX + cropState.imgDispWidth - cropState.cropRight, cropState.cropBottom - cropState.cropTop)
                        )
                        
                        // Draw elegant crop frame borders
                        val borderStrokeW = 2.dp.toPx()
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(cropState.cropLeft, cropState.cropTop),
                            size = Size(cropState.cropRight - cropState.cropLeft, cropState.cropBottom - cropState.cropTop),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = borderStrokeW)
                        )
                        
                        // Auxiliary grid lines (Rule of Thirds style inside crop frame)
                        if (gridAlpha > 0f) {
                            val frameW = cropState.cropRight - cropState.cropLeft
                            val frameH = cropState.cropBottom - cropState.cropTop
                            
                            drawLine(
                                color = Color.White.copy(alpha = gridAlpha),
                                start = Offset(cropState.cropLeft + frameW / 3f, cropState.cropTop),
                                end = Offset(cropState.cropLeft + frameW / 3f, cropState.cropBottom),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color.White.copy(alpha = gridAlpha),
                                start = Offset(cropState.cropLeft + (frameW * 2f) / 3f, cropState.cropTop),
                                end = Offset(cropState.cropLeft + (frameW * 2f) / 3f, cropState.cropBottom),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color.White.copy(alpha = gridAlpha),
                                start = Offset(cropState.cropLeft, cropState.cropTop + frameH / 3f),
                                end = Offset(cropState.cropRight, cropState.cropTop + frameH / 3f),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color.White.copy(alpha = gridAlpha),
                                start = Offset(cropState.cropLeft, cropState.cropTop + (frameH * 2f) / 3f),
                                end = Offset(cropState.cropRight, cropState.cropTop + (frameH * 2f) / 3f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        

                        

                        // Draw 4 corner L-shaped brackets (modern unobtrusive handles)
                        val cropW = cropState.cropRight - cropState.cropLeft
                        val cropH = cropState.cropBottom - cropState.cropTop
                        val armLen = minOf(14.dp.toPx(), cropW * 0.35f, cropH * 0.35f).coerceAtLeast(4.dp.toPx())
                        val cornerStroke = 3.5.dp.toPx()
                        val activeColor = KuriAmber
                        val defaultColor = Color.White

                        val tlColor = if (cropState.activeHandle == DragHandle.TOP_LEFT) activeColor else defaultColor
                        val trColor = if (cropState.activeHandle == DragHandle.TOP_RIGHT) activeColor else defaultColor
                        val blColor = if (cropState.activeHandle == DragHandle.BOTTOM_LEFT) activeColor else defaultColor
                        val brColor = if (cropState.activeHandle == DragHandle.BOTTOM_RIGHT) activeColor else defaultColor

                        // TL Bracket
                        drawLine(tlColor, Offset(cropState.cropLeft, cropState.cropTop), Offset(cropState.cropLeft + armLen, cropState.cropTop), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(tlColor, Offset(cropState.cropLeft, cropState.cropTop), Offset(cropState.cropLeft, cropState.cropTop + armLen), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                        // TR Bracket
                        drawLine(trColor, Offset(cropState.cropRight - armLen, cropState.cropTop), Offset(cropState.cropRight, cropState.cropTop), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(trColor, Offset(cropState.cropRight, cropState.cropTop), Offset(cropState.cropRight, cropState.cropTop + armLen), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                        // BL Bracket
                        drawLine(blColor, Offset(cropState.cropLeft, cropState.cropBottom - armLen), Offset(cropState.cropLeft, cropState.cropBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(blColor, Offset(cropState.cropLeft, cropState.cropBottom), Offset(cropState.cropLeft + armLen, cropState.cropBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                        // BR Bracket
                        drawLine(brColor, Offset(cropState.cropRight - armLen, cropState.cropBottom), Offset(cropState.cropRight, cropState.cropBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(brColor, Offset(cropState.cropRight, cropState.cropBottom - armLen), Offset(cropState.cropRight, cropState.cropBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                        // Draw 4 edge handles (centered caps, adaptively hidden on small frames)
                        val hideEdgeThreshold = 36.dp.toPx()
                        if (cropW >= hideEdgeThreshold && cropH >= hideEdgeThreshold) {
                            val edgeHandleLength = minOf(20.dp.toPx(), cropW * 0.3f, cropH * 0.3f)
                            val edgeHandleStroke = 3.dp.toPx()
                            val midX = (cropState.cropLeft + cropState.cropRight) / 2f
                            val midY = (cropState.cropTop + cropState.cropBottom) / 2f

                            // Top Edge Handle
                            drawLine(
                                color = if (cropState.activeHandle == DragHandle.TOP) activeColor else defaultColor,
                                start = Offset(midX - edgeHandleLength / 2f, cropState.cropTop),
                                end = Offset(midX + edgeHandleLength / 2f, cropState.cropTop),
                                strokeWidth = edgeHandleStroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            // Bottom Edge Handle
                            drawLine(
                                color = if (cropState.activeHandle == DragHandle.BOTTOM) activeColor else defaultColor,
                                start = Offset(midX - edgeHandleLength / 2f, cropState.cropBottom),
                                end = Offset(midX + edgeHandleLength / 2f, cropState.cropBottom),
                                strokeWidth = edgeHandleStroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            // Left Edge Handle
                            drawLine(
                                color = if (cropState.activeHandle == DragHandle.LEFT) activeColor else defaultColor,
                                start = Offset(cropState.cropLeft, midY - edgeHandleLength / 2f),
                                end = Offset(cropState.cropLeft, midY + edgeHandleLength / 2f),
                                strokeWidth = edgeHandleStroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            // Right Edge Handle
                            drawLine(
                                color = if (cropState.activeHandle == DragHandle.RIGHT) activeColor else defaultColor,
                                start = Offset(cropState.cropRight, midY - edgeHandleLength / 2f),
                                end = Offset(cropState.cropRight, midY + edgeHandleLength / 2f),
                                strokeWidth = edgeHandleStroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    } else {
                        if (detectedBoxes.isNotEmpty()) {
                            // Area-crop candidates remain independently editable.
                            val imageLeft = cropState.imgOffsetX
                            val imageTop = cropState.imgOffsetY
                            val imageRight = cropState.imgOffsetX + cropState.imgDispWidth
                            val imageBottom = cropState.imgOffsetY + cropState.imgDispHeight
                            val highlightedRects = detectedBoxes.mapIndexedNotNull { index, box ->
                                val isEditingThisBox = (editingOcrBoxIndex == index) || (selectedOcrBoxIndex == index && userSelectionMade)
                                val displayLeft = (if (isEditingThisBox) cropState.cropLeft else cropState.imgOffsetX + box.left * cropState.scaleFactor).coerceIn(imageLeft, imageRight)
                                val displayTop = (if (isEditingThisBox) cropState.cropTop else cropState.imgOffsetY + box.top * cropState.scaleFactor).coerceIn(imageTop, imageBottom)
                                val displayRight = (if (isEditingThisBox) cropState.cropRight else cropState.imgOffsetX + box.right * cropState.scaleFactor).coerceIn(imageLeft, imageRight)
                                val displayBottom = (if (isEditingThisBox) cropState.cropBottom else cropState.imgOffsetY + box.bottom * cropState.scaleFactor).coerceIn(imageTop, imageBottom)

                                if (displayRight > displayLeft && displayBottom > displayTop) {
                                    (index as Int?) to ComposeRect(displayLeft, displayTop, displayRight, displayBottom)
                                } else {
                                    null
                                }
                            }.toMutableList()

                            if (userSelectionMade && selectedOcrBoxIndex == null) {
                                val displayLeft = cropState.cropLeft.coerceIn(imageLeft, imageRight)
                                val displayTop = cropState.cropTop.coerceIn(imageTop, imageBottom)
                                val displayRight = cropState.cropRight.coerceIn(imageLeft, imageRight)
                                val displayBottom = cropState.cropBottom.coerceIn(imageTop, imageBottom)
                                if (displayRight > displayLeft && displayBottom > displayTop) {
                                    highlightedRects.add(null to ComposeRect(displayLeft, displayTop, displayRight, displayBottom))
                                }
                            }

                            drawContext.canvas.saveLayer(ComposeRect(imageLeft, imageTop, imageRight, imageBottom), Paint())
                            drawRect(
                                color = Color.Black.copy(alpha = 0.24f),
                                topLeft = Offset(imageLeft, imageTop),
                                size = Size(imageRight - imageLeft, imageBottom - imageTop)
                            )
                            highlightedRects.forEach { entry ->
                                val index = entry.first
                                val rect = entry.second
                                val isSelected = (selectedOcrBoxIndex != null && selectedOcrBoxIndex == index) || (selectedOcrBoxIndex == null && index == null && userSelectionMade)
                                val holeAlpha = if (isSelected) 1f else unselectedBoxesAlpha
                                if (holeAlpha > 0f) {
                                    drawRect(
                                        color = Color.Transparent,
                                        topLeft = Offset(rect.left, rect.top),
                                        size = Size(rect.width, rect.height),
                                        blendMode = BlendMode.Clear,
                                        alpha = holeAlpha
                                    )
                                }
                            }
                            drawContext.canvas.restore()

                            highlightedRects.forEach { entry ->
                                val index = entry.first
                                val rect = entry.second
                                val isSelected = (selectedOcrBoxIndex != null && selectedOcrBoxIndex == index) || (selectedOcrBoxIndex == null && index == null && userSelectionMade)
                                val boxAlpha = if (isSelected) 1f else unselectedBoxesAlpha

                                if (boxAlpha <= 0f) {
                                    return@forEach
                                }

                                val displayLeft = rect.left
                                val displayTop = rect.top
                                val displayRight = rect.right
                                val displayBottom = rect.bottom
                                val frameWidth = displayRight - displayLeft
                                val frameHeight = displayBottom - displayTop

                                if (frameWidth <= 0f || frameHeight <= 0f) {
                                    return@forEach
                                }

                                val haloStroke = 5.dp.toPx()
                                val shadowStroke = 3.5.dp.toPx()
                                val borderStroke = 2.dp.toPx()
                                val selectionColor = if (isSelected) KuriAmber else Color.White
                                val cornerColor = selectionColor.copy(alpha = boxAlpha)

                                drawRect(
                                    color = selectionColor.copy(alpha = 0.20f * boxAlpha),
                                    topLeft = Offset(displayLeft, displayTop),
                                    size = Size(frameWidth, frameHeight),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = haloStroke)
                                )
                                drawRect(
                                    color = Color.Black.copy(alpha = 0.35f * boxAlpha),
                                    topLeft = Offset(displayLeft, displayTop),
                                    size = Size(frameWidth, frameHeight),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = shadowStroke)
                                )

                                drawRect(
                                    color = selectionColor.copy(alpha = boxAlpha),
                                    topLeft = Offset(displayLeft, displayTop),
                                    size = Size(frameWidth, frameHeight),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = borderStroke)
                                )

                                val armLen = minOf(14.dp.toPx(), frameWidth * 0.35f, frameHeight * 0.35f).coerceAtLeast(4.dp.toPx())
                                val cornerStroke = 3.dp.toPx()

                                // TL Bracket
                                drawLine(cornerColor, Offset(displayLeft, displayTop), Offset(displayLeft + armLen, displayTop), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(cornerColor, Offset(displayLeft, displayTop), Offset(displayLeft, displayTop + armLen), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                                // TR Bracket
                                drawLine(cornerColor, Offset(displayRight - armLen, displayTop), Offset(displayRight, displayTop), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(cornerColor, Offset(displayRight, displayTop), Offset(displayRight, displayTop + armLen), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                                // BL Bracket
                                drawLine(cornerColor, Offset(displayLeft, displayBottom - armLen), Offset(displayLeft, displayBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(cornerColor, Offset(displayLeft, displayBottom), Offset(displayLeft + armLen, displayBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                                // BR Bracket
                                drawLine(cornerColor, Offset(displayRight - armLen, displayBottom), Offset(displayRight, displayBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(cornerColor, Offset(displayRight, displayBottom - armLen), Offset(displayRight, displayBottom), strokeWidth = cornerStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

                                val hideEdgeThreshold = 36.dp.toPx()
                                if (frameWidth >= hideEdgeThreshold && frameHeight >= hideEdgeThreshold) {
                                    val edgeLength = minOf(20.dp.toPx(), frameWidth * 0.3f, frameHeight * 0.3f)
                                    val edgeStroke = 3.dp.toPx()
                                    val midX = (displayLeft + displayRight) / 2f
                                    val midY = (displayTop + displayBottom) / 2f
                                    drawLine(cornerColor, Offset(midX - edgeLength / 2f, displayTop), Offset(midX + edgeLength / 2f, displayTop), edgeStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    drawLine(cornerColor, Offset(midX - edgeLength / 2f, displayBottom), Offset(midX + edgeLength / 2f, displayBottom), edgeStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    drawLine(cornerColor, Offset(displayLeft, midY - edgeLength / 2f), Offset(displayLeft, midY + edgeLength / 2f), edgeStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    drawLine(cornerColor, Offset(displayRight, midY - edgeLength / 2f), Offset(displayRight, midY + edgeLength / 2f), edgeStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                }
                            }
                        }
                    }
                }

                if (interactionMode == CropInteraction.TEXT_SELECT &&
                    isDragging &&
                    magnifierPosition != null &&
                    magnifierSource != null &&
                    textSelectStart != null &&
                    textSelectEnd != null
                ) {
                    val localPos = magnifierPosition!!
                    val localSource = magnifierSource!!
                    val activeHandleForLens = if (activeDraggingHandle == "START") textSelectStart else textSelectEnd
                    val activeBoxForLens = activeHandleForLens?.let { detectedBoxes.getOrNull(it.index) }
                    val isVerticalLens = activeBoxForLens?.let { it.height() > it.width() } ?: false
                    val lensWidth = if (isVerticalLens) 56.dp else 120.dp
                    val lensHeight = if (isVerticalLens) 120.dp else 56.dp
                    val lensWidthPx = with(density) { lensWidth.toPx() }
                    val lensHeightPx = with(density) { lensHeight.toPx() }
                    val offsetAboveFingerPx = with(density) { (if (isVerticalLens) 122.dp else 90.dp).toPx() }
                    val marginPx = with(density) { 8.dp.toPx() }

                    val rawCenterX = localPos.x
                    val rawCenterY = if (localPos.y - offsetAboveFingerPx - lensHeightPx / 2f < marginPx) {
                        localPos.y + offsetAboveFingerPx
                    } else {
                        localPos.y - offsetAboveFingerPx
                    }

                    val minX = marginPx
                    val maxX = (workspaceSize.width.toFloat() - lensWidthPx - marginPx).coerceAtLeast(minX)
                    val minY = marginPx
                    val maxY = (workspaceSize.height.toFloat() - lensHeightPx - marginPx).coerceAtLeast(minY)

                    val clampedX = (rawCenterX - lensWidthPx / 2f).coerceIn(minX, maxX)
                    val clampedY = (rawCenterY - lensHeightPx / 2f).coerceIn(minY, maxY)

                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = clampedX
                                translationY = clampedY
                            }
                            .size(lensWidth, lensHeight)
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(28.dp),
                                clip = false
                            )
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(28.dp))
                            .border(BorderStroke(2.dp, Color.White), RoundedCornerShape(28.dp))
                            .clip(RoundedCornerShape(28.dp))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val lensCenter = Offset(size.width / 2f, size.height / 2f)
                            withTransform({
                                translate(lensCenter.x, lensCenter.y)
                                scale(1.5f, 1.5f, pivot = Offset.Zero)
                                translate(-localSource.x, -localSource.y)
                            }) {
                                // 1. Draw base image
                                withTransform({
                                    translate(
                                        cropState.imgOffsetX + cropState.imgDispWidth / 2f,
                                        cropState.imgOffsetY + cropState.imgDispHeight / 2f
                                    )
                                    rotate(cropState.visualRotationAngle, pivot = Offset.Zero)
                                    translate(-cropState.imgDispWidth / 2f, -cropState.imgDispHeight / 2f)
                                }) {
                                    drawImage(
                                        image = imageBitmap,
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(cropState.imgDispWidth.toInt(), cropState.imgDispHeight.toInt())
                                    )
                                }

                                // 2. Draw selection highlights (slicedBoxes)
                                val (orderedStart, orderedEnd) = getOrderedHandles(textSelectStart!!, textSelectEnd!!)
                                val slicedBoxes = calculateSubLineRects(detectedBoxes, orderedStart, orderedEnd)
                                slicedBoxes.forEach { box ->
                                    val displayLeft = cropState.imgOffsetX + box.left * cropState.scaleFactor
                                    val displayTop = cropState.imgOffsetY + box.top * cropState.scaleFactor
                                    val displayRight = cropState.imgOffsetX + box.right * cropState.scaleFactor
                                    val displayBottom = cropState.imgOffsetY + box.bottom * cropState.scaleFactor

                                    val radius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                    drawRoundRect(
                                        color = KuriAmber.copy(alpha = 0.25f),
                                        topLeft = Offset(displayLeft, displayTop),
                                        size = Size(displayRight - displayLeft, displayBottom - displayTop),
                                        cornerRadius = radius
                                    )
                                    drawRoundRect(
                                        color = KuriAmber.copy(alpha = 0.8f),
                                        topLeft = Offset(displayLeft, displayTop),
                                        size = Size(displayRight - displayLeft, displayBottom - displayTop),
                                        cornerRadius = radius,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                                    )
                                }

                                // 3. Draw cursor line at the active cut position
                                val activeHandle = if (activeDraggingHandle == "START") textSelectStart else textSelectEnd
                                val activeBox = activeHandle?.let { detectedBoxes.getOrNull(it.index) }
                                if (activeHandle != null && activeBox != null) {
                                    val isVertical = activeBox.height() > activeBox.width()
                                    val cursorStroke = 2.dp.toPx()
                                    if (isVertical) {
                                        val y = cropState.imgOffsetY + (activeBox.top + activeBox.height() * activeHandle.offsetRatio) * cropState.scaleFactor
                                        val startX = cropState.imgOffsetX + activeBox.left * cropState.scaleFactor
                                        val endX = cropState.imgOffsetX + activeBox.right * cropState.scaleFactor
                                        drawLine(
                                            color = KuriAmber.copy(alpha = 0.95f),
                                            start = Offset(startX, y),
                                            end = Offset(endX, y),
                                            strokeWidth = cursorStroke,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                                        )
                                    } else {
                                        val x = cropState.imgOffsetX + (activeBox.left + activeBox.width() * activeHandle.offsetRatio) * cropState.scaleFactor
                                        val startY = cropState.imgOffsetY + activeBox.top * cropState.scaleFactor
                                        val endY = cropState.imgOffsetY + activeBox.bottom * cropState.scaleFactor
                                        drawLine(
                                            color = KuriAmber.copy(alpha = 0.95f),
                                            start = Offset(x, startY),
                                            end = Offset(x, endY),
                                            strokeWidth = cursorStroke,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SumiInk)
            .statusBarsPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val isLandscape = captureDeviceOrientation == DeviceOrientation.LANDSCAPE_LEFT || captureDeviceOrientation == DeviceOrientation.LANDSCAPE_RIGHT
            
            val workspaceModifier = Modifier
            .run {
                if (isLandscape) {
                    requiredSize(width = maxHeight, height = maxWidth)
                } else {
                    fillMaxSize()
                }
            }
            .graphicsLayer {
                if (captureDeviceOrientation == DeviceOrientation.LANDSCAPE_LEFT) {
                    rotationZ = 90f
                } else if (captureDeviceOrientation == DeviceOrientation.LANDSCAPE_RIGHT) {
                    rotationZ = -90f
                }
            }
            
        Box(
            contentAlignment = Alignment.Center, 
            modifier = Modifier.fillMaxSize()
        ) {
            WorkspaceArea(
                modifier = workspaceModifier
            )
        }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isDragging,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Floating Auto Deskew Button
                val manualDeskewAvailable = !autoDeskewAfterCapture ||
                    deskewOutcome is DeskewOutcome.Failed ||
                    deskewOutcome == DeskewOutcome.NoText ||
                    deskewOutcome == DeskewOutcome.TimedOut
                if (manualDeskewAvailable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 24.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        when (val detection = detectImageSkew(bitmap)) {
                                            is SkewDetectionResult.Detected -> {
                                                if (kotlin.math.abs(detection.angle) > 0.5f) {
                                                    performAnimatedDeskew(detection.angle)
                                                } else {
                                                    deskewOutcome = DeskewOutcome.NotNeeded(detection.angle)
                                                }
                                            }
                                            SkewDetectionResult.NoText -> deskewOutcome = DeskewOutcome.NoText
                                            is SkewDetectionResult.Failed -> {
                                                deskewOutcome = DeskewOutcome.Failed(detection.cause)
                                                android.widget.Toast.makeText(context, R.string.camera_auto_deskew_failed, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            SkewDetectionResult.TimedOut -> {
                                                deskewOutcome = DeskewOutcome.TimedOut
                                                android.widget.Toast.makeText(context, R.string.camera_auto_deskew_failed, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.e("CAMERA", "Auto skew detection failed", e)
                                    }
                                }
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "Auto Deskew", tint = Color.White)
                        }
                    }
                }

                // Unified Bottom Control Panel
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.9f)),
                                startY = 0f
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SegmentedControl(
                    selected = interactionMode,
                    onSelected = { 
                        interactionMode = it 
                        uiPreferencesRepository.saveCropInteraction(it.name)
                    }
                )

                val executeCropAction: ((Bitmap) -> Unit) -> Unit = { targetCallback ->
                    if (interactionMode == CropInteraction.TEXT_SELECT) {
                        if (detectedBoxes.isNotEmpty()) {
                            val startHandle = textSelectStart ?: TextHandleState(0, 0f)
                            val endHandle = textSelectEnd ?: TextHandleState(detectedBoxes.lastIndex, 1f)
                            val (orderedStart, orderedEnd) = getOrderedHandles(startHandle, endHandle)
                            val slicedBoxes = calculateSubLineRects(detectedBoxes, orderedStart, orderedEnd)
                            try {
                                val recomposed = MaskedCropHelper.createRecomposedBitmap(bitmap, slicedBoxes)
                                targetCallback(recomposed)
                            } catch (e: Exception) {
                                AppLogger.e("CAMERA", "Recomposed crop failed, fallback to masked", e)
                                try {
                                    val masked = MaskedCropHelper.createMaskedBitmap(bitmap, slicedBoxes)
                                    targetCallback(masked)
                                } catch (e2: Exception) {
                                    AppLogger.e("CAMERA", "Masked crop failed", e2)
                                    targetCallback(bitmap)
                                }
                            }
                        } else {
                            targetCallback(bitmap)
                        }
                    } else {
                        if (isCropCleared) {
                            targetCallback(bitmap)
                        } else {
                            val selectedIndex = selectedOcrBoxIndex
                            if (!userSelectionMade && !hideOcrBoxes && selectedIndex != null && selectedIndex in detectedBoxes.indices) {
                                val box = detectedBoxes[selectedIndex]
                                val x = box.left.coerceIn(0, bitmap.width)
                                val y = box.top.coerceIn(0, bitmap.height)
                                val w = minOf(bitmap.width - x, box.width())
                                val h = minOf(bitmap.height - y, box.height())
                                if (w > 0 && h > 0) {
                                    try {
                                        targetCallback(Bitmap.createBitmap(bitmap, x, y, w, h))
                                    } catch (e: Throwable) {
                                        targetCallback(bitmap)
                                    }
                                } else {
                                    targetCallback(bitmap)
                                }
                            } else {
                                val l = ((cropState.cropLeft - cropState.imgOffsetX) / cropState.scaleFactor).toInt().coerceIn(0, bitmap.width)
                                val t = ((cropState.cropTop - cropState.imgOffsetY) / cropState.scaleFactor).toInt().coerceIn(0, bitmap.height)
                                val r = ((cropState.cropRight - cropState.imgOffsetX) / cropState.scaleFactor).toInt().coerceIn(0, bitmap.width)
                                val b = ((cropState.cropBottom - cropState.imgOffsetY) / cropState.scaleFactor).toInt().coerceIn(0, bitmap.height)
                                val w = r - l
                                val h = b - t
                                if (w > 0 && h > 0) {
                                    try {
                                        targetCallback(Bitmap.createBitmap(bitmap, l, t, w, h))
                                    } catch (e: Throwable) {
                                        targetCallback(bitmap)
                                    }
                                } else {
                                    targetCallback(bitmap)
                                }
                            }
                        }
                    }
                }

                if (cameraMode == "text_input") {
                    if (batchCount > 0) {
                        Surface(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.camera_batch_count, batchCount),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { onCancel() },
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f)),
                            modifier = Modifier.weight(0.8f).height(50.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.camera_recapture_btn),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        if (onConfirmNext != null) {
                            OutlinedButton(
                                onClick = { executeCropAction(onConfirmNext) },
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                                modifier = Modifier.weight(1.55f).height(50.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.camera_capture_next),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }

                        Button(
                            onClick = { executeCropAction(onConfirm) },
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KuriAmber,
                                contentColor = SumiInk
                            ),
                            modifier = Modifier.weight(0.85f).height(50.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.camera_capture_done),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { onCancel() },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.camera_cancel_desc), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.camera_recapture_btn), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }

                        Button(
                            onClick = { executeCropAction(onConfirm) },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KuriAmber,
                                contentColor = SumiInk
                            ),
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.camera_confirm_desc), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.camera_confirm_btn), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
        }
    }
}



private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

