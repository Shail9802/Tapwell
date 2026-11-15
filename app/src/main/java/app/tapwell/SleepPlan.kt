package app.tapwell

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

internal data class SleepNight(val start: Long, val end: Long, val rested: Int) {
    val minutes: Int get() = ((end - start) / MINUTE).toInt()
}

internal data class SleepSuggestion(val bedtime: Int, val wake: Int, val duration: Int, val samples: Int)

/** Local nearest-neighbour estimate: favour well-rested nights close to the requested wake time. */
internal fun suggestSleep(age: Int, wake: Int, nights: List<SleepNight>, zone: ZoneId): SleepSuggestion {
    val range = when { age < 18 -> 480..600; age >= 65 -> 420..480; else -> 420..540 }
    val valid = nights.filter { it.minutes in range && it.rested >= 4 }
    val neighbours = valid.sortedBy {
        val time = Instant.ofEpochMilli(it.end).atZone(zone).toLocalTime()
        val delta = abs(time.hour * 60 + time.minute - wake)
        minOf(delta, 1440 - delta)
    }.take(7)
    val duration = if (neighbours.size < 3) (if (age < 18) 540 else 480).coerceIn(range)
        else neighbours.map { it.minutes }.sorted()[neighbours.size / 2].coerceIn(range)
    val target = Math.floorMod(wake - duration, 1440)
    val recent = nights.takeLast(7)
    val bedtime = if (recent.size < 3) target else {
        // Unwrap times around noon so 23:55 and 00:05 average near midnight.
        val starts = recent.map {
            val time = Instant.ofEpochMilli(it.start).atZone(zone).toLocalTime()
            Math.floorMod(time.hour * 60 + time.minute - 720, 1440)
        }.sorted()
        val usual = Math.floorMod(starts[starts.size / 2] + 720, 1440)
        val shift = Math.floorMod(target - usual + 720, 1440) - 720
        // Stage an earlier bedtime gradually while keeping the full duration target visible.
        if (shift < 0) Math.floorMod(usual + shift.coerceAtLeast(-15), 1440) else target
    }
    return SleepSuggestion(bedtime, wake, duration, neighbours.size)
}
