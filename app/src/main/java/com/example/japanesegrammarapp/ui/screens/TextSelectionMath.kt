package com.example.japanesegrammarapp.ui.screens

import android.graphics.Rect

data class TextHandleState(val index: Int, val offsetRatio: Float)

/**
 * Pure Kotlin representation of an axis-aligned bounding box for text selection.
 * Decouples selection geometry and reading-order calculation from Android framework stubs.
 */
data class SelectionRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromAndroidRect(rect: Rect): SelectionRect =
            SelectionRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}

val Rect.rectWidth: Int get() = right - left
val Rect.rectHeight: Int get() = bottom - top
val Rect.rectCenterX: Int get() = (left + right) / 2
val Rect.rectCenterY: Int get() = (top + bottom) / 2

fun getOrderedHandles(h1: TextHandleState, h2: TextHandleState): Pair<TextHandleState, TextHandleState> {
    return if (h1.index != h2.index) {
        if (h1.index < h2.index) Pair(h1, h2) else Pair(h2, h1)
    } else {
        if (h1.offsetRatio <= h2.offsetRatio) Pair(h1, h2) else Pair(h2, h1)
    }
}

fun isHandleAfter(h1: TextHandleState, h2: TextHandleState): Boolean {
    return if (h1.index != h2.index) {
        h1.index > h2.index
    } else {
        h1.offsetRatio > h2.offsetRatio
    }
}

fun snapOffsetRatio(ratio: Float, threshold: Float = 0.06f): Float {
    return when {
        ratio <= threshold -> 0.0f
        ratio >= 1.0f - threshold -> 1.0f
        else -> ratio.coerceIn(0.0f, 1.0f)
    }
}

fun sortSelectionRects(boxes: List<SelectionRect>): List<SelectionRect> {
    if (boxes.isEmpty()) return emptyList()

    val verticalBoxes = mutableListOf<SelectionRect>()
    val horizontalBoxes = mutableListOf<SelectionRect>()

    boxes.forEach { box ->
        if (box.height > box.width) {
            verticalBoxes.add(box)
        } else {
            horizontalBoxes.add(box)
        }
    }

    // 1. Group and sort vertical text: right-to-left columns, top-to-bottom within column
    val verticalColumns = mutableListOf<MutableList<SelectionRect>>()
    val remainingVertical = verticalBoxes.sortedByDescending { it.centerX }.toMutableList()

    while (remainingVertical.isNotEmpty()) {
        val current = remainingVertical.removeAt(0)
        val column = mutableListOf(current)
        var colLeft = current.left
        var colRight = current.right

        val iterator = remainingVertical.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            val overlap = maxOf(0, minOf(colRight, next.right) - maxOf(colLeft, next.left))
            val minW = minOf(colRight - colLeft, next.width).coerceAtLeast(1)
            val centerDist = kotlin.math.abs(next.centerX - (colLeft + colRight) / 2)
            val threshold = maxOf(minW * 0.55f, 12f)
            if (overlap >= minW * 0.40f || centerDist <= threshold) {
                column.add(next)
                colLeft = minOf(colLeft, next.left)
                colRight = maxOf(colRight, next.right)
                iterator.remove()
            }
        }
        column.sortBy { it.top }
        verticalColumns.add(column)
    }
    verticalColumns.sortByDescending { col -> col.map { it.centerX }.average() }
    val sortedVertical = verticalColumns.flatten()

    // 2. Group and sort horizontal text: top-to-bottom rows, left-to-right within row
    val horizontalRows = mutableListOf<MutableList<SelectionRect>>()
    val remainingHorizontal = horizontalBoxes.sortedBy { it.centerY }.toMutableList()

    while (remainingHorizontal.isNotEmpty()) {
        val current = remainingHorizontal.removeAt(0)
        val row = mutableListOf(current)
        var rowTop = current.top
        var rowBottom = current.bottom

        val iterator = remainingHorizontal.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            val overlap = maxOf(0, minOf(rowBottom, next.bottom) - maxOf(rowTop, next.top))
            val minH = minOf(rowBottom - rowTop, next.height).coerceAtLeast(1)
            val centerDist = kotlin.math.abs(next.centerY - (rowTop + rowBottom) / 2)
            val threshold = maxOf(minH * 0.55f, 12f)
            if (overlap >= minH * 0.40f || centerDist <= threshold) {
                row.add(next)
                rowTop = minOf(rowTop, next.top)
                rowBottom = maxOf(rowBottom, next.bottom)
                iterator.remove()
            }
        }
        row.sortBy { it.left }
        horizontalRows.add(row)
    }
    horizontalRows.sortBy { r -> r.map { it.centerY }.average() }
    val sortedHorizontal = horizontalRows.flatten()

    return sortedVertical + sortedHorizontal
}

