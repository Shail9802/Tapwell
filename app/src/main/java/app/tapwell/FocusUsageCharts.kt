package app.tapwell

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun FocusUsageCharts(activity: MainActivity, targets: Set<String>, dates: List<LocalDate>, now: Long) {
    val revision = activity.permissionRevision
    val allowed = remember(revision) { FocusUsage.allowed(activity) }
    val packages = remember(targets) { targets.map(::focusPackage).toSet() }
    var history by remember(packages, dates, revision) { mutableStateOf<Map<LocalDate, Map<String, Long>>?>(null) }
    var loaded by remember(packages, dates, revision) { mutableStateOf(false) }
    var selected by remember(dates) { mutableStateOf(dates.lastIndex) }
    LaunchedEffect(packages, dates, revision, now / 15_000) {
        if (allowed && packages.isNotEmpty()) {
            history = withContext(Dispatchers.IO) {
                FocusUsage.usageHistory(activity.applicationContext, packages, dates.first(), now)
            }
            loaded = true
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Focus app usage", style = MaterialTheme.typography.titleMedium)
            val usage = history
            when {
                packages.isEmpty() -> Text("Add an app to Focus to see its usage here.")
                !allowed -> {
                    Text("Allow Usage access to see screen time and daily limits.")
                    TextButton(onClick = { activity.openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                        Text("Open Usage access")
                    }
                }
                !loaded -> Text("Loading screen time…")
                usage == null -> Text("Android’s usage data is temporarily unavailable.")
                else -> {
                    if (dates.size > 1) {
                        Text("Daily screen time", style = MaterialTheme.typography.labelLarge)
                        UsageWeek(dates, dates.map { usage[it]?.values?.sum() }, selected) { selected = it }
                        Text(dates[selected].format(usageDay), style = MaterialTheme.typography.labelLarge)
                    }
                    val day = usage[dates[selected]]
                    Text("Usage by app", style = MaterialTheme.typography.labelLarge)
                    when {
                        day == null -> Text("Android has no usage data for this day.")
                        day.values.none { it > 0 } -> Text("No Focus app usage for this day.")
                        else -> UsageDonut(day, label = { targetLabel(activity, it) })
                    }
                    val today = usage[dates.last()].orEmpty()
                    val limits = targets.mapNotNull { target ->
                        activity.store.dailyLimit(target).takeIf { it > 0 }?.let { target to it * MINUTE }
                    }.sortedBy { (target, _) -> targetLabel(activity, target).lowercase() }
                    HorizontalDivider()
                    Text("Today’s Tapwell limits", style = MaterialTheme.typography.labelLarge)
                    if (limits.isEmpty()) Text("Set a daily limit on the Focus tab to track your remaining time.")
                    for ((target, cap) in limits) {
                        LimitBar(targetLabel(activity, target), today[focusPackage(target)] ?: 0L, cap)
                    }
                    if (SHORTS in targets) Text(
                        "The Shorts rule uses time spent in the entire YouTube app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (dates.size > 1) Text(
                        "Past days use Android’s daily totals.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private val usageDay = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val usageWeekday = DateTimeFormatter.ofPattern("EEE")

@Composable
private fun UsageWeek(dates: List<LocalDate>, totals: List<Long?>, selected: Int, select: (Int) -> Unit) {
    val peak = totals.filterNotNull().maxOrNull()?.coerceAtLeast(1) ?: 1
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((index, date) in dates.withIndex()) {
            val used = totals[index]
            val active = index == selected
            Column(
                Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                    .background(if (active) colors.surfaceContainerHigh else Color.Transparent)
                    .selectable(active, role = Role.Tab, onClick = { select(index) })
                    .semantics {
                        contentDescription = "${date.format(usageDay)}, ${used?.let(::usageDuration) ?: "no usage data"}"
                    }.padding(horizontal = 3.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.BottomCenter) {
                    if (used != null && used > 0) {
                        Box(
                            Modifier.fillMaxWidth(0.65f).fillMaxHeight((used.toFloat() / peak).coerceAtLeast(0.03f))
                                .background(if (active) colors.primary else colors.secondary, MaterialTheme.shapes.small)
                        )
                    }
                }
                HorizontalDivider()
                Text(date.format(usageWeekday), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UsageDonut(usage: Map<String, Long>, label: (String) -> String) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primary, colors.tertiary, colors.secondary, colors.error, colors.outline)
    val apps = usage.filterValues { it > 0 }.entries.sortedByDescending { it.value }
    val total = apps.sumOf { it.value }
    val breakdown = apps.take(if (apps.size > palette.size) palette.size - 1 else palette.size)
        .map { label(it.key) to it.value }.toMutableList()
    if (apps.size > palette.size) {
        breakdown += "Other apps" to apps.drop(breakdown.size).sumOf { it.value }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(144.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 13.dp.toPx()
                var angle = -90f
                for ((index, item) in breakdown.withIndex()) {
                    val sweep = 360f * item.second / total
                    drawArc(
                        color = palette[index], startAngle = angle, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke)
                    )
                    angle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(usageDuration(total), style = MaterialTheme.typography.titleMedium)
                Text("Total", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    for ((index, item) in breakdown.withIndex()) {
        val (app, used) = item
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(palette[index], CircleShape))
            Text(app, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(usageDuration(used), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LimitBar(label: String, used: Long, cap: Long) {
    val colors = MaterialTheme.colorScheme
    val left = cap - used
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { (used.toFloat() / cap).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (left > 0) colors.primary else colors.error
        )
        val status = when {
            left > 0 -> "${timeLeft(left)} left"
            left < 0 -> "${usageDuration(-left)} over"
            else -> "Limit reached"
        }
        Text("${usageDuration(used)} used of ${timeLeft(cap)} · $status", style = MaterialTheme.typography.bodySmall)
    }
}

private fun usageDuration(millis: Long): String = when {
    millis <= 0 -> "0 min"
    millis < MINUTE -> "<1 min"
    else -> timeLeft(millis / MINUTE * MINUTE)
}
