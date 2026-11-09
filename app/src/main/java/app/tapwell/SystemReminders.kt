package app.tapwell

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract

private const val SAMSUNG_REMINDER = "com.samsung.android.app.reminder"

internal enum class ReminderDestination(val label: String) {
    SAMSUNG("Share to Samsung Reminder"),
    CALENDAR("Add to calendar");

    private fun intent(): Intent = when (this) {
        SAMSUNG -> Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(SAMSUNG_REMINDER)
        CALENDAR -> Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
    }

    fun available(activity: MainActivity): Boolean = intent().resolveActivity(activity.packageManager) != null

    fun open(activity: MainActivity, habit: Habit) {
        val now = System.currentTimeMillis()
        val store = activity.store
        val settings = store.habitSettings(habit)
        val due = store.habitState(habit, now).dueAt.takeIf { it > now } ?: (now + settings.minutes * MINUTE)
        val detail = if (settings.needsNfc) "Complete in Tapwell with your paired NFC tag."
            else "Complete in Tapwell to restart the habit timer."
        val title = when (habit) {
            Habit.WATER -> "Drink water"
            Habit.STAND -> "Stand up"
            else -> habit.title
        }
        val draft = intent()
        when (this) {
            SAMSUNG -> draft.putExtra(Intent.EXTRA_TEXT, "$title\n$detail")
            CALENDAR -> draft.putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.Events.DESCRIPTION, detail)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, due)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, due + 15 * MINUTE)
        }
        try {
            activity.startActivity(draft)
        } catch (_: ActivityNotFoundException) {
            activity.notice("This reminder app is unavailable.")
        } catch (_: SecurityException) {
            activity.notice("This reminder app cannot receive a draft.")
        }
    }
}
