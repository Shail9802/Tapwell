package app.tapwell

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

internal data class AmountUnit(val label: String, val base: Double)
internal val metricVolume = listOf(AmountUnit("mL", 1.0), AmountUnit("L", 1000.0))
internal val usVolume = listOf(AmountUnit("US fl oz", 29.5735295625), AmountUnit("US cup", 236.5882365), AmountUnit("US pint", 473.176473), AmountUnit("US gal", 3785.411784))
internal val imperialVolume = listOf(AmountUnit("Imp fl oz", 28.4130625), AmountUnit("Imp pint", 568.26125), AmountUnit("Imp gal", 4546.09))
internal fun defaultUnitSystem(country: String): String = if (country == "US") "us" else "metric"
internal fun amountBase(value: String, unit: AmountUnit, min: Int, max: Int): Int? {
    val raw = value.trim().replace(',', '.').toDoubleOrNull()?.times(unit.base) ?: return null
    return raw.takeIf { it.isFinite() && it >= min && it <= max }?.roundToInt()
}
private fun amountNumber(value: Double): String = "%.6f".format(Locale.US, value).trimEnd('0').trimEnd('.')
internal fun unitSystem(context: Context): String {
    val prefs = context.getSharedPreferences("tapwell", 0)
    val country = prefs.getString("units_country", null) ?: context.resources.configuration.locales[0].country
    return prefs.getString("units_system", null)?.takeUnless { it == "auto" } ?: defaultUnitSystem(country)
}
internal fun volumeUnits(context: Context) = when (unitSystem(context)) { "us" -> usVolume; "imperial" -> imperialVolume; else -> metricVolume }
internal fun waterAmount(context: Context, ml: Int): String {
    val units = volumeUnits(context)
    val unit = if (units == metricVolume && ml >= 1000) metricVolume[1] else units.first()
    val number = if (units == metricVolume) amountNumber(ml / unit.base)
        else "%.2f".format(Locale.US, ml / unit.base).trimEnd('0').trimEnd('.')
    return "$number ${unit.label}"
}
internal fun distanceUnits(context: Context) = if (unitSystem(context) == "metric") listOf(AmountUnit("m", 1.0), AmountUnit("km", 1000.0))
    else listOf(AmountUnit("ft", 0.3048), AmountUnit("mi", 1609.344))

@Composable
internal fun QuantityInput(initial: Int, label: String, units: List<AmountUnit>, min: Int, max: Int, changed: (Int?) -> Unit) {
    val preferred = if (units == metricVolume && initial >= 1000) units[1] else units.first()
    var unitIndex by rememberSaveable { mutableStateOf(units.indexOf(preferred)) }
    var value by rememberSaveable { mutableStateOf(amountNumber(initial / preferred.base)) }
    var menu by remember { mutableStateOf(false) }
    val unit = units[unitIndex]
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value, { if (it.length <= 12 && it.all { c -> c.isDigit() || c == '.' || c == ',' }) {
                value = it; changed(amountBase(it, unit, min, max))
            } }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("${unit.label} ▾") }
                DropdownMenu(menu, { menu = false }) {
                    units.forEachIndexed { index, next -> DropdownMenuItem(text = { Text(next.label) }, onClick = {
                        val base = amountBase(value, unit, min, max)
                        unitIndex = index
                        if (base != null) value = amountNumber(base / next.base)
                        changed(base); menu = false
                    }) }
                }
            }
        }
        if (amountBase(value, unit, min, max) == null) Text("Enter ${amountNumber(min / unit.base)}–${amountNumber(max / unit.base)} ${unit.label}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun RegionalUnitsSetting() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tapwell", 0) }
    var country by remember { mutableStateOf(prefs.getString("units_country", null)) }
    var system by remember { mutableStateOf(prefs.getString("units_system", "auto")) }
    var countries by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val deviceCountry = context.resources.configuration.locales[0].country
    fun name(code: String) = Locale("", code).getDisplayCountry(context.resources.configuration.locales[0])
    UiSection("Region & units") {
    Text("Uses your phone’s region by default. No location permission needed.", style = MaterialTheme.typography.bodySmall)
    PreferenceValue("Country", country?.let(::name) ?: "Device · ${name(deviceCountry)}", { countries = true })
    Box {
        val names = mapOf("auto" to "Regional default", "metric" to "Metric · mL, L, metres", "us" to "US customary · US fl oz, feet", "imperial" to "Imperial · Imp fl oz, feet")
        PreferenceValue("Measurement system", names[system] ?: "Regional default", { menu = true })
        DropdownMenu(menu, { menu = false }) { names.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = {
            system = id; prefs.edit().putString("units_system", id).apply(); menu = false
        }) } }
    }
    }
    if (countries) {
        var search by remember { mutableStateOf("") }
        val codes = remember { Locale.getISOCountries().sortedBy(::name) }
        AlertDialog(onDismissRequest = { countries = false }, title = { Text("Choose your country") }, text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text("Search country") }, singleLine = true)
                TextButton(onClick = { country = null; prefs.edit().remove("units_country").apply(); countries = false }) { Text("Use phone’s region") }
                LazyColumn(Modifier.heightIn(max = 320.dp)) { items(codes.filter { name(it).contains(search, true) || it.equals(search, true) }) { code ->
                    TextButton(onClick = { country = code; prefs.edit().putString("units_country", code).apply(); countries = false }) { Text(name(code)) }
                } }
            }
        }, confirmButton = { TextButton(onClick = { countries = false }) { Text("Close") } })
    }
}
