package app.tapwell

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val monday = LocalDate.parse("2026-11-09")
    private val morning = Instant.parse("2026-11-09T03:30:00Z").toEpochMilli()

    @Test
    fun widgetsExpandFromOneHabitToRowsAndColumns() {
        assertEquals(1, habitWidgetGrid(64f, 80f, 80f, 96f).capacity)
        assertEquals(2, habitWidgetGrid(144f, 80f, 80f, 96f).capacity)
        assertEquals(4, habitWidgetGrid(144f, 176f, 80f, 96f).capacity)
        assertEquals(8, habitWidgetGrid(304f, 176f, 80f, 96f).capacity)
        assertEquals(1, habitWidgetGrid(0f, 0f, 0f, 0f).capacity)
    }

    @Test
    fun manualWidgetTrackingCountsWaterWithoutSchedulingDisabledReminders() {
        val settings = HabitSettings(enabled = false)
        val state = HabitState(monday, loggedMl = 250)
        val completed = completeHabit(Habit.WATER, settings, state, morning, manualTracking = true)
        assertEquals(500, completed.loggedMl)
        assertEquals(0L, completed.dueAt)
        assertEquals(0L, completed.sentAt)
        val stand = completeHabit(Habit.STAND, settings, HabitState(monday), morning, manualTracking = true)
        assertEquals(0L, stand.dueAt)
        val enabled = completeHabit(Habit.WATER, settings.copy(enabled = true), state, morning, manualTracking = true)
        assertEquals(morning + 60 * MINUTE, enabled.dueAt)
    }

    @Test
    fun foregroundTimeCountsOverlappingActivitiesOnceAndClipsToMidnight() {
        val time = ForegroundTime(1_000)
        time.resume("feed", 500)
        time.resume("video", 1_100)
        time.pause("feed", 1_200)
        time.stop(1_500)
        time.pause("video", 1_550)
        assertEquals(500L, time.used)

        time.resume("feed", 1_600)
        time.pause("feed", 1_800)
        assertEquals(700L, time.used)
    }

    @Test
    fun chimeBoostsLowVolumeAndSoftensMaximumVolume() {
        assertEquals(28, chimeGainHalfDecibels(-55f))
        assertEquals(10, chimeGainHalfDecibels(-20f))
        assertEquals(-8, chimeGainHalfDecibels(0f))
        assertEquals(0, chimeGainHalfDecibels(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun waterSchedulesFourServingsAndRestartsTheNextDay() {
        val settings = HabitSettings(enabled = true)
        var now = morning
        var state = refreshHabit(Habit.WATER, settings, HabitState(monday), now, zone)
        assertEquals(now + 60 * MINUTE, state.dueAt)
        repeat(4) { index ->
            now = state.dueAt
            assertTrue(now > morning)
            state = completeHabit(Habit.WATER, settings, state, now)
            assertEquals((index + 1) * 250, state.loggedMl)
            if (index < 3) assertEquals(now + 60 * MINUTE, state.dueAt)
        }
        assertEquals(0L, state.dueAt)
        val midnight = nextMidnight(now, zone)
        assertEquals(midnight, reminderWake(Habit.WATER, reminderTrigger(settings, state), now, zone))
        state = refreshHabit(Habit.WATER, settings, state, midnight, zone)
        assertEquals(0, state.loggedMl)
        assertEquals(midnight + 60 * MINUTE, state.dueAt)
    }

    @Test
    fun overdueWaterRetriesDoNotCountAsServings() {
        val settings = HabitSettings(enabled = true, minutes = 30, goalMl = 700, servingMl = 250)
        val due = morning + 30 * MINUTE
        val state = HabitState(monday, 500, dueAt = due, sentAt = due)
        val retry = refreshHabit(Habit.WATER, settings, state, due, zone)
        assertEquals(500, retry.loggedMl)
        assertEquals(due + 5 * MINUTE, reminderTrigger(settings, retry))
        val completed = completeHabit(Habit.WATER, settings, retry, due + 2 * MINUTE)
        assertEquals(750, completed.loggedMl)
        assertEquals(0L, completed.dueAt)
        assertEquals(0L, completed.sentAt)
    }

    @Test
    fun waterAlertStaysPendingWithRepeatsOffUntilCompletion() {
        val settings = HabitSettings(enabled = true, repeats = false)
        val state = HabitState(monday, dueAt = morning, sentAt = morning)
        val later = morning + 10 * MINUTE
        assertEquals(0L, reminderTrigger(settings, state))
        assertTrue(hasPendingAlert(state, later))
        assertFalse(hasPendingAlert(completeHabit(Habit.WATER, settings, state, later), later))
        assertFalse(hasPendingAlert(state.copy(sentAt = 0), later))
    }

    @Test
    fun standCompletionRestartsFromTheTapEvenAfterRetries() {
        val settings = HabitSettings(enabled = true, minutes = 45)
        val due = morning + 45 * MINUTE
        val overdue = HabitState(monday, dueAt = due, sentAt = due)
        val tap = due + 2 * MINUTE
        val state = completeHabit(Habit.STAND, settings, overdue, tap)
        assertEquals(tap + 45 * MINUTE, reminderTrigger(settings, state))
        assertEquals(0L, state.sentAt)
    }

    @Test
    fun oneAlertCustomHabitWaitsForCompletionBeforeStartingAnotherInterval() {
        val habit = Habit("skin-care", "Skin care")
        val settings = HabitSettings(enabled = true, minutes = 1440, needsNfc = false, repeats = false)
        val state = refreshHabit(habit, settings, HabitState(monday), morning, zone)
        assertEquals(morning + 1440 * MINUTE, reminderTrigger(settings, state))
        val sent = state.copy(sentAt = state.dueAt)
        val finishedAt = sent.sentAt + 45 * MINUTE
        assertEquals(0L, reminderWake(habit, reminderTrigger(settings, sent), finishedAt, zone))
        val completed = completeHabit(habit, settings, sent, finishedAt)
        assertEquals(finishedAt + 1440 * MINUTE, reminderTrigger(settings, completed))
    }

    @Test
    fun customRetryUsesTheConfiguredDelayAfterTheLastAlert() {
        val settings = HabitSettings(enabled = true, retryMinutes = 12)
        val overdue = HabitState(monday, dueAt = morning - MINUTE, sentAt = morning)
        assertEquals(morning + 12 * MINUTE, reminderTrigger(settings, overdue))
        assertEquals(morning + 3 * MINUTE, reminderTrigger(settings.copy(retryMinutes = 3), overdue))
        assertEquals(0L, reminderTrigger(settings.copy(repeats = false), overdue))
    }

    @Test
    fun nfcHabitAcceptsEitherPairedTagAndManualHabitUsesManualCompletion() {
        val nfc = HabitSettings(needsNfc = true)
        val paired = setOf("home", "work")
        assertFalse(acceptsCompletion(nfc, paired, null))
        assertFalse(acceptsCompletion(nfc, paired, ScannedTag("other")))
        assertFalse(acceptsCompletion(nfc, emptySet(), null))
        assertTrue(acceptsCompletion(nfc, paired, ScannedTag("home")))
        assertTrue(acceptsCompletion(nfc, paired, ScannedTag("work")))
        val manual = nfc.copy(needsNfc = false)
        assertTrue(acceptsCompletion(manual, emptySet(), null))
        assertFalse(acceptsCompletion(manual, emptySet(), ScannedTag("other")))
    }

    @Test
    fun tagfoldCodeMatchesAcrossDifferentEmulatedUids() {
        val code = "tapwell-tag:1fdc6e97-7319-46ad-9b3f-bfc7134b1c25"
        val paired = setOf(code)
        assertTrue(ScannedTag("0102", tagfoldIdentity(code)).matches(paired))
        assertTrue(ScannedTag("0304", tagfoldIdentity(code)).matches(paired))
        assertFalse(ScannedTag("0102", tagfoldIdentity("Home")).matches(paired))
        assertTrue(ScannedTag("0102", tagfoldIdentity(code)).matches(setOf("0102")))
        assertNull(tagfoldIdentity("tapwell-tag:invalid"))
        assertNull(tagfoldIdentity("$code extra text"))
    }

    @Test
    fun tagFeedbackUsesTaskNamesForDefaultLabelsAndKeepsCustomNames() {
        val water = TagRole.WATER.slot
        assertEquals("Water Tag Scanned", tagScanMessage(water, null))
        assertEquals("Water Tag Scanned", tagScanMessage(water, "Tag 1"))
        assertEquals("Water Tag Scanned", tagScanMessage(water, "Original tag"))
        assertEquals("Kitchen Tag Scanned", tagScanMessage(water, "Kitchen"))
        assertEquals("Kitchen Tag Scanned", tagScanMessage(water, "Kitchen Tag"))
    }

    @Test
    fun mealSnoozeDefersNewAndOverdueAlertsWithoutChangingTheirDueTime() {
        val lunch = Meal.LUNCH
        val dinner = Meal.DINNER
        val windows = listOf(MealSnooze(true, lunch.startMinute, lunch.endMinute),
            MealSnooze(true, dinner.startMinute, dinner.endMinute))
        fun at(hour: Int, minute: Int = 0) = monday.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        assertEquals(at(12, 29), deferReminder(at(12, 29), at(12), windows, zone))
        assertEquals(at(14), deferReminder(at(12, 30), at(12), windows, zone))
        assertEquals(at(14), deferReminder(at(12), at(13), windows, zone))
        assertEquals(at(21, 30), deferReminder(at(20), at(19), windows, zone))
        assertEquals(at(21, 30), deferReminder(at(21, 30), at(20), windows, zone))
        assertEquals(0L, deferReminder(0, at(13), windows, zone))
    }

    @Test
    fun earlyDrinkRestartsTheFullIntervalAndCountsOneServing() {
        val settings = HabitSettings(enabled = true, minutes = 60, servingMl = 250)
        val early = morning + 15 * MINUTE
        val state = HabitState(monday, dueAt = morning + 60 * MINUTE)
        val logged = completeHabit(Habit.WATER, settings, state, early)
        assertEquals(250, logged.loggedMl)
        assertEquals(early + 60 * MINUTE, logged.dueAt)
        assertEquals(0L, logged.sentAt)
    }

    @Test
    fun overnightWorkWindowUsesTheDayItStartsAndEndsOnTime() {
        val settings = WorkSettings(enabled = true, days = 1, startMinute = 22 * 60, endMinute = 6 * 60)
        val start = Instant.parse("2026-11-09T16:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-10T00:30:00Z").toEpochMilli()
        assertNull(workWindow(settings, start - 1, zone))
        assertEquals(WorkWindow(monday, end), workWindow(settings, start, zone))
        assertEquals(WorkWindow(monday, end), workWindow(settings, end - 1, zone))
        assertNull(workWindow(settings, end, zone))
        assertNull(workWindow(settings, start + 24 * 60 * MINUTE, zone))
    }

    @Test
    fun removalUsesElapsedTimeAndSurvivesRebootWhilePassesExpire() {
        val deadline = Deadline(wall = morning + REMOVAL_DELAY, elapsed = 100 + REMOVAL_DELAY, boot = 7)
        assertEquals(REMOVAL_DELAY, deadline.remaining(morning + REMOVAL_DELAY, 100, 7, true))
        assertEquals(0L, deadline.remaining(morning, 100 + REMOVAL_DELAY, 7, true))
        assertEquals(60 * MINUTE, deadline.remaining(morning + 60 * MINUTE, 100, 8, true))
        assertEquals(0L, deadline.remaining(morning + 60 * MINUTE, 100, 8, false))
    }

    @Test
    fun shortsPlayerMarkersExcludeOrdinaryPlayerAndShortsShelves() {
        assertTrue(isShortsPlayerId("reel_watch_fragment_root"))
        assertTrue(isShortsPlayerId("reel_player_page_container"))
        assertFalse(isShortsPlayerId("reel_shelf"))
        assertFalse(isShortsPlayerId("reel_time_bar"))
        assertFalse(isShortsPlayerId("player_view"))
    }
}
