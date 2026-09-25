package com.example.japanesegrammarapp.ui.screens

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class CropStateTest {
    @Test
    fun initialCropBoxExposesCornersAndEdges() {
        val state = initializedState()
        val tolerance = 40f

        val expectedHandles = listOf(
            Offset(100f, 300f) to DragHandle.TOP_LEFT,
            Offset(900f, 300f) to DragHandle.TOP_RIGHT,
            Offset(100f, 700f) to DragHandle.BOTTOM_LEFT,
            Offset(900f, 700f) to DragHandle.BOTTOM_RIGHT,
            Offset(500f, 300f) to DragHandle.TOP,
            Offset(500f, 700f) to DragHandle.BOTTOM,
            Offset(100f, 500f) to DragHandle.LEFT,
            Offset(900f, 500f) to DragHandle.RIGHT
        )

        expectedHandles.forEach { (position, expected) ->
            state.startDrag(position, tolerance)
            assertEquals(expected, state.activeHandle)
            state.stopDrag()
        }
    }

    @Test
    fun edgeDragChangesOnlyItsOwnBoundaryAndStaysInsideImage() {
        val state = initializedState()
        state.startDrag(Offset(500f, 300f), 40f)

        state.onDrag(Offset(80f, -500f), minSizePx = 50f)

        assertEquals(0f, state.cropTop, 0.001f)
        assertEquals(100f, state.cropLeft, 0.001f)
        assertEquals(900f, state.cropRight, 0.001f)
        assertEquals(700f, state.cropBottom, 0.001f)
    }

    @Test
    fun edgeDragHonorsMinimumCropSize() {
        val state = initializedState()
        state.startDrag(Offset(500f, 300f), 40f)

        state.onDrag(Offset(0f, 1_000f), minSizePx = 120f)

        assertEquals(580f, state.cropTop, 0.001f)
        assertEquals(120f, state.cropBottom - state.cropTop, 0.001f)
    }

    @Test
    fun centerDragTranslatesCropBox() {
        val state = initializedState()
        // Center of crop box is (500f, 500f)
        state.startDrag(Offset(500f, 500f), 40f)
        assertEquals(DragHandle.CENTER, state.activeHandle)

        state.onDrag(Offset(30f, 40f), minSizePx = 50f)
        assertEquals(130f, state.cropLeft, 0.001f)
        assertEquals(930f, state.cropRight, 0.001f)
        assertEquals(340f, state.cropTop, 0.001f)
        assertEquals(740f, state.cropBottom, 0.001f)
        state.stopDrag()
        assertEquals(DragHandle.NONE, state.activeHandle)
    }

    private fun initializedState(): CropState {
        return CropState(bitmapWidth = 1_000f, bitmapHeight = 1_000f).apply {
            initializeCropBox(width = 1_000f, height = 1_000f)
        }
    }
}
