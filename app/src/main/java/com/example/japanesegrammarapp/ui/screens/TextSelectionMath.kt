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

fun mergeCollinearVerticalColumn(boxes: List<SelectionRect>): List<SelectionRect> {
    if (boxes.size <= 1) return boxes
    val sorted = boxes.sortedBy { it.top }
    val result = mutableListOf<SelectionRect>()
    var current = sorted[0]

    for (i in 1 until sorted.size) {
        val next = sorted[i]
        val minW = minOf(current.width, next.width).coerceAtLeast(1)
        val minH = minOf(current.height, next.height).coerceAtLeast(1)
        val overlapX = maxOf(0, minOf(current.right, next.right) - maxOf(current.left, next.left))
        val centerDistX = kotlin.math.abs(current.centerX - next.centerX)
        // Allow slight camera tilt / font variation: center distance within 85% width or 24px, or partial X overlap
        val isHorizontallyAligned = overlapX >= minW * 0.20f || centerDistX <= maxOf(minW * 0.85f, 24f)

        val overlapY = maxOf(0, minOf(current.bottom, next.bottom) - maxOf(current.top, next.top))
        // Parallel check: if they overlap in Y by more than 25% or more than line width, they are parallel lines!
        val isParallel = overlapY > minH * 0.25f || overlapY > maxOf(minW * 1.2f, 24f)

        val verticalOverlap = current.bottom >= next.top
        val verticalGap = if (!verticalOverlap) next.top - current.bottom else 0
        val isEndToEndNeighbor = !isParallel && (verticalOverlap || verticalGap <= maxOf(minW * 1.8f, 36f))

        if (isHorizontallyAligned && isEndToEndNeighbor) {
            current = SelectionRect(
                left = minOf(current.left, next.left),
                top = minOf(current.top, next.top),
                right = maxOf(current.right, next.right),
                bottom = maxOf(current.bottom, next.bottom)
            )
        } else {
            result.add(current)
            current = next
        }
    }
    result.add(current)
    return result
}

