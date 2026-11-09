package app.tapwell

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager

internal object ReminderAlerts {
    private val timings = longArrayOf(0, 220, 105, 330, 110, 190)
    private val strengths = intArrayOf(0, 255, 0, 255, 0, 235)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun channel(context: Context, manager: NotificationManager, habit: Habit): NotificationChannel {
        val legacyId = if (habit.role == null) "habit_custom" else "habit_${habit.id.lowercase()}"
        val chime = Store.get(context).reminderChime()
        val previousId = "${legacyId}_pulse_${if (chime) "chime" else "quiet"}"
        val previous = manager.getNotificationChannel(previousId)
        if (previous != null && (!chime || previous.hasUserSetSound() || previous.canBypassDnd())) return previous

        val id = if (chime) "${legacyId}_adaptive_chime" else previousId
        manager.getNotificationChannel(id)?.let { return it }

        val legacy = manager.getNotificationChannel(legacyId)
        val title = if (habit.role == null) "Custom habits" else habit.title
        val mode = if (chime) "chime and vibration" else "vibration only"
        val channel = NotificationChannel(
            id, "$title · $mode", previous?.importance ?: legacy?.importance ?: NotificationManager.IMPORTANCE_HIGH
        )
        val sound = when {
            !chime -> null
            previous == null && legacy?.hasUserSetSound() == true -> legacy.sound
            else -> Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority("${context.packageName}.reminder-sound")
                .appendPath("chime").build()
        }
        channel.setSound(sound, audioAttributes)
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        if (previous != null) {
            if (Build.VERSION.SDK_INT >= 35) channel.vibrationEffect = previous.vibrationEffect
            else channel.vibrationPattern = previous.vibrationPattern
            channel.enableVibration(previous.shouldVibrate())
            channel.setShowBadge(previous.canShowBadge())
            channel.lockscreenVisibility = previous.lockscreenVisibility
            channel.enableLights(previous.shouldShowLights())
            channel.lightColor = previous.lightColor
        } else if (Build.VERSION.SDK_INT >= 35 && vibrator.hasAmplitudeControl()) {
            channel.vibrationEffect = VibrationEffect.createWaveform(timings, strengths, -1)
        } else {
            channel.vibrationPattern = timings
        }
        manager.createNotificationChannel(channel)
        return manager.getNotificationChannel(id) ?: channel
    }
}