fun calculateSubLineSelectionRects(
    boxes: List<SelectionRect>,
    start: TextHandleState,
    end: TextHandleState
): List<SelectionRect> {
    if (boxes.isEmpty()) return emptyList()
    val startBox = boxes.getOrNull(start.index) ?: return emptyList()
    if (end.index !in boxes.indices) return emptyList()
    val isVertical = startBox.height > startBox.width

    if (start.index == end.index) {
        val width = startBox.width
        val height = startBox.height
        val r1 = minOf(start.offsetRatio, end.offsetRatio).coerceIn(0f, 1f)
        val r2 = maxOf(start.offsetRatio, end.offsetRatio).coerceIn(0f, 1f)

        val rect = if (isVertical) {
            val top = (startBox.top + height * r1).toInt()
            val bottom = (startBox.top + height * r2).toInt()
            SelectionRect(startBox.left, top, startBox.right, bottom)
        } else {
            val left = (startBox.left + width * r1).toInt()
            val right = (startBox.left + width * r2).toInt()
            SelectionRect(left, startBox.top, right, startBox.bottom)
        }
        return if (rect.width > 0 && rect.height > 0) listOf(rect) else emptyList()
    }

    val minIdx = minOf(start.index, end.index)
    val maxIdx = maxOf(start.index, end.index)
    val result = mutableListOf<SelectionRect>()

    for (i in minIdx..maxIdx) {
        val box = boxes.getOrNull(i) ?: continue
        if ((box.height > box.width) != isVertical) continue

        val width = box.width
        val height = box.height

        var newLeft = box.left
        var newTop = box.top
        var newRight = box.right
        var newBottom = box.bottom

        if (i == start.index) {
            if (isVertical) {
                newTop = (box.top + height * start.offsetRatio.coerceIn(0f, 1f)).toInt()
            } else {
                newLeft = (box.left + width * start.offsetRatio.coerceIn(0f, 1f)).toInt()
            }
        } else if (i == end.index) {
            if (isVertical) {
                newBottom = (box.top + height * end.offsetRatio.coerceIn(0f, 1f)).toInt()
            } else {
                newRight = (box.left + width * end.offsetRatio.coerceIn(0f, 1f)).toInt()
            }
        }

        if (newLeft < newRight && newTop < newBottom) {
            result.add(SelectionRect(newLeft, newTop, newRight, newBottom))
        }
    }

    return result
}

fun sortOcrBoxesForTextSelect(boxes: List<Rect>): List<Rect> {
    return sortSelectionRects(boxes.map { SelectionRect.fromAndroidRect(it) })
        .map { it.toAndroidRect() }
}

fun calculateSubLineRects(
    boxes: List<Rect>,
    start: TextHandleState,
    end: TextHandleState
): List<Rect> {
    return calculateSubLineSelectionRects(
        boxes.map { SelectionRect.fromAndroidRect(it) },
        start,
        end
    ).map { it.toAndroidRect() }
}

enum class RecompositionMode {
    EMPTY,
    SINGLE_BOX,
    SINGLE_COLUMN_VERTICAL,
    MULTI_COLUMN_VERTICAL,
    ROWS_HORIZONTAL
}

data class RecomposedPlacement(
    val srcRect: SelectionRect,
    val dstRect: SelectionRect
)

data class RecompositionPlan(
    val mode: RecompositionMode,
    val targetWidth: Int,
    val targetHeight: Int,
    val placements: List<RecomposedPlacement>
)

