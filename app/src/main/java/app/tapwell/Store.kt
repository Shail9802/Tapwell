package app.tapwell

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal class Store private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("tapwell", Context.MODE_PRIVATE)
    val portableSetup = PortableSetup(prefs)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        changes.value++
        TapwellWidgets.scheduleUpdate(context)
        if (!portableSetup.applying && portablePreference(key) && portableSetup.connected()) {
            SetupSync.scheduleEdit(context)
        }
    }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun reminderChime(): Boolean = prefs.getBoolean("reminder_chime", true)

    fun setReminderChime(enabled: Boolean) {
        prefs.edit().putBoolean("reminder_chime", enabled).apply()
    }

    fun mealSnooze(meal: Meal): MealSnooze {
        val key = "snooze_${meal.name}"
        return MealSnooze(
            prefs.getBoolean("${key}_enabled", false),
            prefs.getInt("${key}_start", meal.startMinute),
            prefs.getInt("${key}_end", meal.endMinute)
        )
    }

    fun mealSnoozes(): List<MealSnooze> = Meal.entries.map(::mealSnooze)

    fun saveMealSnoozes(windows: Map<Meal, MealSnooze>) {
        prefs.edit().apply {
            for ((meal, window) in windows) {
                val key = "snooze_${meal.name}"
                putBoolean("${key}_enabled", window.enabled)
                putInt("${key}_start", window.startMinute)
                putInt("${key}_end", window.endMinute)
            }
        }.apply()
    }

    fun dailyLimit(target: String): Int = prefs.getInt("daily_limit_$target", 0)

    fun saveDailyLimit(target: String, minutes: Int) {
        if (target !in locks() || minutes !in 0..1440) return
        prefs.edit().putInt("daily_limit_$target", minutes).apply()
    }

    fun wallpaperColors(): Boolean = prefs.getBoolean("wallpaper_colors", true)

    fun setWallpaperColors(enabled: Boolean) {
        prefs.edit().putBoolean("wallpaper_colors", enabled).apply()
    }

    fun hasAutomaticLauncherDefault(): Boolean = prefs.getBoolean("automatic_launcher_default", false)

    fun markAutomaticLauncherDefault() {
        prefs.edit().putBoolean("automatic_launcher_default", true).apply()
    }

    fun tags(slot: TagSlot): List<PairedTag> =
        tagEntries(slot).map { PairedTag(it.key, it.value) }.sortedBy { it.label.lowercase() }

    fun hasTags(slot: TagSlot): Boolean = tagEntries(slot).isNotEmpty()

    fun matches(slot: TagSlot, tag: ScannedTag): Boolean = tag.matches(tagEntries(slot).keys)

    fun pairedLabel(slot: TagSlot, tag: ScannedTag): String? {
        val entries = tagEntries(slot)
        return entries[tag.code] ?: entries[tag.uid]
    }

    fun enroll(slot: TagSlot, tag: ScannedTag, label: String, replace: PairedTag?, approval: ScannedTag?): String? {
        if (!slotAvailable(slot)) return "This task no longer accepts NFC tags."
        val id = tag.identity ?: return "This tag doesn’t provide an ID."
        val entries = tagEntries(slot)
        if (entries.isNotEmpty() && approval?.matches(entries.keys) != true) return "Verify a paired tag before adding another."
        if (replace != null && replace.id !in entries) return "This tag is no longer paired."
        if (tag.matches(entries.keys)) return "This tag is already paired here. Tap a different tag."
        val otherSlots = TagRole.entries.map { it.slot } + customHabitIds().map { TagSlot("${it}_tag", "", it) }
        if (otherSlots.any { it.key != slot.key && matches(it, tag) }) return "This tag is paired to another task."
        val updated = entries.toMutableMap()
        replace?.let { updated.remove(it.id) }
        val name = label.trim().ifEmpty { generateSequence(1) { it + 1 }.map { "Tag $it" }.first { it !in updated.values } }
        updated[id] = name
        prefs.edit().putString("${slot.key}_list", JSONObject(updated).toString()).remove(slot.key).apply()
        return null
    }

    fun removeTag(slot: TagSlot, tag: PairedTag, approval: ScannedTag): Boolean {
        val entries = tagEntries(slot)
        if (!slotAvailable(slot) || entries.size <= 1 || tag.id !in entries || !approval.matches(entries.keys)) return false
        prefs.edit().putString("${slot.key}_list", JSONObject(entries - tag.id).toString()).remove(slot.key).apply()
        return true
    }

    private fun tagEntries(slot: TagSlot): Map<String, String> {
        val saved = prefs.getString("${slot.key}_list", null)
        if (saved != null) {
            val entries = JSONObject(saved)
            return entries.keys().asSequence().associateWith(entries::getString)
        }
        val original = prefs.getString(slot.key, null)
        return if (original == null) emptyMap() else mapOf(original to "Original tag")
    }

    private fun slotAvailable(slot: TagSlot): Boolean =
        slot.habitId == null || habit(slot.habitId)?.let { habitSettings(it).needsNfc } == true

    fun locks(now: Long = System.currentTimeMillis()): Set<String> {
        val configured = prefs.getStringSet("locks", setOf(INSTAGRAM, SHORTS)).orEmpty()
        val expired = configured.filter { target ->
            val deadline = deadline("remove_$target")
            deadline != null && remaining(deadline, now, true) == 0L
        }
        if (expired.isEmpty()) return configured.toSet()
        val active = configured - expired.toSet()
        prefs.edit().putStringSet("locks", active).apply {
            expired.forEach { clearDeadline("remove_$it").clearDeadline("pass_$it").remove("daily_limit_$it") }
        }.apply()
        return active
    }

    fun addLock(target: String) {
        val active = locks()
        if (target in active) return
        prefs.edit().putStringSet("locks", active + target)
            .clearDeadline("remove_$target").clearDeadline("pass_$target").remove("daily_limit_$target").apply()
    }

    fun queueRemoval(target: String): Boolean {
        if (target !in locks() || deadline("remove_$target") != null) return false
        prefs.edit().putDeadline("remove_$target", after(REMOVAL_DELAY)).apply()
        return true
    }

    fun cancelRemoval(target: String) {
        if (target in locks()) prefs.edit().clearDeadline("remove_$target").apply()
    }

    fun removalLeft(target: String, now: Long): Long =
        deadline("remove_$target")?.let { remaining(it, now, true) } ?: 0L

    fun unlockMinutes(): Int = prefs.getInt("unlock_minutes", 10)
    fun saveUnlockMinutes(minutes: Int) {
        prefs.edit().putInt("unlock_minutes", minutes).apply()
    }

    fun unlock(target: String) {
        if (target in locks()) {
            prefs.edit().putDeadline("pass_$target", after(unlockMinutes() * MINUTE)).apply()
        }
    }

    fun accessLeft(target: String, now: Long = System.currentTimeMillis()): Long {
        val nfc = deadline("pass_$target")?.let { remaining(it, now, false) } ?: 0L
        return maxOf(nfc, workAccessLeft(now))
    }

    fun work(): WorkSettings = WorkSettings(
        enabled = prefs.getBoolean("work_enabled", false),
        days = prefs.getInt("work_days", 31),
        startMinute = prefs.getInt("work_start", 540),
        endMinute = prefs.getInt("work_end", 1020),
        minutes = prefs.getInt("work_minutes", 10),
        latitude = prefs.getString("work_latitude", null)?.toDoubleOrNull(),
        longitude = prefs.getString("work_longitude", null)?.toDoubleOrNull(),
        radiusMeters = prefs.getInt("work_radius", 250)
    )

    fun saveWork(settings: WorkSettings) {
        prefs.edit()
            .putBoolean("work_enabled", settings.enabled)
            .putInt("work_days", settings.days)
            .putInt("work_start", settings.startMinute)
            .putInt("work_end", settings.endMinute)
            .putInt("work_minutes", settings.minutes)
            .putInt("work_radius", settings.radiusMeters)
            .putString("work_latitude", settings.latitude?.toString())
            .putString("work_longitude", settings.longitude?.toString())
            .clearDeadline("work_pass")
            .apply()
    }

    fun workAccessLeft(now: Long): Long {
        val window = workWindow(work(), now, ZoneId.systemDefault()) ?: return 0
        if (!workBreakUsed(window)) return 0
        val deadline = deadline("work_pass") ?: return 0
        return minOf(window.endsAt - now, remaining(deadline, now, false))
    }

    fun workBreakUsed(window: WorkWindow): Boolean =
        prefs.getString("work_used_day", null) == window.day.toString()

    fun startWorkBreak(verifiedSettings: WorkSettings, now: Long): Boolean {
        if (verifiedSettings != work()) return false
        val window = workWindow(verifiedSettings, now, ZoneId.systemDefault()) ?: return false
        if (workBreakUsed(window)) return false
        val length = minOf(verifiedSettings.minutes * MINUTE, window.endsAt - now)
        prefs.edit().putString("work_used_day", window.day.toString())
            .putDeadline("work_pass", after(length)).apply()
        return true
    }

    private fun customHabitIds(): Set<String> = prefs.getStringSet("custom_habits", emptySet()).orEmpty()

    fun habits(): List<Habit> = Habit.builtIns + customHabitIds().mapNotNull(::habit).sortedBy { it.title.lowercase() }

    fun habit(id: String): Habit? {
        Habit.builtIns.firstOrNull { it.id == id }?.let { return it }
        if (id !in customHabitIds()) return null
        val title = prefs.getString("${id}_title", null) ?: return null
        return Habit(id, title)
    }

    fun habitSettings(habit: Habit): HabitSettings {
        val key = habit.id
        return HabitSettings(
            enabled = prefs.getBoolean("${key}_enabled", false),
            minutes = prefs.getInt("${key}_minutes", 60),
            goalMl = prefs.getInt("${key}_goal", 1000),
            servingMl = prefs.getInt("${key}_serving", 250),
            needsNfc = habit.role != null || prefs.getBoolean("${key}_needs_nfc", false),
            repeats = prefs.getBoolean("${key}_repeats", true),
            retryMinutes = prefs.getInt("${key}_retry_minutes", DEFAULT_RETRY_MINUTES)
        )
    }

    fun habitReady(habit: Habit, settings: HabitSettings): Boolean =
        settings.enabled && (!settings.needsNfc || hasTags(habit.tagSlot))

    fun saveHabit(existing: Habit?, title: String, settings: HabitSettings, now: Long = System.currentTimeMillis()) {
        val habit = if (existing == null) Habit(UUID.randomUUID().toString(), title) else habit(existing.id) ?: return
        val savedSettings = if (habit.role != null) settings.copy(needsNfc = true) else settings
        val old = habitSettings(habit)
        val state = habitState(habit, now)
        val updated = if (state.dueAt > now && old.minutes != settings.minutes) {
            state.copy(dueAt = now + settings.minutes * MINUTE)
        } else state
        val key = habit.id
        val effective = savedSettings.copy(enabled = habitReady(habit, savedSettings))
        prefs.edit().apply {
            if (habit.role == null) {
                putString("${key}_title", title)
                if (existing == null) putStringSet("custom_habits", customHabitIds() + key)
                if (!savedSettings.needsNfc) remove("${key}_tag").remove("${key}_tag_list")
            }
        }.putBoolean("${key}_enabled", savedSettings.enabled)
            .putInt("${key}_minutes", savedSettings.minutes)
            .putInt("${key}_goal", savedSettings.goalMl)
            .putInt("${key}_serving", savedSettings.servingMl)
            .putBoolean("${key}_needs_nfc", savedSettings.needsNfc)
            .putBoolean("${key}_repeats", savedSettings.repeats)
            .putInt("${key}_retry_minutes", savedSettings.retryMinutes)
            .putHabit(habit, refreshHabit(habit, effective, updated, now, ZoneId.systemDefault()))
            .apply()
    }

    fun deleteHabit(habit: Habit) {
        if (habit.role != null) return
        val key = habit.id
        prefs.edit().putStringSet("custom_habits", customHabitIds() - key).apply {
            listOf("title", "tag", "tag_list", "enabled", "minutes", "goal", "serving", "needs_nfc", "repeats",
                "retry_minutes", "day", "logged", "due", "sent", "retry").forEach { remove("${key}_$it") }
        }.apply()
    }

    fun habitState(habit: Habit, now: Long = System.currentTimeMillis()): HabitState {
        val zone = ZoneId.systemDefault()
        val key = habit.id
        val legacyRetry = prefs.getLong("${key}_retry", 0)
        val legacySentAt = (legacyRetry - DEFAULT_RETRY_MINUTES * MINUTE).coerceAtLeast(0)
        val stored = HabitState(
            day = prefs.getString("${key}_day", null)?.let(LocalDate::parse) ?: localDay(now, zone),
            loggedMl = prefs.getInt("${key}_logged", 0),
            dueAt = prefs.getLong("${key}_due", 0),
            sentAt = prefs.getLong("${key}_sent", legacySentAt)
        )
        val settings = habitSettings(habit)
        val state = refreshHabit(habit, settings.copy(enabled = habitReady(habit, settings)), stored, now, zone)
        if (state != stored) prefs.edit().putHabit(habit, state).apply()
        return state
    }

    fun canConfirmHabitLog(
        habit: Habit, tag: ScannedTag?, now: Long = System.currentTimeMillis(), manualTracking: Boolean = false
    ): Boolean {
        val settings = habitSettings(habit)
        if ((!settings.enabled && !manualTracking) || !acceptsCompletion(settings, tagEntries(habit.tagSlot).keys, tag)) return false
        val state = habitState(habit, now)
        return manualTracking || state.dueAt > 0 || habit == Habit.WATER && state.loggedMl >= settings.goalMl
    }

    fun logHabit(
        habit: Habit, tag: ScannedTag?, now: Long = System.currentTimeMillis(), confirmedOutsideReminder: Boolean = false,
        manualTracking: Boolean = false
    ): Boolean {
        val current = habit(habit.id) ?: return false
        val settings = habitSettings(current)
        if ((!settings.enabled && !manualTracking) || !acceptsCompletion(settings, tagEntries(current.tagSlot).keys, tag)) return false
        val state = habitState(current, now)
        if (state.dueAt == 0L && !manualTracking &&
            !(confirmedOutsideReminder && current == Habit.WATER && state.loggedMl >= settings.goalMl)) return false
        val counts = if (now < state.dueAt) listOf(ActivityCount.HABIT_DONE, ActivityCount.HABIT_EARLY)
            else listOf(ActivityCount.HABIT_DONE)
        val next = completeHabit(current, settings, state, now, manualTracking)
        prefs.edit().putHabit(current, next)
            .putActivity(current.id, counts, now).apply()
        return true
    }

    fun markReminderSent(habit: Habit, state: HabitState, now: Long, posted: Boolean) {
        prefs.edit().putHabit(habit, state.copy(sentAt = now)).apply {
            if (posted) putActivity(habit.id, listOf(ActivityCount.HABIT_ALERT), now)
        }.apply()
    }

    fun recordFocus(target: String, count: ActivityCount, now: Long = System.currentTimeMillis()) {
        prefs.edit().putActivity(target, listOf(count), now).apply()
    }

    fun activityCounts(date: LocalDate): Map<String, Map<ActivityCount, Int>> {
        val day = JSONObject(prefs.getString("activity_$date", null) ?: "{}")
        return buildMap {
            for (id in day.keys()) {
                val entry = day.optJSONObject(id) ?: continue
                put(id, buildMap {
                    for (count in ActivityCount.entries) {
                        if (entry.has(count.name)) put(count, entry.optInt(count.name))
                    }
                })
            }
        }
    }

    private fun SharedPreferences.Editor.putActivity(
        id: String, counts: List<ActivityCount>, now: Long
    ): SharedPreferences.Editor {
        val today = localDay(now, ZoneId.systemDefault())
        val key = "activity_$today"
        val json = JSONObject(prefs.getString(key, null) ?: "{}")
        val entry = json.optJSONObject(id) ?: JSONObject()
        for (count in counts) entry.put(count.name, entry.optInt(count.name) + 1)
        if (!prefs.contains(key)) {
            val oldest = "activity_${today.minusDays(6)}"
            prefs.all.keys.filter { it.startsWith("activity_") && it < oldest }.forEach(::remove)
        }
        return putString(key, json.put(id, entry).toString())
    }

    private fun SharedPreferences.Editor.putHabit(habit: Habit, state: HabitState): SharedPreferences.Editor {
        val key = habit.id
        return putString("${key}_day", state.day.toString())
            .putInt("${key}_logged", state.loggedMl)
            .putLong("${key}_due", state.dueAt)
            .putLong("${key}_sent", state.sentAt)
            .remove("${key}_retry")
    }

    private fun after(length: Long) = Deadline(System.currentTimeMillis() + length, SystemClock.elapsedRealtime() + length, boot)

    private fun remaining(deadline: Deadline, now: Long, surviveReboot: Boolean) =
        deadline.remaining(now, SystemClock.elapsedRealtime(), boot, surviveReboot)

    private fun deadline(key: String): Deadline? {
        val wall = prefs.getLong("${key}_wall", 0)
        if (wall == 0L) return null
        return Deadline(wall, prefs.getLong("${key}_elapsed", 0), prefs.getInt("${key}_boot", -1))
    }

    private fun SharedPreferences.Editor.putDeadline(key: String, deadline: Deadline): SharedPreferences.Editor =
        putLong("${key}_wall", deadline.wall).putLong("${key}_elapsed", deadline.elapsed).putInt("${key}_boot", deadline.boot)

    private fun SharedPreferences.Editor.clearDeadline(key: String): SharedPreferences.Editor =
        remove("${key}_wall").remove("${key}_elapsed").remove("${key}_boot")

    companion object {
        private var instance: Store? = null
        fun get(context: Context): Store = instance ?: Store(context.applicationContext).also { instance = it }
    }
}
