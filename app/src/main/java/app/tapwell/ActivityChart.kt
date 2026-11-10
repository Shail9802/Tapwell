package app.tapwell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal data class ChartMetric(val count: ActivityCount, val label: String, val color: Color)

@Composable
internal fun ActivityChart(
    title: String,
    history: Map<LocalDate, Map<String, Map<ActivityCount, Int>>>,
    metrics: List<ChartMetric>
) {
    val dates = history.keys.toList()
    val values = remember(history, metrics) {
        history.values.map { day -> metrics.map { metric -> day.values.sumOf { it[metric.count] ?: 0 } } }
    }
    val peak = values.maxOf { it.max() }.coerceAtLeast(1)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (dates.size == 1) {
                for ((index, metric) in metrics.withIndex()) {
                    val count = values.single()[index]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(metric.label, style = MaterialTheme.typography.bodyMedium)
                            Text(count.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                        Box(
                            Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            Box(Modifier.fillMaxWidth(count.toFloat() / peak).fillMaxHeight().background(metric.color))
                        }
                    }
                }
            } else {
                var selected by remember(dates) { mutableStateOf(dates.lastIndex) }
                val dayLabel = remember { DateTimeFormatter.ofPattern("EEE") }
                val fullLabel = remember { DateTimeFormatter.ofPattern("EEEE, MMM d") }
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for ((index, date) in dates.withIndex()) {
                        val isSelected = index == selected
                        Column(
                            Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                                .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                                .selectable(isSelected, role = Role.Tab, onClick = { selected = index })
                                .semantics {
                                    contentDescription = date.format(fullLabel) + ", " +
                                        metrics.indices.joinToString { "${metrics[it].label} ${values[index][it]}" }
                                }
                                .padding(horizontal = 3.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.fillMaxWidth().height(84.dp)) {
                                Row(
                                    Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    for ((metricIndex, metric) in metrics.withIndex()) {
                                        val height = values[index][metricIndex].toFloat() / peak
                                        Box(
                                            Modifier.weight(1f).fillMaxHeight(height)
                                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                                .background(metric.color)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                            Text(date.format(dayLabel), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                Text(dates[selected].format(fullLabel), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((index, metric) in metrics.withIndex()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(metric.color, CircleShape))
                            Text("${metric.label} ${values[selected][index]}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
