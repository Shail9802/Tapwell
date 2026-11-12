package app.tapwell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.text.format.DateUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private const val WATER_PRIVACY = "When you enable export, Tapwell writes the amount and original timestamp of each newly confirmed Water drink to Health Connect on this phone. NFC pairing, reminder previews, and stand breaks are not exported. Tapwell requests only permission to write water intake; it does not read other apps’ health records. Other apps can access exported drinks only if you grant them Health Connect permissions."
private const val WATER_RETENTION = "Water-event history and its queue are excluded from Google Drive settings sync. Export is independent of your Google account. Disconnecting stops future and queued exports without deleting records already in your chosen health app. Local events older than 90 days are pruned when you log another drink; unsent events remain until sent or disconnected. You can export or delete local water-event history below. Daily habit totals are separate; clearing Android app storage removes all local Tapwell data."

@Composable
internal fun WaterHealthDialog(activity: MainActivity, dismiss: () -> Unit) {
    val revision by activity.store.revision.collectAsState()
    val health = activity.waterHealth
    val status by health.status.collectAsState()
    val enabled = remember(revision) { activity.store.waterExportEnabled() }
    val pending = remember(revision) { activity.store.waterEvents().count { it.pending } }
    val lastExport = remember(revision) { activity.store.lastWaterExport() }
    val destination = remember(revision) { activity.store.waterDestination() }
    val name = destination.title
    val permissionRevision = activity.permissionRevision
    LaunchedEffect(permissionRevision) { health.refreshStatus() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Water & health apps") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (enabled && status.permitted) "$name export is on" else "$name export is off",
                    style = MaterialTheme.typography.titleMedium)
                WaterDestination.entries.forEach { option ->
                    FilterChip(selected = destination == option, enabled = !enabled && !status.busy,
                        onClick = { activity.lifecycleScope.launch { health.selectDestination(option) } },
                        label = { Text(option.title) })
                }
                Text("One destination receives new drinks. Disconnect before switching. Previously exported drinks stay in their original destination.")
                if (destination == WaterDestination.SAMSUNG) {
                    Text("Samsung Health: write your confirmed drink volume and timestamp. Read access is used only to check Tapwell’s own record IDs before retrying, to avoid duplicates. No other health data is read.")
                    if (!health.samsungAvailable) Text("Awaiting Samsung partner approval. This build cannot connect to Samsung Health yet.")
                } else Text("Health Connect: write water intake only. Samsung Health shows these entries only if it supports importing hydration.")
                Text("Only new Water drinks confirmed after connection are exported. Old daily totals are not backfilled. Your configured serving size is saved with each drink.")
                if (pending > 0) Text("$pending drinks waiting for their selected destination")
                if (lastExport > 0) Text("Last sent to $name ${DateUtils.getRelativeTimeSpanString(lastExport)}")
                if (status.busy) Text("Working with $name…")
                status.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("Samsung Health", style = MaterialTheme.typography.titleSmall)
                Text(health.samsungHydrationSupport())
                TextButton(onClick = {
                    activity.packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")?.let(activity::openSettings)
                        ?: activity.notice("Samsung Health isn’t installed.")
                }) { Text("Open Samsung Health") }
                TextButton(onClick = { activity.openSettings(health.settingsIntent()) }) { Text("Health Connect settings") }
                TextButton(onClick = { activity.openSettings(Intent(activity, WaterPrivacyActivity::class.java)) }) { Text("Water privacy & permissions") }
                if (enabled) {
                    TextButton(enabled = !status.busy, onClick = { activity.lifecycleScope.launch { health.disconnect() } }) {
                        Text("Disconnect export")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !status.busy && (destination != WaterDestination.SAMSUNG || health.samsungAvailable), onClick = {
                if (enabled && status.permitted) activity.lifecycleScope.launch {
                    if (!health.flush()) WaterHealth.schedule(activity)
                } else activity.connectWaterHealth()
            }) { Text(if (enabled && status.permitted) "Export to $name" else "Connect $name") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

class WaterPrivacyActivity : ComponentActivity() {
    private val exportHistory = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val data = Store.get(this@WaterPrivacyActivity).waterHistoryJson()
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(data) }
                }.isSuccess
            }
            Toast.makeText(this@WaterPrivacyActivity, if (saved) "Water history exported" else "Couldn’t save water history", Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val store = Store.get(this)
            val revision by store.revision.collectAsState()
            val events = remember(revision) { store.waterEvents() }
            var confirmDelete by remember { mutableStateOf(false) }
            var showHistory by remember { mutableStateOf(false) }
            TapTheme {
                Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Water privacy & permissions", style = MaterialTheme.typography.headlineMedium)
                        Text(WATER_PRIVACY)
                        Text(WATER_RETENTION)
                        Text("Samsung Health (when available): a separate opt-in writes Water intake and checks only Tapwell-owned water record IDs for retries. Samsung approval and its water permissions are required. Stand and custom habits are never sent as water. Choose one destination to avoid double exports.")
                        Text("Samsung Health support depends on that app’s available water permissions. Tapwell does not change your Samsung Health account or bypass its access controls.")
                        TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide local water history" else "View local water history (${events.size})") }
                        if (showHistory) {
                            Text("Most recent 100 events. Export JSON for the full retained history.")
                            events.takeLast(100).asReversed().forEach {
                                Text("${Instant.ofEpochMilli(it.time).atOffset(java.time.ZoneOffset.ofTotalSeconds(it.offsetSeconds))} · ${it.millilitres} mL · ${if (it.exported) "Sent to ${it.destination.title}" else if (it.pending) "Queued for ${it.destination.title}" else "Local only"}")
                            }
                        }
                        Text("The exported JSON contains private drink history. Save it only to a location you choose and trust.")
                        TextButton(onClick = { exportHistory.launch("Tapwell-water-history.json") }) { Text("Export local water history") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete local water-event history") }
                        Text("Privacy and grievance contact: Shail Shah · shailmshah.ss@gmail.com. Request access, correction, deletion or report a concern privately; do not post health data in public issues.")
                        TextButton(onClick = {
                            runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:shailmshah.ss@gmail.com?subject=Tapwell%20privacy%20request"))) }
                        }) { Text("Contact privately") }
                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shail9802.github.io/Tapwell/privacy.html"))) }) {
                            Text("Full privacy policy")
                        }
                        TextButton(onClick = ::finish) { Text("Close") }
                    }
                }
                if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete local water-event history?") },
                    text = { Text("Stops export and permanently deletes this phone’s water-event journal and pending queue. Daily habit totals and records already in Health Connect or Samsung Health remain. Clear Android app storage to remove all local Tapwell data.") },
                    confirmButton = { TextButton(onClick = {
                        lifecycleScope.launch { WaterHealth.get(this@WaterPrivacyActivity).clearLocalHistory() }
                        confirmDelete = false
                    }) { Text("Delete history & stop export") } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
            }
        }
    }
}
