package app.tapwell

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal class SleepStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapwell_sleep", Context.MODE_PRIVATE)
    fun configured() = prefs.contains("age")
    fun age() = prefs.getInt("age", 25)
    fun wake() = prefs.getInt("wake", 7 * 60)
    fun start() = prefs.getLong("start", 0)
    fun quietUntil() = prefs.getLong("quiet_until", 0)
    fun save(age: Int, wake: Int) { prefs.edit().putInt("age", age).putInt("wake", wake).apply() }
    fun nights(): List<SleepNight> = runCatching {
        val array = JSONArray(prefs.getString("nights", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { row ->
            SleepNight(row.getLong("start"), row.getLong("end"), row.getInt("rested"))
        } }
    }.getOrDefault(emptyList())
    fun begin(now: Long, zone: ZoneId) {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var end = today.atStartOfDay().plusMinutes(wake().toLong()).atZone(zone).toInstant().toEpochMilli()
        if (end <= now) end = today.plusDays(1).atStartOfDay().plusMinutes(wake().toLong()).atZone(zone).toInstant().toEpochMilli()
        prefs.edit().putLong("start", now).putLong("quiet_until", end).apply()
    }
    fun extend(now: Long) { prefs.edit().putLong("quiet_until", maxOf(now, quietUntil()) + 60 * MINUTE).apply() }
    fun finish(start: Long, end: Long, rested: Int) {
        require(end > start && end - start <= 24 * 60 * MINUTE && rested in 1..5)
        val array = JSONArray()
        (nights() + SleepNight(start, end, rested)).takeLast(90).forEach {
            array.put(JSONObject().put("start", it.start).put("end", it.end).put("rested", it.rested))
        }
        prefs.edit().putString("nights", array.toString()).remove("start").remove("quiet_until").apply()
    }
    fun cancel() { prefs.edit().remove("start").remove("quiet_until").apply() }
    fun clear() { prefs.edit().clear().apply() }
}

