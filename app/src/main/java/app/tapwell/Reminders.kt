package app.tapwell

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.time.ZoneId

internal object Reminders {
    const val WATER_DISMISSED = "app.tapwell.WATER_DISMISSED"
    const val WATER_TEST_DISMISSED = "app.tapwell.WATER_TEST_DISMISSED"
    const val TEST_EXPIRY = "test_expiry"

    fun exactAllowed(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAllowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    fun reconcile(context: Context) {
        val store = Store.get(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val snoozes = store.mealSnoozes()
        val popups = arrayListOf<String>()
        for (habit in store.habits()) {
            val settings = store.habitSettings(habit)
            val alarm = alarmIntent(context, habit)
            if (!store.habitReady(habit, settings)) {
                alarms.cancel(alarm)
                notifications.cancel(notificationTag(habit), notificationId(habit))
                continue
            }
            val state = store.habitState(habit, now)
            var trigger = deferReminder(reminderTrigger(settings, state), now, snoozes, zone)
            if (state.sentAt == 0L) notifications.cancel(notificationTag(habit), notificationId(habit))
            if (trigger in 1..now) {
                if (notificationsAllowed(context)) {
                    val importance = notify(context, notifications, habit, settings, state)
                    if (importance >= NotificationManager.IMPORTANCE_DEFAULT) popups.add(habit.id)
                    store.markReminderSent(habit, state, now, posted = importance > NotificationManager.IMPORTANCE_NONE)
                    trigger = deferReminder(reminderTrigger(settings, state.copy(sentAt = now)), now, snoozes, zone)
                } else {
                    trigger = now + DEFAULT_RETRY_MINUTES * MINUTE
                }
            } else if (habit == Habit.WATER && hasPendingAlert(state, now) &&
                notificationsAllowed(context) && notifications.activeNotifications.none {
                    it.id == notificationId(Habit.WATER) && it.notification.deleteIntent != null
                }
            ) {
                notify(context, notifications, habit, settings, state, restored = true)
            }
            val next = reminderWake(habit, trigger, now, zone)
            if (next == 0L) {
                alarms.cancel(alarm)
                continue
            }
            try {
                if (alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
                }
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
            }
        }
        if (popups.isNotEmpty()) {
            showPopup(context, notifications, reminderScreen(context).putStringArrayListExtra(ReminderActivity.EXTRA_POPUPS, popups))
        }
    }

    fun test(context: Context, habit: Habit): Boolean =
        postTest(context, habit, SystemClock.elapsedRealtime() + MINUTE, restored = false)

    fun restoreWaterTest(context: Context, expiry: Long) {
        postTest(context, Habit.WATER, expiry, restored = true)
    }

    private fun postTest(context: Context, habit: Habit, expiry: Long, restored: Boolean): Boolean {
        val remaining = expiry - SystemClock.elapsedRealtime()
        if (remaining <= 0) return false
        if (!notificationsAllowed(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = ReminderAlerts.channel(context, manager, habit)
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (Store.get(context).habitSettings(habit).needsNfc) NfcAutomation.enableIfNeeded(context)
        val screen = reminderScreen(context).putExtra(ReminderActivity.EXTRA_TEST_HABIT, habit.id)
        val open = PendingIntent.getActivity(context, TEST_ID, screen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alert = notification(context, channel.id, "Test reminder · ${habit.title}", "Open to try the reminder. Your timer stays as it is.", open)
            .setTimeoutAfter(remaining)
        if (habit == Habit.WATER) {
            val dismiss = Intent(context, ReminderReceiver::class.java).setAction(WATER_TEST_DISMISSED)
                .putExtra(TEST_EXPIRY, expiry)
            keepWaterAlert(alert, open, dismissIntent(context, dismiss), restored)
        } else alert.setAutoCancel(true)
        manager.notify(TEST_ID, alert.build())
        if (channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) showPopup(context, manager, screen)
        return true
    }

    fun cancelTest(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(TEST_ID)
    }

    fun restoreWaterReminder(context: Context) {
        if (!notificationsAllowed(context)) return
        val habit = Habit.WATER
        val store = Store.get(context)
        val settings = store.habitSettings(habit)
        val now = System.currentTimeMillis()
        val state = store.habitState(habit, now)
        if (!store.habitReady(habit, settings) || !hasPendingAlert(state, now)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val importance = notify(context, manager, habit, settings, state, restored = true)
        if (importance >= NotificationManager.IMPORTANCE_DEFAULT &&
            snoozedUntil(store.mealSnoozes(), now, ZoneId.systemDefault()) == 0L
        ) {
            showPopup(context, manager, reminderScreen(context).putExtra(ReminderActivity.EXTRA_HABIT, habit.id))
        }
    }

    private fun showPopup(context: Context, manager: NotificationManager, screen: Intent) {
        if (!Settings.canDrawOverlays(context) || manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        try {
            context.startActivity(screen)
        } catch (_: SecurityException) {
            // The notification still opens the reminder if Android denies the background launch.
        }
    }

    fun cancel(context: Context, habit: Habit) {
        val alarm = alarmIntent(context, habit)
        context.getSystemService(AlarmManager::class.java).cancel(alarm)
        alarm.cancel()
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationTag(habit), notificationId(habit))
    }

    private fun alarmIntent(context: Context, habit: Habit): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode(habit),
        Intent(context, ReminderReceiver::class.java).setAction("app.tapwell.REMIND")
            .setData(habitUri(habit)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notify(
        context: Context,
        manager: NotificationManager,
        habit: Habit,
        settings: HabitSettings,
        state: HabitState,
        restored: Boolean = false
    ): Int {
        val channel = ReminderAlerts.channel(context, manager, habit)
        if (settings.needsNfc && channel.importance != NotificationManager.IMPORTANCE_NONE) {
            NfcAutomation.enableIfNeeded(context)
        }
        val open = PendingIntent.getActivity(
            context, requestCode(habit),
            reminderScreen(context)
                .setData(habitUri(habit))
                .putExtra(ReminderActivity.EXTRA_HABIT, habit.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when (habit) {
            Habit.WATER -> "Time to drink water"
            Habit.STAND -> "Time to stand up"
            else -> habit.title
        }
        val body = when {
            habit == Habit.WATER ->
                "Drink ${settings.servingMl} mL and scan your Water tag · ${state.loggedMl} of ${settings.goalMl} mL today"
            habit == Habit.STAND -> "Stand up, then scan your Stand tag to reset the timer."
            settings.needsNfc -> "Scan your paired tag when you’re done."
            else -> "Open Tapwell to mark this habit done."
        }
        val alert = notification(context, channel.id, title, body, open)
        if (habit == Habit.WATER) {
            val dismiss = Intent(context, ReminderReceiver::class.java).setAction(WATER_DISMISSED)
            keepWaterAlert(alert, open, dismissIntent(context, dismiss), restored)
        }
        manager.notify(notificationTag(habit), notificationId(habit), alert.build())
        return channel.importance
    }

    private fun notification(context: Context, channel: String, title: String, body: String, open: PendingIntent) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_tapwell_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)

    private fun dismissIntent(context: Context, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun keepWaterAlert(
        builder: NotificationCompat.Builder,
        open: PendingIntent,
        dismiss: PendingIntent,
        restored: Boolean
    ) {
        builder.setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(dismiss)
            .addAction(R.drawable.ic_water, "Scan Water tag", open)
        if (restored) builder.setSilent(true)
    }

    private const val TEST_ID = 200

    private fun reminderScreen(context: Context) =
        Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun requestCode(habit: Habit) = if (habit == Habit.STAND) 1 else 0
    private fun notificationId(habit: Habit) = 100 + requestCode(habit)
    private fun notificationTag(habit: Habit): String? = habit.id.takeIf { habit.role == null }
    private fun habitUri(habit: Habit): Uri? = notificationTag(habit)?.let { Uri.fromParts("tapwell-habit", it, null) }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.WATER_DISMISSED -> Reminders.restoreWaterReminder(context)
            Reminders.WATER_TEST_DISMISSED -> Reminders.restoreWaterTest(context, intent.getLongExtra(Reminders.TEST_EXPIRY, 0))
            else -> {
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) migrateLauncherAppearance(context)
                Reminders.reconcile(context)
                if (Store.get(context).waterExportEnabled()) WaterHealth.schedule(context)
            }
        }
    }
}
