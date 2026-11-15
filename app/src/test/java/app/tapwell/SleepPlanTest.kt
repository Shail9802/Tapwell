package app.tapwell

import java.time.ZoneId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepPlanTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun night(hour: Int, minute: Int, length: Int, rested: Int): SleepNight {
        val start = LocalDate.of(2026, 11, 14).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        return SleepNight(start, start + length * MINUTE, rested)
    }
    @Test fun coldStartUsesAgeAppropriateDuration() {
        assertEquals(540, suggestSleep(16, 420, emptyList(), zone).duration)
        assertEquals(480, suggestSleep(25, 420, emptyList(), zone).duration)
        assertEquals(23 * 60, suggestSleep(25, 420, emptyList(), zone).bedtime)
    }
    @Test fun shortNightsCannotTeachAnUnsafeDurationTarget() {
        val nights = List(10) { night(2, 0, 300, 5) }
        assertEquals(480, suggestSleep(25, 420, nights, zone).duration)
        assertEquals(0, suggestSleep(25, 420, nights, zone).samples)
    }
    @Test fun bedtimeAdvanceCrossesMidnightInSmallSteps() {
        val nights = List(4) { night(0, 10, 480, 4) }
        assertEquals(23 * 60 + 55, suggestSleep(25, 420, nights, zone).bedtime)
        assertEquals(4, suggestSleep(25, 420, nights, zone).samples)
    }
}
