package app.tapwell

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val MINUTE = 60_000L
internal const val DEFAULT_RETRY_MINUTES = 5
internal const val REMOVAL_DELAY = 120 * MINUTE
internal const val YOUTUBE = "com.google.android.youtube"
internal const val SHORTS = "youtube-shorts"
internal const val INSTAGRAM = "com.instagram.android"

internal enum class TagRole(val title: String) {
    FOCUS("Focus"), WATER("Water"), STAND("Stand");

    val slot: TagSlot get() = TagSlot("tag_$name", title)
}

internal data class TagSlot(val key: String, val title: String, val habitId: String? = null)
internal data class PairedTag(val id: String, val label: String)

private val unnamedTag = Regex("Tag [0-9]+")

internal fun tagScanMessage(slot: TagSlot, label: String?): String {
    val name = label?.trim()?.takeUnless { it.isEmpty() || it == "Original tag" || unnamedTag.matches(it) }
        ?: slot.title
    return if (name.endsWith(" tag", ignoreCase = true)) "$name Scanned" else "$name Tag Scanned"
}

internal data class ScannedTag(val uid: String?, val code: String? = null) {
    val identity: String? get() = code ?: uid

    fun matches(ids: Set<String>): Boolean = uid in ids || code in ids
}

internal const val TAGFOLD_PREFIX = "tapwell-tag:"
private val tagfoldCode = Regex("tapwell-tag:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

internal fun tagfoldIdentity(text: String): String? =
    tagfoldCode.matchEntire(text)?.groupValues?.get(1)?.lowercase()?.let { TAGFOLD_PREFIX + it }

internal data class Habit(val id: String, val title: String, val role: TagRole? = null) {
    val tagLabel: String get() = role?.title ?: title
    val tagSlot: TagSlot get() = role?.slot ?: TagSlot("${id}_tag", title, id)

    companion object {
        val WATER = Habit("WATER", "Water", TagRole.WATER)
        val STAND = Habit("STAND", "Stand up", TagRole.STAND)
        val builtIns = listOf(WATER, STAND)
    }
}

internal data class HabitSettings(
    val enabled: Boolean = false,
    val minutes: Int = 60,
    val goalMl: Int = 1000,
    val servingMl: Int = 250,
    val needsNfc: Boolean = true,
    val repeats: Boolean = true,
    val retryMinutes: Int = DEFAULT_RETRY_MINUTES
)

internal data class HabitState(
    val day: LocalDate,
    val loggedMl: Int = 0,
    val dueAt: Long = 0,
    val sentAt: Long = 0
)

internal data class WorkSettings(
    val enabled: Boolean = false,
    val days: Int = 31,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60,
    val minutes: Int = 10,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = 250
)

internal data class WorkWindow(val day: LocalDate, val endsAt: Long)

internal fun workWindow(settings: WorkSettings, now: Long, zone: ZoneId): WorkWindow? {
    if (!settings.enabled || settings.startMinute == settings.endMinute) return null
    val instant = Instant.ofEpochMilli(now)
    val today = instant.atZone(zone).toLocalDate()
    for (day in listOf(today, today.minusDays(1))) {
        if (settings.days and (1 shl (day.dayOfWeek.value - 1)) == 0) continue
        val endDay = if (settings.endMinute < settings.startMinute) day.plusDays(1) else day
        val start = day.atStartOfDay().plusMinutes(settings.startMinute.toLong()).atZone(zone).toInstant()
        val end = endDay.atStartOfDay().plusMinutes(settings.endMinute.toLong()).atZone(zone).toInstant()
        if (instant >= start && instant < end) return WorkWindow(day, end.toEpochMilli())
    }
    return null
}

internal fun localDay(now: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

internal fun nextMidnight(now: Long, zone: ZoneId): Long =
    localDay(now, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

internal fun refreshHabit(
    habit: Habit,
    settings: HabitSettings,
    state: HabitState,
    now: Long,
    zone: ZoneId
): HabitState {
    val today = localDay(now, zone)
    if (!settings.enabled) return HabitState(today, if (state.day == today) state.loggedMl else 0)
    val reset = if (habit == Habit.WATER && today != state.day) {
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        HabitState(today, dueAt = midnight + settings.minutes * MINUTE)
    } else state
    if (habit == Habit.WATER && reset.loggedMl >= settings.goalMl) {
        return reset.copy(dueAt = 0, sentAt = 0)
    }
    return if (reset.dueAt == 0L) reset.copy(dueAt = now + settings.minutes * MINUTE) else reset
}

internal fun completeHabit(habit: Habit, settings: HabitSettings, state: HabitState, now: Long): HabitState {
    val logged = if (habit == Habit.WATER) state.loggedMl + settings.servingMl else 0
    val due = if (habit == Habit.WATER && logged >= settings.goalMl) 0 else now + settings.minutes * MINUTE
    return state.copy(loggedMl = logged, dueAt = due, sentAt = 0)
}

internal fun acceptsCompletion(settings: HabitSettings, pairedTags: Set<String>, scannedTag: ScannedTag?): Boolean =
    if (settings.needsNfc) scannedTag?.matches(pairedTags) == true else scannedTag == null

internal fun reminderTrigger(settings: HabitSettings, state: HabitState): Long = when {
    state.sentAt == 0L -> state.dueAt
    settings.repeats -> state.sentAt + settings.retryMinutes * MINUTE
    else -> 0
}

internal enum class Meal(val title: String, val startMinute: Int, val endMinute: Int) {
    LUNCH("Lunch", 12 * 60 + 30, 14 * 60),
    DINNER("Dinner", 19 * 60 + 30, 21 * 60 + 30)
}

internal data class MealSnooze(val enabled: Boolean, val startMinute: Int, val endMinute: Int)

internal fun snoozedUntil(windows: List<MealSnooze>, at: Long, zone: ZoneId): Long {
    val local = Instant.ofEpochMilli(at).atZone(zone)
    val date = local.toLocalDate()
    return windows.filter { it.enabled }.mapNotNull { window ->
        val start = date.atTime(window.startMinute / 60, window.startMinute % 60).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(window.endMinute / 60, window.endMinute % 60).atZone(zone).toInstant().toEpochMilli()
        end.takeIf { at >= start && at < end }
    }.maxOrNull() ?: 0L
}

internal fun deferReminder(trigger: Long, now: Long, windows: List<MealSnooze>, zone: ZoneId): Long =
    if (trigger == 0L) 0L else maxOf(trigger, snoozedUntil(windows, maxOf(trigger, now), zone))

internal enum class ActivityCount {
    FOCUS_PROMPT, FOCUS_LEFT, FOCUS_OPENED, HABIT_ALERT, HABIT_DONE, HABIT_EARLY
}

internal fun hasPendingAlert(state: HabitState, now: Long): Boolean =
    state.sentAt > 0 && state.dueAt in 1..now

internal fun reminderWake(habit: Habit, trigger: Long, now: Long, zone: ZoneId): Long {
    if (habit != Habit.WATER) return trigger
    val midnight = nextMidnight(now, zone)
    return if (trigger == 0L) midnight else minOf(midnight, trigger)
}

internal data class Deadline(val wall: Long, val elapsed: Long, val boot: Int) {
    fun remaining(now: Long, uptime: Long, bootCount: Int, surviveReboot: Boolean): Long =
        when {
            boot == bootCount -> (elapsed - uptime).coerceAtLeast(0)
            surviveReboot -> (wall - now).coerceIn(0, REMOVAL_DELAY)
            else -> 0
        }
}

internal fun isShortsPlayerId(id: String): Boolean = id in shortsPlayerIds

private val shortsPlayerIds = setOf(
    "reel_watch_fragment",
    "reel_watch_fragment_root",
    "reel_player_page",
    "reel_player_page_container",
    "reel_player_view",
    "reel_player_view_container",
    "reel_player_view_overlay",
    "reel_recycler",
    "reel_recycler_view",
    "reel_player",
    "shorts_player_page_container",
    "shorts_player"
)
