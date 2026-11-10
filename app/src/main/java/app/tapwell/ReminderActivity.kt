package app.tapwell

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal class ReminderActivity : TapActivity() {
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var queue by mutableStateOf<List<String>>(emptyList())
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = savedInstanceState?.getStringArrayList(EXTRA_POPUPS) ?: reminderIds(intent)
        if (queue.isEmpty()) {
            finish()
            return
        }
        setContent { TapTheme { Prompt() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(EXTRA_POPUPS, ArrayList(queue))
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = reminderIds(intent)
        queue = if (intent.hasExtra(EXTRA_HABIT) || intent.hasExtra(EXTRA_TEST_HABIT)) (incoming + queue).distinct()
            else (queue + incoming).distinct()
    }

    override fun onPause() {
        super.onPause()
        unlocked = false
    }

    private fun reminderIds(intent: Intent): List<String> {
        val test = intent.getStringExtra(EXTRA_TEST_HABIT)
        if (test != null && store.habit(test) != null) return listOf(TEST_PREFIX + test)
        return (intent.getStringArrayListExtra(EXTRA_POPUPS) ?: listOfNotNull(intent.getStringExtra(EXTRA_HABIT)))
            .filter { store.habit(it) != null }.distinct()
    }

    private fun next() {
        if (queue.firstOrNull()?.startsWith(TEST_PREFIX) == true) Reminders.cancelTest(this)
        cancelScan()
        if (queue.size < 2) finishAndRemoveTask() else queue = queue.drop(1)
    }

    private fun unlock() {
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { unlocked = true }
        })
    }

    @Composable
    private fun Prompt() {
        val now = liveNow(store)
        val locked = !unlocked && keyguard.isDeviceLocked
        val first = queue.firstOrNull()
        val test = first?.startsWith(TEST_PREFIX) == true
        val habit = first?.removePrefix(TEST_PREFIX)?.let(store::habit)
        val settings = habit?.let(store::habitSettings)
        val state = if (test) null else habit?.let { store.habitState(it, now) }
        val active = habit != null && settings != null &&
            (test || (state != null && store.habitReady(habit, settings) && state.dueAt in 1..now))
        val needsNfc = habit != null && active && settings?.needsNfc == true && store.hasTags(habit.tagSlot)
        val ready = nfcReady()
        LaunchedEffect(habit?.id, needsNfc, locked, ready, test) {
            cancelScan()
            if (habit != null && needsNfc && !locked && ready) log(habit, fromReminder = true, test = test, onSuccess = ::next)
        }
        BackHandler(onBack = ::next)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.54f)).safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        when {
                            test -> "TEST REMINDER"
                            queue.size > 1 -> "HABIT REMINDER · ${queue.size} WAITING"
                            else -> "HABIT REMINDER"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (needsNfc) NfcTapAnimation(Modifier.size(width = 206.dp, height = 134.dp))
                    else IconBadge(Symbol.HABITS)
                    Text(
                        when {
                            locked || habit == null -> "Habit reminder"
                            habit == Habit.WATER -> "Drink water"
                            else -> habit.title
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        when {
                            !active -> "This reminder is no longer due."
                            locked -> "Unlock to view and complete this reminder."
                            needsNfc && !ready -> "Turn on NFC to scan your tag."
                            test && needsNfc -> "Tap any paired tag to test NFC. Your timer stays as it is."
                            test -> "This preview tests the alert. Your habit and timer stay as they are."
                            habit == Habit.WATER -> "Drink ${settings?.servingMl} mL, then hold your phone near your Water tag."
                            habit == Habit.STAND -> "Stand up, then hold your phone near your Stand tag."
                            needsNfc -> "Hold the back of your phone near the paired tag."
                            else -> "Mark this habit done to restart the timer."
                        },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (needsNfc && !locked) scanError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    when {
                        active && locked -> Button(onClick = ::unlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
                        needsNfc && !ready -> Button(
                            onClick = { openSettings(Intent(Settings.ACTION_NFC_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("NFC settings") }
                        needsNfc -> Text(
                            "Ready to scan",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        active && test -> Button(onClick = ::next, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                        active -> Button(
                            onClick = { log(habit, fromReminder = true, onSuccess = ::next) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Mark done") }
                    }
                    TextButton(onClick = ::next, modifier = Modifier.fillMaxWidth()) {
                        Text(when {
                            queue.size > 1 -> "Next reminder"
                            active && !test && habit == Habit.WATER -> "Keep reminder"
                            else -> "Close"
                        })
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_HABIT = "reminder_habit"
        const val EXTRA_POPUPS = "reminder_popups"
        const val EXTRA_TEST_HABIT = "reminder_test_habit"
        private const val TEST_PREFIX = "test:"
    }
}
