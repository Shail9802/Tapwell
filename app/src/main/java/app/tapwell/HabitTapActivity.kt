package app.tapwell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

/** Confirms an early or manual log, with a scanned tag when the habit requires one. */
class HabitTapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store.get(this)
        val habit = intent.getStringExtra("habit")?.let(store::habit)
        val tag = ScannedTag(intent.getStringExtra("uid"), intent.getStringExtra("code")).takeIf { it.identity != null }
        if (habit == null || !validRequest() || !store.canConfirmHabitLog(habit, tag)) {
            finish()
            return
        }
        val settings = store.habitSettings(habit)
        val title = when (habit) {
            Habit.WATER -> "Log a drink?"
            Habit.STAND -> "Log a stand break?"
            else -> "Log ${habit.title}?"
        }
        val detail = when {
            habit != Habit.WATER -> "Mark this habit done and restart its reminder interval."
            store.habitState(habit).loggedMl + settings.servingMl >= settings.goalMl -> "Add ${settings.servingMl} mL to today’s total."
            else -> "Add ${settings.servingMl} mL and restart your water reminder interval."
        }
        setContent {
            TapTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(title) },
                    text = { Text(detail) },
                    confirmButton = {
                        TextButton(onClick = {
                            if (isFinishing) return@TextButton
                            if (validRequest() && store.logHabit(habit, tag, confirmedOutsideReminder = true)) {
                                Reminders.reconcile(this)
                                Toast.makeText(this, if (habit == Habit.WATER) "Drink logged" else "Habit logged", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "This request can no longer log the habit.", Toast.LENGTH_SHORT).show()
                            }
                            finish()
                        }) { Text(if (habit == Habit.WATER) "Log drink" else "Log habit") }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } }
                )
            }
        }
    }

    private fun validRequest(): Boolean =
        SystemClock.elapsedRealtime() - intent.getLongExtra("scanned_at", -MINUTE) in 0 until MINUTE

    companion object {
        internal fun prompt(context: Context, habit: Habit, tag: ScannedTag?) {
            context.startActivity(Intent(context, HabitTapActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("habit", habit.id)
                .putExtra("uid", tag?.uid)
                .putExtra("code", tag?.code)
                .putExtra("scanned_at", SystemClock.elapsedRealtime()))
        }
    }
}
