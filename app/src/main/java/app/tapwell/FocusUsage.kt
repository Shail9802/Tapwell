package app.tapwell

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
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
        val used = cached?.used ?: usageToday(context, pkg, now)?.also { samples[pkg] = Sample(now, it) }
            ?: return FocusBudget(0, "Android’s usage data is temporarily unavailable.")
        val left = (minutes * MINUTE - used).coerceAtLeast(0)
        return if (left == 0L) FocusBudget(0, "You’ve used this app’s daily limit. It resets at midnight.")
            else FocusBudget(left)
    }

    private fun usageToday(context: Context, pkg: String, now: Long): Long? {
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
        val active = mutableSetOf<String>()
        var openedAt = 0L
        var used = 0L
        while (events.hasNextEvent() && events.getNextEvent(event)) {
            val wasActive = active.isNotEmpty()
            when {
                event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN ||
                    event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> active.clear()
                event.packageName == pkg -> when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> active.add(event.className.orEmpty())
                    UsageEvents.Event.ACTIVITY_PAUSED -> active.remove(event.className.orEmpty())
                }
            }
            if (!wasActive && active.isNotEmpty()) openedAt = event.timeStamp
            if (wasActive && active.isEmpty()) used += (event.timeStamp - maxOf(start, openedAt)).coerceAtLeast(0)
        }
        if (active.isNotEmpty()) used += (now - maxOf(start, openedAt)).coerceAtLeast(0)
        return used
    }
}

internal fun focusPackage(target: String): String = if (target == SHORTS) YOUTUBE else target
