package com.example.japanesegrammarapp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionMathTest {

    @Test
    fun sortSelectionRects_verticalColumns_sortedRightToLeft_andTopToBottom() {
        val col1 = SelectionRect(840, 201, 870, 780)
        val col2 = SelectionRect(760, 203, 790, 350)
        val col3 = SelectionRect(670, 198, 700, 785)
        val col4 = SelectionRect(590, 202, 620, 775)
        val col5 = SelectionRect(500, 200, 530, 780)
        val col6 = SelectionRect(410, 199, 440, 782)
        val col7 = SelectionRect(330, 204, 360, 778)
        val col8 = SelectionRect(240, 197, 270, 780)

        // Shuffled / arbitrary input
        val unsorted = listOf(col8, col3, col6, col1, col7, col4, col2, col5)
        val sorted = sortSelectionRects(unsorted)

        assertEquals(8, sorted.size)
        assertEquals(col1, sorted[0])
        assertEquals(col2, sorted[1])
        assertEquals(col3, sorted[2])
        assertEquals(col4, sorted[3])
        assertEquals(col5, sorted[4])
        assertEquals(col6, sorted[5])
        assertEquals(col7, sorted[6])
        assertEquals(col8, sorted[7])
    }

    @Test
    fun sortSelectionRects_mixedOrientation_isolatesVerticalAndHorizontal() {
        val header = SelectionRect(100, 50, 900, 90) // Horizontal
        val col1 = SelectionRect(800, 200, 830, 700) // Vertical
        val col2 = SelectionRect(700, 200, 730, 700) // Vertical
        val footer = SelectionRect(100, 850, 900, 890) // Horizontal

        val sorted = sortSelectionRects(listOf(footer, col2, header, col1))

        assertEquals(4, sorted.size)
        // Vertical first in Right-to-Left order
        assertEquals(col1, sorted[0])
        assertEquals(col2, sorted[1])
        // Horizontal next in Top-to-Bottom order
        assertEquals(header, sorted[2])
        assertEquals(footer, sorted[3])
    }

    @Test
    fun calculateSubLineSelectionRects_verticalRightToLeft_selectsFromStartToBottom_andTopToEnd() {
        val col5 = SelectionRect(500, 200, 530, 800) // height = 600
        val col6 = SelectionRect(400, 200, 430, 800) // height = 600
        val boxes = listOf(col5, col6)

        // Drag from Col 5 (middle, ratio 0.4) to Col 6 (top, ratio 0.1)
        val start = TextHandleState(index = 0, offsetRatio = 0.4f)
        val end = TextHandleState(index = 1, offsetRatio = 0.1f)

        val sliced = calculateSubLineSelectionRects(boxes, start, end)

        assertEquals(2, sliced.size)
        // Col 5: from ratio 0.4 down to bottom
        assertEquals(col5.left, sliced[0].left)
        assertEquals(col5.right, sliced[0].right)
        assertEquals(200 + (600 * 0.4f).toInt(), sliced[0].top)
        assertEquals(col5.bottom, sliced[0].bottom)

        // Col 6: from top down to ratio 0.1
        assertEquals(col6.left, sliced[1].left)
        assertEquals(col6.right, sliced[1].right)
        assertEquals(col6.top, sliced[1].top)
        assertEquals(200 + (600 * 0.1f).toInt(), sliced[1].bottom)
    }

    @Test
    fun calculateSubLineSelectionRects_verticalLeftToRight_respectsGestureFlow() {
        val col5 = SelectionRect(500, 200, 530, 800) // height = 600
        val col6 = SelectionRect(400, 200, 430, 800) // height = 600
        val boxes = listOf(col5, col6)

        // Drag from Col 6 (top, ratio 0.1) to Col 5 (middle, ratio 0.4)
        val start = TextHandleState(index = 1, offsetRatio = 0.1f)
        val end = TextHandleState(index = 0, offsetRatio = 0.4f)

        val sliced = calculateSubLineSelectionRects(boxes, start, end)

        assertEquals(2, sliced.size)
        // Index 0 (Col 5 is end): from top down to ratio 0.4
        assertEquals(col5.top, sliced[0].top)
        assertEquals(200 + (600 * 0.4f).toInt(), sliced[0].bottom)

        // Index 1 (Col 6 is start): from ratio 0.1 down to bottom
        assertEquals(200 + (600 * 0.1f).toInt(), sliced[1].top)
        assertEquals(col6.bottom, sliced[1].bottom)
    }

    @Test
    fun calculateSubLineSelectionRects_sameColumn_slicesBetweenRatios() {
        val col = SelectionRect(500, 200, 530, 800) // height = 600
        val boxes = listOf(col)

        // Drag downwards
        val slicedDown = calculateSubLineSelectionRects(
            boxes,
            TextHandleState(0, 0.2f),
            TextHandleState(0, 0.7f)
        )
        assertEquals(1, slicedDown.size)
        assertEquals(200 + (600 * 0.2f).toInt(), slicedDown[0].top)
        assertEquals(200 + (600 * 0.7f).toInt(), slicedDown[0].bottom)

        // Drag upwards
        val slicedUp = calculateSubLineSelectionRects(
            boxes,
            TextHandleState(0, 0.7f),
            TextHandleState(0, 0.2f)
        )
        assertEquals(1, slicedUp.size)
        assertEquals(200 + (600 * 0.2f).toInt(), slicedUp[0].top)
        assertEquals(200 + (600 * 0.7f).toInt(), slicedUp[0].bottom)
    }

    @Test
    fun calculateSubLineSelectionRects_multiColumn_includesIntermediateColumnsFully() {
        val col1 = SelectionRect(700, 200, 730, 800)
        val col2 = SelectionRect(600, 200, 630, 800)
        val col3 = SelectionRect(500, 200, 530, 800)
        val boxes = listOf(col1, col2, col3)

        val start = TextHandleState(0, 0.5f)
        val end = TextHandleState(2, 0.3f)

        val sliced = calculateSubLineSelectionRects(boxes, start, end)

        assertEquals(3, sliced.size)
        // Col 1: 0.5 to bottom
        assertEquals(200 + (600 * 0.5f).toInt(), sliced[0].top)
        assertEquals(800, sliced[0].bottom)
        // Col 2: full column
        assertEquals(200, sliced[1].top)
        assertEquals(800, sliced[1].bottom)
        // Col 3: top to 0.3
        assertEquals(200, sliced[2].top)
        assertEquals(200 + (600 * 0.3f).toInt(), sliced[2].bottom)
    }

    @Test
    fun calculateSubLineSelectionRects_horizontalRows_slicesProperly() {
        val row1 = SelectionRect(100, 200, 600, 240) // width = 500
        val row2 = SelectionRect(100, 260, 600, 300) // width = 500
        val boxes = listOf(row1, row2)

        val start = TextHandleState(0, 0.3f)
        val end = TextHandleState(1, 0.7f)

        val sliced = calculateSubLineSelectionRects(boxes, start, end)

        assertEquals(2, sliced.size)
        // Row 1: 0.3 to right
        assertEquals(100 + (500 * 0.3f).toInt(), sliced[0].left)
        assertEquals(600, sliced[0].right)
        // Row 2: left to 0.7
        assertEquals(100, sliced[1].left)
        assertEquals(100 + (500 * 0.7f).toInt(), sliced[1].right)
    }

    @Test
    fun isHandleAfter_comparesIndicesAndOffsetRatiosCorrectly() {
        val h1 = TextHandleState(0, 0.5f)
        val h2 = TextHandleState(1, 0.2f)
        val h3 = TextHandleState(0, 0.8f)

        // h2 (index 1) is after h1 (index 0)
        assertEquals(true, isHandleAfter(h2, h1))
        assertEquals(false, isHandleAfter(h1, h2))

        // Same index: h3 (ratio 0.8) is after h1 (ratio 0.5)
        assertEquals(true, isHandleAfter(h3, h1))
        assertEquals(false, isHandleAfter(h1, h3))

        // Same position
        assertEquals(false, isHandleAfter(h1, h1))
    }

    @Test
    fun snapOffsetRatio_snapsNearBoundaries() {
        assertEquals(0.0f, snapOffsetRatio(0.03f, 0.06f), 0.0001f)
        assertEquals(0.0f, snapOffsetRatio(0.00f, 0.06f), 0.0001f)
        assertEquals(1.0f, snapOffsetRatio(0.95f, 0.06f), 0.0001f)
        assertEquals(1.0f, snapOffsetRatio(1.00f, 0.06f), 0.0001f)
        assertEquals(0.5f, snapOffsetRatio(0.5f, 0.06f), 0.0001f)
        assertEquals(0.12f, snapOffsetRatio(0.12f, 0.06f), 0.0001f)
    }

    @Test
    fun planRecomposition_empty_returnsEmptyPlan() {
        val plan = planRecomposition(emptyList())
        assertEquals(RecompositionMode.EMPTY, plan.mode)
        assertEquals(1, plan.targetWidth)
        assertEquals(1, plan.targetHeight)
        assertEquals(0, plan.placements.size)
    }

    @Test
    fun planRecomposition_singleBox_cropsWithPadding() {
        val box = SelectionRect(100, 200, 150, 400) // width=50, height=200
        val plan = planRecomposition(listOf(box), padding = 20, gap = 12)
        assertEquals(RecompositionMode.SINGLE_BOX, plan.mode)
        assertEquals(90, plan.targetWidth)
        assertEquals(240, plan.targetHeight)
        assertEquals(1, plan.placements.size)
        assertEquals(SelectionRect(20, 20, 70, 220), plan.placements[0].dstRect)
    }

    @Test
    fun planRecomposition_verticalTwoColumns_assemblesIntoSingleColumnVerticalStrip() {
        // Simulates the exact user problem:
        // Box 0: Column 2 bottom (それだけ)
        // Box 1: Column 3 top (の才能を持ちながら増長もしていない。)
        val box0 = SelectionRect(430, 800, 465, 920) // width=35, height=120
        val box1 = SelectionRect(360, 160, 395, 640) // width=35, height=480

        val plan = planRecomposition(listOf(box0, box1), padding = 20, gap = 12)

        assertEquals(RecompositionMode.SINGLE_COLUMN_VERTICAL, plan.mode)
        // targetWidth = 35 + 20*2 = 75
        assertEquals(75, plan.targetWidth)
        // targetHeight = 120 + 480 + 12 + 20*2 = 652
        assertEquals(652, plan.targetHeight)
        assertEquals(2, plan.placements.size)

        // Box 0 is placed at top
        assertEquals(box0, plan.placements[0].srcRect)
        assertEquals(SelectionRect(20, 20, 55, 140), plan.placements[0].dstRect)

        // Box 1 is placed below Box 0 with 12px gap
        assertEquals(box1, plan.placements[1].srcRect)
        assertEquals(SelectionRect(20, 152, 55, 632), plan.placements[1].dstRect)
    }

    @Test
    fun planRecomposition_verticalThreeColumns_assemblesIntoMultiColumnRtlTopAligned() {
        // 3 vertical columns
        val col0 = SelectionRect(800, 200, 830, 600) // width=30, height=400 (rightmost)
        val col1 = SelectionRect(700, 200, 730, 600) // width=30, height=400 (middle)
        val col2 = SelectionRect(600, 200, 630, 600) // width=30, height=400 (leftmost)

        val plan = planRecomposition(listOf(col0, col1, col2), padding = 20, gap = 12)

        assertEquals(RecompositionMode.MULTI_COLUMN_VERTICAL, plan.mode)
        // targetWidth = 30*3 + 12*2 + 20*2 = 154
        assertEquals(154, plan.targetWidth)
        // targetHeight = 400 + 20*2 = 440
        assertEquals(440, plan.targetHeight)
        assertEquals(3, plan.placements.size)

        // Column 0 is placed on the RIGHT
        assertEquals(SelectionRect(104, 20, 134, 420), plan.placements[0].dstRect)
        // Column 1 is placed in the MIDDLE
        assertEquals(SelectionRect(62, 20, 92, 420), plan.placements[1].dstRect)
        // Column 2 is placed on the LEFT
        assertEquals(SelectionRect(20, 20, 50, 420), plan.placements[2].dstRect)

        // All 3 columns start at top = 20 (top-aligned)
        assertEquals(20, plan.placements[0].dstRect.top)
        assertEquals(20, plan.placements[1].dstRect.top)
        assertEquals(20, plan.placements[2].dstRect.top)
    }

    @Test
    fun planRecomposition_horizontalTwoRows_assemblesIntoLeftAlignedRows() {
        val row0 = SelectionRect(500, 100, 800, 140) // width=300, height=40 (right side of line 1)
        val row1 = SelectionRect(100, 160, 450, 200) // width=350, height=40 (left side of line 2)

        val plan = planRecomposition(listOf(row0, row1), padding = 20, gap = 12)

        assertEquals(RecompositionMode.ROWS_HORIZONTAL, plan.mode)
        // targetWidth = 350 + 20*2 = 390
        assertEquals(390, plan.targetWidth)
        // targetHeight = 40*2 + 12 + 20*2 = 132
        assertEquals(132, plan.targetHeight)
        assertEquals(2, plan.placements.size)

        // Both rows are left-aligned at x = 20
        assertEquals(20, plan.placements[0].dstRect.left)
        assertEquals(20, plan.placements[1].dstRect.left)

        // Row 0 is at top
        assertEquals(SelectionRect(20, 20, 320, 60), plan.placements[0].dstRect)
        // Row 1 is below Row 0 with 12px gap
        assertEquals(SelectionRect(20, 72, 370, 112), plan.placements[1].dstRect)
    }

    @Test
    fun sortSelectionRects_verticalColumnWithCommaOverlap_mergesIntoSingleBox() {
        val part1 = SelectionRect(800, 100, 830, 300) // width=30, height=200
        val part2 = SelectionRect(798, 285, 832, 600) // width=34, height=315 (15px overlap)

        val sorted = sortSelectionRects(listOf(part2, part1))

        assertEquals(1, sorted.size)
        assertEquals(SelectionRect(798, 100, 832, 600), sorted[0])
    }

    @Test
    fun sortSelectionRects_verticalColumnWithCommaSmallGap_mergesIntoSingleBox() {
        val part1 = SelectionRect(800, 100, 830, 300) // width=30
        val part2 = SelectionRect(800, 312, 830, 600) // gap=12px (<= 30 * 1.2 = 36px)

        val sorted = sortSelectionRects(listOf(part1, part2))

        assertEquals(1, sorted.size)
        assertEquals(SelectionRect(800, 100, 830, 600), sorted[0])
    }

    @Test
    fun sortSelectionRects_verticalDistinctParagraphs_notMerged() {
        val para1 = SelectionRect(800, 100, 830, 300) // width=30
        val para2 = SelectionRect(800, 450, 830, 650) // gap=150px (> 36px)

        val sorted = sortSelectionRects(listOf(para2, para1))

        assertEquals(2, sorted.size)
        assertEquals(para1, sorted[0])
        assertEquals(para2, sorted[1])
    }

    @Test
    fun sortSelectionRects_verticalAdjacentColumns_notMerged() {
        val col1 = SelectionRect(800, 100, 830, 600)
        val col2 = SelectionRect(750, 100, 780, 600)

        val sorted = sortSelectionRects(listOf(col2, col1))

        assertEquals(2, sorted.size)
        assertEquals(col1, sorted[0])
        assertEquals(col2, sorted[1])
    }

    @Test
    fun sortSelectionRects_horizontalLineWithComma_mergesIntoSingleRow() {
        val part1 = SelectionRect(100, 200, 350, 230) // height=30
        val part2 = SelectionRect(340, 198, 600, 232) // overlap=10px

        val sorted = sortSelectionRects(listOf(part2, part1))

        assertEquals(1, sorted.size)
        assertEquals(SelectionRect(100, 198, 600, 232), sorted[0])
    }

    @Test
    fun sortSelectionRects_denseAdjacentParallelVerticalColumns_neverMerged() {
        // Simulates the exact user problem: two adjacent parallel columns on an e-reader
        // that slightly overlap in X due to OCR unclip padding, but run parallel in Y
        val col1 = SelectionRect(820, 200, 860, 700) // rightmost column
        val col2 = SelectionRect(785, 195, 825, 705) // adjacent column, 5px X-overlap

        val sorted = sortSelectionRects(listOf(col2, col1))

        assertEquals(2, sorted.size)
        assertEquals(col1, sorted[0])
        assertEquals(col2, sorted[1])
    }

    @Test
    fun sortSelectionRects_denseAdjacentParallelHorizontalRows_neverMerged() {
        val row1 = SelectionRect(100, 100, 600, 135) // top row
        val row2 = SelectionRect(98, 130, 602, 168)  // adjacent bottom row, 5px Y-overlap

        val sorted = sortSelectionRects(listOf(row2, row1))

        assertEquals(2, sorted.size)
        assertEquals(row1, sorted[0])
        assertEquals(row2, sorted[1])
    }

    @Test
    fun sortSelectionRects_slantedTiltedVerticalColumn_mergesIntoSingleBox() {
        // Simulates the exact user problem:
        // Col 1: 彼女を筆頭に... (rightmost)
        // Col 2: fractured by OCR into 'トリスと' (top, cx=125) and '続く。' (bottom, cx=140) due to 2.5 deg tilt
        val col1 = SelectionRect(165, 200, 190, 500)
        val col2Top = SelectionRect(115, 200, 135, 280) // 'トリスと'
        val col2Bot = SelectionRect(130, 285, 150, 370) // '続く。'

        val sorted = sortSelectionRects(listOf(col2Bot, col1, col2Top))

        assertEquals(2, sorted.size)
        assertEquals(col1, sorted[0])
        assertEquals(SelectionRect(115, 200, 150, 370), sorted[1])

        // Verify subline selection from col1 start to col2Bot end covers everything
        val selected = calculateSubLineSelectionRects(
            sorted,
            TextHandleState(index = 0, offsetRatio = 0.0f),
            TextHandleState(index = 1, offsetRatio = 1.0f)
        )
        assertEquals(2, selected.size)
        assertEquals(col1, selected[0])
        assertEquals(SelectionRect(115, 200, 150, 370), selected[1])
    }

    @Test
    fun sortSelectionRects_slantedHorizontalRow_mergesIntoSingleRow() {
        val rowLeft = SelectionRect(100, 100, 300, 130)
        val rowRight = SelectionRect(305, 115, 500, 145) // 15px Y-shift due to tilt

        val sorted = sortSelectionRects(listOf(rowRight, rowLeft))

        assertEquals(1, sorted.size)
        assertEquals(SelectionRect(100, 100, 500, 145), sorted[0])
    }
}
