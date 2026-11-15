package app.tapwell

import kotlin.math.roundToInt

internal data class HabitWidgetGrid(val columns: Int, val rows: Int) {
    val capacity: Int get() = columns * rows
}

internal fun waterGoalProgress(loggedMl: Int, goalMl: Int): Float =
    if (goalMl <= 0) 0f else (loggedMl.toFloat() / goalMl).coerceIn(0f, 1f)

/** Shrink the grid to its contents so a single habit never occupies just one corner. */
internal fun filledHabitGrid(space: HabitWidgetGrid, count: Int): HabitWidgetGrid {
    if (count <= 0 || count >= space.capacity) return space
    val columns = minOf(space.columns, count)
    return HabitWidgetGrid(columns, (count + columns - 1) / columns)
}

internal fun habitWidgetGrid(width: Float, height: Float, cellWidth: Float, cellHeight: Float): HabitWidgetGrid =
    HabitWidgetGrid(
        ((width + 16) / cellWidth.coerceAtLeast(48f)).roundToInt().coerceIn(1, 6),
        ((height + 16) / cellHeight.coerceAtLeast(48f)).roundToInt().coerceIn(1, 6)
    )
