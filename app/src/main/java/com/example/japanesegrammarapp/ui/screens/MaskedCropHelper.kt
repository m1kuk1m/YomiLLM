package com.example.japanesegrammarapp.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect

object MaskedCropHelper {

    /**
     * Recomposes text selection boxes according to natural reading order:
     * - Vertical text <= 2 columns: sequential single vertical strip (top to bottom)
     * - Vertical text >= 3 columns: multi-column right-to-left top-aligned
     * - Horizontal text: rows left-aligned top to bottom
     */
    fun createRecomposedBitmap(
        originalBitmap: Bitmap,
        selectedBoxes: List<Rect>,
        padding: Int = 20,
        gap: Int = 12
    ): Bitmap {
        if (selectedBoxes.isEmpty()) {
            val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            emptyBitmap.eraseColor(Color.WHITE)
            return emptyBitmap
        }

        val plan = planRecompositionForAndroidRects(selectedBoxes, padding, gap)
        val width = plan.targetWidth.coerceAtLeast(1)
        val height = plan.targetHeight.coerceAtLeast(1)
        val config = originalBitmap.config ?: Bitmap.Config.ARGB_8888
        val resultBitmap = Bitmap.createBitmap(width, height, config)
        resultBitmap.eraseColor(Color.WHITE)

        val canvas = Canvas(resultBitmap)
        for (placement in plan.placements) {
            val src = placement.srcRect
            val dst = placement.dstRect

            val left = src.left.coerceIn(0, originalBitmap.width)
            val top = src.top.coerceIn(0, originalBitmap.height)
            val right = src.right.coerceIn(0, originalBitmap.width)
            val bottom = src.bottom.coerceIn(0, originalBitmap.height)

            if (left >= right || top >= bottom) continue

            val srcRect = Rect(left, top, right, bottom)
            val dstRect = Rect(dst.left, dst.top, dst.right, dst.bottom)
            canvas.drawBitmap(originalBitmap, srcRect, dstRect, null)
        }

        return resultBitmap
    }

    /**
     * Legacy masked crop retaining original absolute 2D positions on a white background.
     */
    fun createMaskedBitmap(originalBitmap: Bitmap, selectedBoxes: List<Rect>): Bitmap {
        if (selectedBoxes.isEmpty()) {
            val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            emptyBitmap.eraseColor(Color.WHITE)
            return emptyBitmap
        }

        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE

        for (box in selectedBoxes) {
            if (box.left < minLeft) minLeft = box.left
            if (box.top < minTop) minTop = box.top
            if (box.right > maxRight) maxRight = box.right
            if (box.bottom > maxBottom) maxBottom = box.bottom
        }

        // Calculate dynamic padding (5% of the largest dimension, bounded between 20 and 100 pixels)
        val boxWidth = maxRight - minLeft
        val boxHeight = maxBottom - minTop
        val dynamicPadding = (maxOf(boxWidth, boxHeight) * 0.05).toInt().coerceIn(20, 100)

        // Apply padding
        minLeft -= dynamicPadding
        minTop -= dynamicPadding
        maxRight += dynamicPadding
        maxBottom += dynamicPadding

        // Ensure bounds are within the original bitmap
        minLeft = minLeft.coerceAtLeast(0)
        minTop = minTop.coerceAtLeast(0)
        maxRight = maxRight.coerceAtMost(originalBitmap.width)
        maxBottom = maxBottom.coerceAtMost(originalBitmap.height)

        val width = maxRight - minLeft
        val height = maxBottom - minTop

        if (width <= 0 || height <= 0) {
            val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            emptyBitmap.eraseColor(Color.WHITE)
            return emptyBitmap
        }

        val config = originalBitmap.config ?: Bitmap.Config.ARGB_8888
        val maskedBitmap = Bitmap.createBitmap(width, height, config)
        
        // Fill the background with pure white
        maskedBitmap.eraseColor(Color.WHITE)

        val canvas = Canvas(maskedBitmap)

        for (box in selectedBoxes) {
            val left = box.left.coerceAtLeast(0)
            val top = box.top.coerceAtLeast(0)
            val right = box.right.coerceAtMost(originalBitmap.width)
            val bottom = box.bottom.coerceAtMost(originalBitmap.height)

            if (left >= right || top >= bottom) continue

            val srcRect = Rect(left, top, right, bottom)
            val dstRect = Rect(left - minLeft, top - minTop, right - minLeft, bottom - minTop)

            // Copy only the selected box pixels from original to the new white bitmap
            canvas.drawBitmap(originalBitmap, srcRect, dstRect, null)
        }

        return maskedBitmap
    }
}
