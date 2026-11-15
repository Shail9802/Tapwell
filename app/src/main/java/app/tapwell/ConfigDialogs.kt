package app.tapwell

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
internal fun DailyLimitDialog(activity: MainActivity, target: String, dismiss: () -> Unit) {
    val store = activity.store
    var value by rememberSaveable(target) { mutableStateOf(store.dailyLimit(target).toString()) }
    val minutes = value.toIntOrNull()?.takeIf { it in 0..1440 }
    val revision = activity.permissionRevision
    val allowed = remember(revision) { FocusUsage.allowed(activity) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Daily limit for ${targetLabel(activity, target)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tapwell counts Android foreground usage and blocks access once the limit is used. Enter the same number of minutes as your system timer if you want the limits to match.")
                if (target == SHORTS) Text("This uses total YouTube time.")
                NumberField(value, { value = it }, "Minutes per day", "0 turns off Tapwell’s daily limit")
                if (!allowed) {
                    TextButton(onClick = { activity.openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                        Text("Open Usage access")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = minutes != null && (minutes == 0 || allowed), onClick = {
                dismiss()
                if (minutes != null && minutes != store.dailyLimit(target)) {
                    if (store.hasTags(TagRole.FOCUS.slot)) {
                        activity.scanRole(TagRole.FOCUS, "Verify Focus tag", "Scan your tag to change this daily limit.") {
                            store.saveDailyLimit(target, minutes)
                        }
                    } else store.saveDailyLimit(target, minutes)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun MealSnoozeDialog(activity: MainActivity, dismiss: () -> Unit) {
    val store = activity.store
    var windows by remember { mutableStateOf(Meal.entries.associateWith(store::mealSnooze)) }
    var editing by remember { mutableStateOf<Pair<Meal, Boolean>?>(null) }
    val valid = windows.values.all { !it.enabled || it.endMinute > it.startMinute }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Meal snooze") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Due reminders wait until the meal ends. You can still log a habit during a meal.")
                for (meal in Meal.entries) {
                    val window = windows.getValue(meal)
                    ToggleRow(meal.title, window.enabled) { windows = windows + (meal to window.copy(enabled = it)) }
                    if (window.enabled) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = meal to true }) { Text("Starts ${clockLabel(window.startMinute)}") }
                            TextButton(onClick = { editing = meal to false }) { Text("Ends ${clockLabel(window.endMinute)}") }
                        }
                    }
                }
                if (!valid) Text("End time must be after start time.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                store.saveMealSnoozes(windows)
                Reminders.reconcile(activity)
                dismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
    editing?.let { (meal, start) ->
        val window = windows.getValue(meal)
        ClockDialog(
            if (start) "${meal.title} starts" else "${meal.title} ends",
            if (start) window.startMinute else window.endMinute,
            dismiss = { editing = null }
        ) { minute ->
            windows = windows + (meal to if (start) window.copy(startMinute = minute) else window.copy(endMinute = minute))
            editing = null
        }
    }
}

@Composable
internal fun QuietHoursDialog(activity: MainActivity, dismiss: () -> Unit) {
    val store = activity.store
    var hours by remember { mutableStateOf(store.quietHours()) }
    var editingStart by remember { mutableStateOf<Boolean?>(null) }
    val valid = hours.startMinute != hours.endMinute
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Sleep quiet hours") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Habit notifications, pop-ups, NFC prompts, and repeat counts pause overnight. A due habit reminds you once quiet hours end.")
                ToggleRow("Pause overnight", hours.enabled) { hours = hours.copy(enabled = it) }
                if (hours.enabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editingStart = true }) { Text("Starts ${clockLabel(hours.startMinute)}") }
                        TextButton(onClick = { editingStart = false }) { Text("Ends ${clockLabel(hours.endMinute)}") }
                    }
                }
                if (!valid) Text("Choose different start and end times.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                store.saveQuietHours(hours)
                Reminders.reconcile(activity)
                dismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
    editingStart?.let { start ->
        ClockDialog(
            if (start) "Quiet hours start" else "Quiet hours end",
            if (start) hours.startMinute else hours.endMinute,
            dismiss = { editingStart = null }
        ) { minute ->
            hours = if (start) hours.copy(startMinute = minute) else hours.copy(endMinute = minute)
            editingStart = null
        }
    }
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
    var repeatSeconds by rememberSaveable { mutableStateOf<Int?>((settings.repeatMillis / 1000).toInt()) }
    var intervalSeconds by rememberSaveable { mutableStateOf<Int?>((settings.intervalMillis / 1000).toInt()) }
    var goalMl by rememberSaveable { mutableStateOf<Int?>(settings.goalMl) }
    var servingMl by rememberSaveable { mutableStateOf<Int?>(settings.servingMl) }
    val unitContext = androidx.compose.ui.platform.LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var extraReminder by remember { mutableStateOf(false) }
    val valid = title.isNotBlank() && intervalSeconds != null && goalMl != null && servingMl != null &&
        (!repeats || repeatSeconds != null)
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
                Modifier.animateContentSize().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
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
                    Box {
                        TextButton(onClick = { extraReminder = true }) { Text("Use another reminder app") }
                        DropdownMenu(extraReminder, { extraReminder = false }) {
                            for ((label, open) in systemReminders) DropdownMenuItem(text = { Text(label) }, onClick = { extraReminder = false; open() })
                        }
                    }
                }
                if (custom) ToggleRow("Require NFC tag", needsNfc) { needsNfc = it }
                if (needsNfc && !paired) {
                    Text(
                        if (custom) "Save, then pair a tag on the habit card to start reminders."
                        else "Pair your ${habit?.tagLabel} tag to turn on reminders.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                DurationInput((settings.intervalMillis / 1000).toInt(), "Remind after") { intervalSeconds = it }
                if (habit == Habit.WATER) {
                    QuantityInput(settings.goalMl, "Daily water goal", volumeUnits(unitContext), 1, 20000) { goalMl = it }
                    QuantityInput(settings.servingMl, "Amount per drink", volumeUnits(unitContext), 1, 10000) { servingMl = it }
                }
                ToggleRow("Repeat until done", repeats) { repeats = it }
                if (repeats) {
                    DurationInput((settings.repeatMillis / 1000).toInt(), "Repeat every") { repeatSeconds = it }
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
                if (intervalSeconds != null && goalMl != null && servingMl != null) {
                    save(title.trim(), HabitSettings(
                        enabled = enabled, minutes = ((intervalSeconds ?: 3600) / 60).coerceAtLeast(1), goalMl = goalMl!!, servingMl = servingMl!!,
                        needsNfc = needsNfc, repeats = repeats, retryMinutes = ((repeatSeconds ?: 300) / 60).coerceIn(1, 1440),
                        intervalSeconds = intervalSeconds, repeatSeconds = repeatSeconds
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
    var radius by rememberSaveable { mutableStateOf<Int?>(settings.radiusMeters) }
    val unitContext = androidx.compose.ui.platform.LocalContext.current
    var latitude by rememberSaveable { mutableStateOf(settings.latitude) }
    var longitude by rememberSaveable { mutableStateOf(settings.longitude) }
    var working by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var editingTime by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val minutes = minutesText.toIntOrNull()?.takeIf { it in 1..120 }
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
                QuantityInput(settings.radiusMeters, "Work radius", distanceUnits(unitContext), 100, 5000) { radius = it }
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
                    save(WorkSettings(enabled, days, start, end, minutes, latitude, longitude, radius!!))
                }
            }, enabled = valid && !working) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
    editingTime?.let { label ->
        ClockDialog("$label of work window", if (label == "Start") start else end, dismiss = { editingTime = null }) {
            if (label == "Start") start = it else end = it
            editingTime = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClockDialog(label: String, minute: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val time = rememberTimePickerState(minute / 60, minute % 60, is24Hour = uses24Hours(context))
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(label) },
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

@Composable
private fun clockLabel(minutes: Int): String = clockText(androidx.compose.ui.platform.LocalContext.current, minutes)