internal fun sleepClock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun parseSleepClock(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

@Composable
internal fun SleepRoutine(activity: MainActivity, now: Long) {
    val sleep = remember { SleepStore(activity) }
    var revision by remember { mutableStateOf(0) }
    var configure by remember { mutableStateOf(!sleep.configured()) }
    var checkIn by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val nights = remember(revision) { sleep.nights() }
    val zone = ZoneId.systemDefault()
    val suggestion = remember(revision) { suggestSleep(sleep.age(), sleep.wake(), nights, zone) }
    Text("Sleep routine", style = MaterialTheme.typography.headlineMedium)
    Text("A steadier rhythm, night by night.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tonight", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Bedtime", style = MaterialTheme.typography.labelMedium); Text(sleepClock(suggestion.bedtime), style = MaterialTheme.typography.headlineLarge) }
                Column { Text("Wake up", style = MaterialTheme.typography.labelMedium); Text(sleepClock(suggestion.wake), style = MaterialTheme.typography.headlineLarge) }
            }
            Text("${suggestion.duration / 60} h ${suggestion.duration % 60} min target · Wind down ${sleepClock(Math.floorMod(suggestion.bedtime - 30, 1440))}", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide plan details" else "About this plan") }
            if (details) {
            Text(if (suggestion.samples < 3) "Starting target for age ${sleep.age()}. Log at least three well-rested nights to personalise duration."
                else "Local nearest-neighbour estimate from ${suggestion.samples} well-rested nights near your chosen wake time. Confidence: limited; confirm how you feel.")
            Text("When moving bedtime earlier, suggestions advance by up to 15 minutes per step. A transition night may still fall short of your duration target.")
            }
            Row {
            TextButton(onClick = { configure = true }) { Text("Edit goal") }
            TextButton(onClick = {
                activity.store.saveQuietHours(QuietHours(true, suggestion.bedtime, suggestion.wake))
                Reminders.reconcile(activity)
                activity.notice("Suggested quiet hours applied")
            }) { Text("Use quiet hours") }
            }
        }
    }
    if (sleep.start() == 0L) {
        Button(onClick = { sleep.begin(now, zone); revision++; Reminders.reconcile(activity) }) { Text("Start bedtime routine") }
        if (activity.store.hasTags(TagRole.FOCUS.slot)) TextButton(onClick = {
            activity.scanRole(TagRole.FOCUS, "Bedtime ritual", "Scan a paired Focus tag to begin quiet hours.") {
                sleep.begin(System.currentTimeMillis(), zone); revision++; Reminders.reconcile(activity)
            }
        }) { Text("Or scan Focus tag") }
    } else {
        Text("Bedtime routine active. This records time in bed, not measured sleep.")
        Button(onClick = { checkIn = true }) { Text("Morning check-in") }
        TextButton(onClick = { sleep.extend(now); revision++; Reminders.reconcile(activity) }) { Text("Keep quiet for another hour") }
        TextButton(onClick = { sleep.cancel(); revision++; Reminders.reconcile(activity) }) { Text("Cancel this session") }
    }
    Text("Last seven nights", style = MaterialTheme.typography.titleLarge)
    val recent = nights.takeLast(7)
    val color = MaterialTheme.colorScheme.primary
    if (recent.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        recent.forEachIndexed { i, night ->
            val width = size.width / 7
            val height = size.height * (night.minutes / 720f).coerceIn(0f, 1f)
            drawRect(color, Offset(i * width, size.height - height), Size(width * .65f, height))
        }
    }
    if (recent.isEmpty()) Text("Your confirmed diary entries will appear here.")
    recent.reversed().forEach {
        Text("${Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate()} · ${it.minutes / 60} h ${it.minutes % 60} min · Rested ${it.rested}/5")
    }
    if (details) Text("Adult baseline: at least 7 hours regularly; teenagers generally need 8–10 hours. Suggestions support routines and do not diagnose sleep problems.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { clear = true }) { Text("Manage sleep data") }
    if (configure) {
        var age by remember { mutableStateOf(sleep.age().toString()) }
        var wake by remember { mutableStateOf(sleepClock(sleep.wake())) }
        val validAge = age.toIntOrNull()?.takeIf { it in 13..100 }
        val validWake = parseSleepClock(wake)
        AlertDialog(onDismissRequest = { configure = false }, title = { Text("Your sleep goal") }, text = {
            Column { OutlinedTextField(age, { age = it }, label = { Text("Age (13–100)") }); OutlinedTextField(wake, { wake = it }, label = { Text("Wake time (HH:mm)") }) }
        }, confirmButton = { TextButton(enabled = validAge != null && validWake != null, onClick = {
            sleep.save(validAge!!, validWake!!); revision++; configure = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { configure = false }) { Text("Cancel") } })
    }
    if (checkIn) {
        var start by remember { mutableStateOf(sleepClock(Instant.ofEpochMilli(sleep.start()).atZone(zone).let { it.hour * 60 + it.minute })) }
        var end by remember { mutableStateOf(sleepClock(Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute })) }
        var rested by remember { mutableStateOf(3) }
        val s = parseSleepClock(start)
        val e = parseSleepClock(end)
        val endDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val endAt = e?.let { endDay.atStartOfDay().plusMinutes(it.toLong()).atZone(zone).toInstant().toEpochMilli() }
        val startAt = s?.let { endDay.minusDays(if (e != null && it >= e) 1 else 0).atStartOfDay().plusMinutes(it.toLong()).atZone(zone).toInstant().toEpochMilli() }
        AlertDialog(onDismissRequest = { checkIn = false }, title = { Text("Confirm your sleep") }, text = {
            Column {
                Text("Correct the estimated times. End date: $endDay. Start is the previous day when it is later than the end time.")
                OutlinedTextField(start, { start = it }, label = { Text("Fell asleep (HH:mm)") })
                OutlinedTextField(end, { end = it }, label = { Text("Woke up (HH:mm)") })
                Text("How rested? $rested/5")
                Slider(rested.toFloat(), { rested = it.toInt() }, valueRange = 1f..5f, steps = 3)
            }
        }, confirmButton = { TextButton(enabled = s != e && startAt != null && endAt != null && endAt > startAt && endAt <= now, onClick = {
            sleep.finish(startAt!!, endAt!!, rested); revision++; checkIn = false; Reminders.reconcile(activity)
        }) { Text("Save night") } }, dismissButton = { TextButton(onClick = { checkIn = false }) { Text("Cancel") } })
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Delete sleep data?") }, text = { Text("Removes the local diary, active session and sleep preferences. Applied quiet hours remain in Setup.") }, confirmButton = {
        TextButton(onClick = { sleep.clear(); revision++; clear = false; Reminders.reconcile(activity) }) { Text("Delete") }
    }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}
