package app.tapwell

import kotlin.math.roundToInt

internal data class HabitWidgetGrid(val columns: Int, val rows: Int) {
    val capacity: Int get() = columns * rows
}

internal fun habitWidgetGrid(width: Float, height: Float, cellWidth: Float, cellHeight: Float): HabitWidgetGrid =
    HabitWidgetGrid(
        ((width + 16) / cellWidth.coerceAtLeast(48f)).roundToInt().coerceIn(1, 6),
        ((height + 16) / cellHeight.coerceAtLeast(48f)).roundToInt().coerceIn(1, 6)
    )
