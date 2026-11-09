package app.tapwell

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AboutDialog(activity: MainActivity, dismiss: () -> Unit) {
    val version = remember(activity) {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
    }
    fun openGitHub(path: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$path")))
        } catch (_: ActivityNotFoundException) {
            activity.notice("A browser is needed to open GitHub.")
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { TapIcon(Symbol.BRAND) },
        title = { Text("About Tapwell") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Developed with love ❤️ by Shail")
                version?.let { Text("Version $it", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { openGitHub("Shail9802") }) { Text("Shail on GitHub") }
                TextButton(onClick = { openGitHub("Shail9802/Tapwell") }) { Text("View source") }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

@Composable
internal fun DurationDialog(value: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(value.toString()) }
    val minutes = input.toIntOrNull()?.takeIf { it in 1..120 }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("NFC unlock duration") },
        text = { NumberField(input, { input = it }, "Minutes", "1–120 minutes per scan") },
        confirmButton = { TextButton(onClick = { minutes?.let(save) }, enabled = minutes != null) { Text("Continue") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun HabitDialog(
    habit: Habit?,
    settings: HabitSettings,
    paired: Boolean,
    dismiss: () -> Unit,
    delete: () -> Unit,
    test: () -> Unit,
    systemReminders: Map<String, () -> Unit>,
    save: (String, HabitSettings) -> Unit
) {
    val custom = habit?.role == null
    var title by rememberSaveable { mutableStateOf(habit?.title.orEmpty()) }
    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var needsNfc by rememberSaveable { mutableStateOf(settings.needsNfc) }
    var repeats by rememberSaveable { mutableStateOf(settings.repeats) }
    var retry by rememberSaveable { mutableStateOf(settings.retryMinutes.toString()) }
    var interval by rememberSaveable { mutableStateOf(settings.minutes.toString()) }
    var goal by rememberSaveable { mutableStateOf(settings.goalMl.toString()) }
    var serving by rememberSaveable { mutableStateOf(settings.servingMl.toString()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val maxInterval = if (custom) 10080 else 720
    val minutes = interval.toIntOrNull()?.takeIf { it in 1..maxInterval }
    val retryMinutes = retry.toIntOrNull()?.takeIf { it in 1..1440 }
    val goalMl = goal.toIntOrNull()?.takeIf { it in 1..20000 }
    val servingMl = serving.toIntOrNull()?.takeIf { it in 1..10000 }
    val valid = title.isNotBlank() && minutes != null && goalMl != null && servingMl != null &&
        (!repeats || retryMinutes != null)
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${habit?.title}?") },
            text = { Text("This removes the habit, its reminders, and its tag assignment.") },
            confirmButton = { TextButton(onClick = delete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (habit == null) "Add habit" else if (custom) "Edit habit" else "${habit.title} reminders") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (custom) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(60) },
                        label = { Text("Habit name") },
                        placeholder = { Text("Skin care") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ToggleRow("Reminders on", enabled, custom || paired) { enabled = it }
                if (habit != null) TextButton(onClick = test) { Text("Send test alert") }
                if (systemReminders.isNotEmpty()) {
                    Text("Optional extra reminder", style = MaterialTheme.typography.titleSmall)
                    Text("Tapwell still tracks completion. Save the extra reminder in the app you choose.",
                        style = MaterialTheme.typography.bodySmall)
                    for ((label, open) in systemReminders) TextButton(onClick = open) { Text(label) }
                }
                if (custom) ToggleRow("Require NFC tag", needsNfc) { needsNfc = it }
                if (needsNfc && !paired) {
                    Text(
                        if (custom) "Save, then pair a tag on the habit card to start reminders."
                        else "Pair your ${habit?.tagLabel} tag to turn on reminders.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                NumberField(interval, { interval = it }, "Interval (minutes)", "1–$maxInterval minutes after completion")
                if (habit == Habit.WATER) {
                    NumberField(goal, { goal = it }, "Daily goal (mL)", "Resets at local midnight")
                    NumberField(serving, { serving = it }, "Amount per scan (mL)", "One serving per scan")
                }
                ToggleRow("Repeat until done", repeats) { repeats = it }
                if (repeats) {
                    NumberField(retry, { retry = it }, "Repeat every (minutes)", "1–1,440 minutes after an alert")
                } else {
                    Text("Send one alert and wait for completion.", style = MaterialTheme.typography.bodySmall)
                }
                if (custom && habit != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete habit", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (minutes != null && goalMl != null && servingMl != null) {
                    save(title.trim(), HabitSettings(
                        enabled = enabled, minutes = minutes, goalMl = goalMl, servingMl = servingMl,
                        needsNfc = needsNfc, repeats = repeats, retryMinutes = retryMinutes ?: settings.retryMinutes
                    ))
                }
            }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ReminderAlertsDialog(activity: MainActivity, dismiss: () -> Unit) {
    var chime by remember { mutableStateOf(activity.store.reminderChime()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Reminder alerts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow("Gentle chime", chime) {
                    chime = it
                    activity.store.setReminderChime(it)
                }
                Text("Both modes use a strong three-pulse vibration. Turn off the chime for vibration-only reminders.")
                Text("Android’s Silent and Do Not Disturb settings can mute these alerts.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                activity.testReminder()
            }) { Text("Send test alert") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

@Composable
internal fun WorkDialog(activity: MainActivity, settings: WorkSettings, dismiss: () -> Unit, save: (WorkSettings) -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var days by rememberSaveable { mutableStateOf(settings.days) }
    var start by rememberSaveable { mutableStateOf(settings.startMinute) }
    var end by rememberSaveable { mutableStateOf(settings.endMinute) }
    var minutesText by rememberSaveable { mutableStateOf(settings.minutes.toString()) }
    var radiusText by rememberSaveable { mutableStateOf(settings.radiusMeters.toString()) }
    var latitude by rememberSaveable { mutableStateOf(settings.latitude) }
    var longitude by rememberSaveable { mutableStateOf(settings.longitude) }
    var working by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var editingTime by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val minutes = minutesText.toIntOrNull()?.takeIf { it in 1..120 }
    val radius = radiusText.toIntOrNull()?.takeIf { it in 100..5000 }
    val areaReady = latitude != null && longitude != null
    val valid = minutes != null && radius != null && (!enabled || (days != 0 && start != end && areaReady))
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Work break") },
        text = {
            Column(
                Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ToggleRow("Allow work breaks", enabled) { enabled = it }
                Text("One break per selected work window. Tapwell checks your location when you start it.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, name ->
                        val bit = 1 shl index
                        FilterChip(selected = days and bit != 0, onClick = { days = days xor bit }, label = { Text(name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingTime = "Start" }, modifier = Modifier.weight(1f)) {
                        Text("Start ${clockLabel(start)}")
                    }
                    OutlinedButton(onClick = { editingTime = "End" }, modifier = Modifier.weight(1f)) {
                        Text("End ${clockLabel(end)}")
                    }
                }
                if (end < start) Text("Ends the following day.", style = MaterialTheme.typography.bodySmall)
                if (start == end) Text("Choose different start and end times.", color = MaterialTheme.colorScheme.error)
                NumberField(minutesText, { minutesText = it }, "Break length (minutes)", "1–120 minutes, within work hours")
                NumberField(radiusText, { radiusText = it }, "Work radius (metres)", "100–5,000 metres from the saved point")
                OutlinedButton(
                    onClick = {
                        activity.withPreciseLocation {
                            scope.launch {
                                working = true
                                locationError = null
                                try {
                                    val fix = locate(activity)
                                    if (fix == null || fix.accuracy > 100) {
                                        locationError = "Couldn’t get a precise fix. Check location settings and try again."
                                    } else {
                                        latitude = fix.latitude
                                        longitude = fix.longitude
                                    }
                                } finally {
                                    working = false
                                }
                            }
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (working) "Getting location…" else "Use current location") }
                Text(if (areaReady) "Work location set" else "Set your location while you’re at work.")
                locationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (minutes != null && radius != null) {
                    save(WorkSettings(enabled, days, start, end, minutes, latitude, longitude, radius))
                }
            }, enabled = valid && !working) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
    editingTime?.let { label ->
        ClockDialog(label, if (label == "Start") start else end, dismiss = { editingTime = null }) {
            if (label == "Start") start = it else end = it
            editingTime = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(label: String, minute: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    val time = rememberTimePickerState(minute / 60, minute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("$label of work window") },
        text = { TimeInput(state = time) },
        confirmButton = { TextButton(onClick = { save(time.hour * 60 + time.minute) }) { Text("Set time") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumberField(value: String, change: (String) -> Unit, label: String, detail: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.length <= 5 && input.all(Char::isDigit)) change(input) },
        label = { Text(label) },
        supportingText = { Text(detail) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, enabled = enabled, onCheckedChange = change)
    }
}

private fun clockLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
