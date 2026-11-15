package app.tapwell

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal fun uses24Hours(context: Context): Boolean = when (context.getSharedPreferences("tapwell", 0).getString("clock_format", "system")) {
    "24" -> true
    "12" -> false
    else -> DateFormat.is24HourFormat(context)
}

internal fun clockText(context: Context, minute: Int): String = if (uses24Hours(context)) sleepClock(minute)
    else "%d:%02d %s".format((minute / 60 % 12).takeIf { it != 0 } ?: 12, minute % 60, if (minute < 720) "AM" else "PM")

internal fun durationText(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds % 86400 == 0L -> "${seconds / 86400} day${if (seconds == 86400L) "" else "s"}"
        seconds % 3600 == 0L -> "${seconds / 3600} hr"
        seconds % 60 == 0L -> "${seconds / 60} min"
        else -> "$seconds sec"
    }
}

@Composable
internal fun ClockFormatSetting() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tapwell", 0) }
    var format by remember { mutableStateOf(prefs.getString("clock_format", "system")) }
    Text("Time format", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system" to "Device", "12" to "12-hour", "24" to "24-hour").forEach { (id, label) ->
            FilterChip(format == id, { format = id; prefs.edit().putString("clock_format", id).apply() }, label = { Text(label) })
        }
    }
}

@Composable
internal fun DurationInput(initialSeconds: Int, label: String, changed: (Int?) -> Unit) {
    val units = listOf("Seconds" to 1, "Minutes" to 60, "Hours" to 3600, "Days" to 86400)
    val initialUnit = units.lastOrNull { initialSeconds % it.second == 0 } ?: units.first()
    var multiplier by rememberSaveable { mutableStateOf(initialUnit.second) }
    var value by rememberSaveable { mutableStateOf((initialSeconds / multiplier).toString()) }
    var menu by remember { mutableStateOf(false) }
    fun emit() { changed(value.toLongOrNull()?.let { it * multiplier }?.takeIf { it in 1..604800 }?.toInt()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value, { if (it.length <= 6 && it.all(Char::isDigit)) { value = it; emit() } },
                label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(units.first { it.second == multiplier }.first + " ▾")
                }
                DropdownMenu(menu, { menu = false }) {
                    units.forEach { (name, factor) -> DropdownMenuItem(text = { Text(name) }, onClick = {
                        multiplier = factor; menu = false; emit()
                    }) }
                }
            }
        }
        Text("1 second to 7 days. Android may delay short intervals while idle.", style = MaterialTheme.typography.bodySmall)
    }
}