fun planRecomposition(
    boxes: List<SelectionRect>,
    padding: Int = 20,
    gap: Int = 12
): RecompositionPlan {
    if (boxes.isEmpty()) {
        return RecompositionPlan(
            mode = RecompositionMode.EMPTY,
            targetWidth = 1,
            targetHeight = 1,
            placements = emptyList()
        )
    }

    if (boxes.size == 1) {
        val box = boxes[0]
        val dst = SelectionRect(padding, padding, padding + box.width, padding + box.height)
        return RecompositionPlan(
            mode = RecompositionMode.SINGLE_BOX,
            targetWidth = box.width + padding * 2,
            targetHeight = box.height + padding * 2,
            placements = listOf(RecomposedPlacement(box, dst))
        )
    }

    val isVertical = boxes.count { it.height > it.width } >= (boxes.size + 1) / 2

    if (isVertical) {
        // Group into vertical columns (adjacent boxes belonging to the same column)
        val columns = mutableListOf<MutableList<SelectionRect>>()
        for (box in boxes) {
            if (columns.isEmpty()) {
                columns.add(mutableListOf(box))
            } else {
                val lastCol = columns.last()
                val belongsToLastCol = lastCol.any { prev ->
                    val overlapX = maxOf(0, minOf(prev.right, box.right) - maxOf(prev.left, box.left))
                    val minW = minOf(prev.width, box.width).coerceAtLeast(1)
                    val centerDist = kotlin.math.abs(prev.centerX - box.centerX)
                    overlapX >= minW * 0.40f || centerDist <= maxOf(minW * 0.55f, 12f)
                }
                if (belongsToLastCol) {
                    lastCol.add(box)
                } else {
                    columns.add(mutableListOf(box))
                }
            }
        }

        if (columns.size <= 2) {
            // Single continuous vertical column (ordered sequentially)
            val maxColWidth = boxes.maxOf { it.width }
            val totalContentHeight = boxes.sumOf { it.height } + (boxes.size - 1) * gap
            val targetWidth = maxColWidth + padding * 2
            val targetHeight = totalContentHeight + padding * 2

            val placements = mutableListOf<RecomposedPlacement>()
            var curY = padding
            for (box in boxes) {
                val curX = padding + (maxColWidth - box.width) / 2
                val dst = SelectionRect(curX, curY, curX + box.width, curY + box.height)
                placements.add(RecomposedPlacement(box, dst))
                curY += box.height + gap
            }

            return RecompositionPlan(
                mode = RecompositionMode.SINGLE_COLUMN_VERTICAL,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                placements = placements
            )
        } else {
            // Multi-column right-to-left, top-aligned
            val colWidths = columns.map { col -> col.maxOf { it.width } }
            val colHeights = columns.map { col -> col.sumOf { it.height } + (col.size - 1) * gap }

            val totalContentWidth = colWidths.sum() + (columns.size - 1) * gap
            val maxContentHeight = colHeights.maxOrNull() ?: 0
            val targetWidth = totalContentWidth + padding * 2
            val targetHeight = maxContentHeight + padding * 2

            val placements = mutableListOf<RecomposedPlacement>()
            var curRight = targetWidth - padding

            for (colIdx in columns.indices) {
                val col = columns[colIdx]
                val w = colWidths[colIdx]
                val colLeft = curRight - w
                var curY = padding

                for (box in col) {
                    val curX = colLeft + (w - box.width) / 2
                    val dst = SelectionRect(curX, curY, curX + box.width, curY + box.height)
                    placements.add(RecomposedPlacement(box, dst))
                    curY += box.height + gap
                }

                curRight = colLeft - gap
            }

            return RecompositionPlan(
                mode = RecompositionMode.MULTI_COLUMN_VERTICAL,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                placements = placements
            )
        }
    } else {
        // Horizontal text: group into rows
        val rows = mutableListOf<MutableList<SelectionRect>>()
        for (box in boxes) {
            if (rows.isEmpty()) {
                rows.add(mutableListOf(box))
            } else {
                val lastRow = rows.last()
                val belongsToLastRow = lastRow.any { prev ->
                    val overlapY = maxOf(0, minOf(prev.bottom, box.bottom) - maxOf(prev.top, box.top))
                    val minH = minOf(prev.height, box.height).coerceAtLeast(1)
                    val centerDist = kotlin.math.abs(prev.centerY - box.centerY)
                    overlapY >= minH * 0.40f || centerDist <= maxOf(minH * 0.55f, 12f)
                }
                if (belongsToLastRow) {
                    lastRow.add(box)
                } else {
                    rows.add(mutableListOf(box))
                }
            }
        }

        // Each row starts from x = padding (left-aligned)
        val rowWidths = rows.map { r -> r.sumOf { it.width } + (r.size - 1) * gap }
        val rowHeights = rows.map { r -> r.maxOf { it.height } }

        val maxRowWidth = rowWidths.maxOrNull() ?: 0
        val totalContentHeight = rowHeights.sum() + (rows.size - 1) * gap
        val targetWidth = maxRowWidth + padding * 2
        val targetHeight = totalContentHeight + padding * 2

        val placements = mutableListOf<RecomposedPlacement>()
        var curY = padding

        for (rowIdx in rows.indices) {
            val row = rows[rowIdx]
            val h = rowHeights[rowIdx]
            var curX = padding

            for (box in row) {
                val curBoxY = curY + (h - box.height) / 2
                val dst = SelectionRect(curX, curBoxY, curX + box.width, curBoxY + box.height)
                placements.add(RecomposedPlacement(box, dst))
                curX += box.width + gap
            }

            curY += h + gap
        }

        return RecompositionPlan(
            mode = RecompositionMode.ROWS_HORIZONTAL,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            placements = placements
        )
    }
}

fun planRecompositionForAndroidRects(
    boxes: List<Rect>,
    padding: Int = 20,
    gap: Int = 12
): RecompositionPlan {
    return planRecomposition(boxes.map { SelectionRect.fromAndroidRect(it) }, padding, gap)
}

