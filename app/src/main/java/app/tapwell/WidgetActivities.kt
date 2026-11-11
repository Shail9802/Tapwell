package app.tapwell

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class HabitWidgetConfiguration : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        if (manager.getAppWidgetInfo(id)?.provider?.className != HabitWidgetProvider::class.java.name) {
            finish()
            return
        }
        val habits = Store.get(this).habits()
        val settings = HabitWidgetSettings(this)
        val available = habits.map { it.id }.toSet()
        val initial = settings.selected(id)?.filter { it in available } ?: habits.map { it.id }
        setContent {
            TapTheme {
                var selected by rememberSaveable { mutableStateOf(ArrayList(initial)) }
                var saving by remember { mutableStateOf(false) }
                Surface {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Choose widget habits", style = MaterialTheme.typography.headlineSmall)
                        Text("Resize to show more habits. Swipe up or down for extra pages. NFC habits require a tag, and every log asks for confirmation.")
                        Text("You can log with reminders off. Long-press the widget and choose Edit to change this selection.")
                        LazyColumn(Modifier.weight(1f)) {
                            items(habits, key = { it.id }) { habit ->
                                val checked = habit.id in selected
                                val needsNfc = Store.get(this@HabitWidgetConfiguration).habitSettings(habit).needsNfc
                                Row(
                                    Modifier.toggleable(checked, role = Role.Checkbox) {
                                        selected = ArrayList(if (checked) selected - habit.id else selected + habit.id)
                                    }.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Checkbox(checked, onCheckedChange = null)
                                    Column {
                                        Text(habit.title, style = MaterialTheme.typography.titleMedium)
                                        Text(if (needsNfc) "Scan a paired tag to log" else "Tap to log")
                                    }
                                }
                            }
                        }
                        Button(
                            enabled = selected.isNotEmpty() && !saving,
                            onClick = {
                                saving = true
                                settings.save(id, selected, manager.getAppWidgetOptions(id))
                                lifecycleScope.launch {
                                    TapwellWidgets.updateAll(this@HabitWidgetConfiguration)
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }
                            }
                        ) { Text("Save widget") }
                        TextButton(onClick = ::finish) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

internal class WidgetHabitActivity : TapActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val habitId = intent.getStringExtra("habit")
        val manager = AppWidgetManager.getInstance(this)
        if (manager.getAppWidgetInfo(id)?.provider?.className != HabitWidgetProvider::class.java.name ||
            habitId !in HabitWidgetSettings(this).selected(id).orEmpty()) {
            finish()
            return
        }
        val habit = habitId?.let(store::habit)
        if (habit == null) {
            notice("This habit was removed. Edit the widget to choose another.")
            finish()
            return
        }
        fun confirm(tag: ScannedTag?) {
            if (store.canConfirmHabitLog(habit, tag, manualTracking = true)) {
                HabitTapActivity.prompt(this, habit, tag, manualTracking = true)
                finish()
            } else notice("Pair a tag for this habit in Tapwell.")
        }
        fun startLog() {
            if (!store.habitSettings(habit).needsNfc) confirm(null)
            else if (!store.hasTags(habit.tagSlot)) {
                notice("Pair a ${habit.tagLabel} tag in Tapwell first.")
                finish()
            } else scan(habit.tagSlot, "Scan ${habit.tagLabel} tag", "Scan any tag paired to this habit to log it.") { tag ->
                if (!store.matches(habit.tagSlot, tag)) "This tag is not paired to ${habit.title}."
                else {
                    confirm(tag)
                    null
                }
            }
        }
        setContent {
            TapTheme {
                Surface {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(habit.title, style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = ::startLog) { Text("Scan tag") }
                        TextButton(onClick = ::finish) { Text("Close") }
                    }
                }
                Scanner()
            }
        }
        startLog()
    }
}