fun mergeCollinearHorizontalRow(boxes: List<SelectionRect>): List<SelectionRect> {
    if (boxes.size <= 1) return boxes
    val sorted = boxes.sortedBy { it.left }
    val result = mutableListOf<SelectionRect>()
    var current = sorted[0]

    for (i in 1 until sorted.size) {
        val next = sorted[i]
        val minH = minOf(current.height, next.height).coerceAtLeast(1)
        val minW = minOf(current.width, next.width).coerceAtLeast(1)
        val overlapY = maxOf(0, minOf(current.bottom, next.bottom) - maxOf(current.top, next.top))
        val centerDistY = kotlin.math.abs(current.centerY - next.centerY)
        val isVerticallyAligned = overlapY >= minH * 0.20f || centerDistY <= maxOf(minH * 0.85f, 24f)

        val overlapX = maxOf(0, minOf(current.right, next.right) - maxOf(current.left, next.left))
        // Parallel check: if they overlap in X by more than 25% or more than line height, they are parallel lines!
        val isParallel = overlapX > minW * 0.25f || overlapX > maxOf(minH * 1.2f, 24f)

        val horizontalOverlap = current.right >= next.left
        val horizontalGap = if (!horizontalOverlap) next.left - current.right else 0
        val isEndToEndNeighbor = !isParallel && (horizontalOverlap || horizontalGap <= maxOf(minH * 1.8f, 36f))

        if (isVerticallyAligned && isEndToEndNeighbor) {
            current = SelectionRect(
                left = minOf(current.left, next.left),
                top = minOf(current.top, next.top),
                right = maxOf(current.right, next.right),
                bottom = maxOf(current.bottom, next.bottom)
            )
        } else {
            result.add(current)
            current = next
        }
    }
    result.add(current)
    return result
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
    val verticalColumns = mutableListOf<List<SelectionRect>>()
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
            val threshold = maxOf(minW * 0.40f, 10f)

            val isParallelToAny = column.any { existing ->
                val overlapY = maxOf(0, minOf(existing.bottom, next.bottom) - maxOf(existing.top, next.top))
                val minH = minOf(existing.height, next.height).coerceAtLeast(1)
                overlapY > minH * 0.25f || overlapY > maxOf(minW * 1.2f, 24f)
            }

            val isCollinearWithAny = column.any { existing ->
                val boxMinW = minOf(existing.width, next.width).coerceAtLeast(1)
                val overlapX = maxOf(0, minOf(existing.right, next.right) - maxOf(existing.left, next.left))
                val centerDistX = kotlin.math.abs(existing.centerX - next.centerX)
                val verticalOverlap = maxOf(0, minOf(existing.bottom, next.bottom) - maxOf(existing.top, next.top))
                val verticalGap = if (existing.bottom < next.top) {
                    next.top - existing.bottom
                } else if (next.bottom < existing.top) {
                    existing.top - next.bottom
                } else {
                    0
                }
                val isNeighbor = verticalOverlap > 0 || verticalGap <= maxOf(boxMinW * 1.8f, 36f)
                val isAligned = overlapX >= boxMinW * 0.20f || centerDistX <= maxOf(boxMinW * 0.85f, 24f)
                isNeighbor && isAligned
            }

            val standardAligned = overlap >= minW * 0.50f || centerDist <= threshold
            if (!isParallelToAny && (standardAligned || isCollinearWithAny)) {
                column.add(next)
                colLeft = minOf(colLeft, next.left)
                colRight = maxOf(colRight, next.right)
                iterator.remove()
            }
        }
        val mergedColumn = mergeCollinearVerticalColumn(column)
        verticalColumns.add(mergedColumn)
    }
    verticalColumns.sortByDescending { col -> col.map { it.centerX }.average() }
    val sortedVertical = verticalColumns.flatten()

    // 2. Group and sort horizontal text: top-to-bottom rows, left-to-right within row
    val horizontalRows = mutableListOf<List<SelectionRect>>()
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
            val threshold = maxOf(minH * 0.40f, 10f)

            val isParallelToAny = row.any { existing ->
                val overlapX = maxOf(0, minOf(existing.right, next.right) - maxOf(existing.left, next.left))
                val minW = minOf(existing.width, next.width).coerceAtLeast(1)
                overlapX > minW * 0.25f || overlapX > maxOf(minH * 1.2f, 24f)
            }

            val isCollinearWithAny = row.any { existing ->
                val boxMinH = minOf(existing.height, next.height).coerceAtLeast(1)
                val overlapY = maxOf(0, minOf(existing.bottom, next.bottom) - maxOf(existing.top, next.top))
                val centerDistY = kotlin.math.abs(existing.centerY - next.centerY)
                val horizontalOverlap = maxOf(0, minOf(existing.right, next.right) - maxOf(existing.left, next.left))
                val horizontalGap = if (existing.right < next.left) {
                    next.left - existing.right
                } else if (next.right < existing.left) {
                    existing.left - next.right
                } else {
                    0
                }
                val isNeighbor = horizontalOverlap > 0 || horizontalGap <= maxOf(boxMinH * 1.8f, 36f)
                val isAligned = overlapY >= boxMinH * 0.20f || centerDistY <= maxOf(boxMinH * 0.85f, 24f)
                isNeighbor && isAligned
            }

            val standardAligned = overlap >= minH * 0.50f || centerDist <= threshold
            if (!isParallelToAny && (standardAligned || isCollinearWithAny)) {
                row.add(next)
                rowTop = minOf(rowTop, next.top)
                rowBottom = maxOf(rowBottom, next.bottom)
                iterator.remove()
            }
        }
        val mergedRow = mergeCollinearHorizontalRow(row)
        horizontalRows.add(mergedRow)
    }
    horizontalRows.sortBy { r -> r.map { it.centerY }.average() }
    val sortedHorizontal = horizontalRows.flatten()

    return sortedVertical + sortedHorizontal
}

fun mergeCollinearSelectionRects(boxes: List<SelectionRect>): List<SelectionRect> = sortSelectionRects(boxes)

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

