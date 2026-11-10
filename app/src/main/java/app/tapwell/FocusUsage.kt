package app.tapwell

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

internal data class FocusBudget(val remaining: Long, val blocked: String? = null, val needsPermission: Boolean = false)

internal object FocusUsage {
    private data class Sample(val at: Long, val used: Long)
    private val samples = mutableMapOf<String, Sample>()

    fun allowed(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
        AppOpsManager.MODE_ALLOWED

    fun budget(context: Context, target: String, minutes: Int, now: Long, fresh: Boolean = false): FocusBudget {
        val pkg = focusPackage(target)
        val suspended = try {
            context.packageManager.isPackageSuspended(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (suspended) return FocusBudget(0, "Android has paused this app.")
        if (minutes == 0) return FocusBudget(Long.MAX_VALUE)
        if (!allowed(context)) return FocusBudget(0, "Allow Usage access to enforce this app’s daily limit.", needsPermission = true)
        val cached = samples[pkg]?.takeIf { !fresh && now - it.at in 0..1_000 }
        val used = cached?.used ?: usageToday(context, setOf(pkg), now)?.get(pkg)?.also { samples[pkg] = Sample(now, it) }
            ?: return FocusBudget(0, "Android’s usage data is temporarily unavailable.")
        val left = (minutes * MINUTE - used).coerceAtLeast(0)
        return if (left == 0L) FocusBudget(0, "You’ve used this app’s daily limit. It resets at midnight.")
            else FocusBudget(left)
    }

    fun usageHistory(
        context: Context, packages: Set<String>, firstDay: LocalDate, now: Long
    ): Map<LocalDate, Map<String, Long>>? {
        if (!allowed(context)) return null
        val zone = ZoneId.systemDefault()
        val today = localDay(now, zone)
        val current = usageToday(context, packages, now) ?: return null
        val history = mutableMapOf<LocalDate, MutableMap<String, Long>>()
        if (firstDay < today) {
            val records = try {
                context.getSystemService(UsageStatsManager::class.java).queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    firstDay.atStartOfDay(zone).toInstant().toEpochMilli(),
                    today.atStartOfDay(zone).toInstant().toEpochMilli()
                )
            } catch (_: SecurityException) {
                return null
            }
            for (record in records.orEmpty()) {
                val date = localDay(record.firstTimeStamp, zone)
                if (date < firstDay || date >= today) continue
                val totals = history.getOrPut(date) { mutableMapOf() }
                if (record.packageName in packages) {
                    totals[record.packageName] = (totals[record.packageName] ?: 0) + record.totalTimeInForeground
                }
            }
        }
        history[today] = current.toMutableMap()
        return history
    }

    private fun usageToday(context: Context, packages: Set<String>, now: Long): Map<String, Long>? {
        val zone = ZoneId.systemDefault()
        val day = localDay(now, zone)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val previousDay = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = try {
            context.getSystemService(UsageStatsManager::class.java).queryEvents(previousDay, now)
        } catch (_: SecurityException) {
            return null
        } ?: return null
        val event = UsageEvents.Event()
        val sessions = packages.associateWith { ForegroundTime(start) }
        while (events.hasNextEvent() && events.getNextEvent(event)) {
            when (event.eventType) {
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                    sessions.values.forEach { it.stop(event.timeStamp) }
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    sessions[event.packageName]?.resume(event.className.orEmpty(), event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    sessions[event.packageName]?.pause(event.className.orEmpty(), event.timeStamp)
            }
        }
        return sessions.mapValues { (_, session) ->
            session.stop(now)
            session.used
        }
    }
}

internal class ForegroundTime(private val midnight: Long) {
    private val active = mutableSetOf<String>()
    private var openedAt = 0L
    var used = 0L
        private set

    fun resume(activity: String, at: Long) {
        if (active.isEmpty()) openedAt = at
        active.add(activity)
    }

    fun pause(activity: String, at: Long) {
        if (active.remove(activity) && active.isEmpty()) addInterval(at)
    }

    fun stop(at: Long) {
        if (active.isEmpty()) return
        addInterval(at)
        active.clear()
    }

    private fun addInterval(at: Long) {
        used += (at - maxOf(midnight, openedAt)).coerceAtLeast(0)
    }
}

internal fun focusPackage(target: String): String = if (target == SHORTS) YOUTUBE else target
