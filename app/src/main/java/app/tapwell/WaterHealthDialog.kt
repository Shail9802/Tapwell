package app.tapwell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private const val WATER_PRIVACY = "When you enable export, Tapwell writes the amount and original timestamp of each newly confirmed Water drink to Health Connect on this phone. NFC pairing, reminder previews, and stand breaks are not exported. Tapwell requests only permission to write water intake; it does not read other apps’ health records. Other apps can access exported drinks only if you grant them Health Connect permissions."
private const val WATER_RETENTION = "Water events and their export queue stay on this phone and are excluded from Google Drive settings sync. Export is independent of your Google account. Disconnecting stops future exports and cancels queued exports without deleting existing Health Connect records. Use Health Connect’s Data and access controls to delete records or revoke permission. Tapwell keeps a local event history for up to about 90 days, with unsent queued events retained until sent or disconnected."

@Composable
internal fun WaterHealthDialog(activity: MainActivity, dismiss: () -> Unit) {
    val revision by activity.store.revision.collectAsState()
    val health = activity.waterHealth
    val status by health.status.collectAsState()
    val enabled = remember(revision) { activity.store.waterExportEnabled() }
    val pending = remember(revision) { activity.store.waterEvents().count { it.pending } }
    val lastExport = remember(revision) { activity.store.lastWaterExport() }
    val permissionRevision = activity.permissionRevision
    LaunchedEffect(permissionRevision) { health.refreshStatus() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Water & health apps") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (enabled && status.permitted) "Health Connect export is on" else "Health Connect export is off",
                    style = MaterialTheme.typography.titleMedium)
                Text("Only new Water drinks confirmed after connection are exported. Old daily totals are not backfilled. Your configured serving size is saved with each drink.")
                if (pending > 0) Text("$pending drinks waiting to export")
                if (lastExport > 0) Text("Last exported ${DateUtils.getRelativeTimeSpanString(lastExport)}")
                if (status.busy) Text("Exporting water intake…")
                status.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("Samsung Health", style = MaterialTheme.typography.titleSmall)
                Text(health.samsungHydrationSupport())
                Text("Direct Samsung water sync requires Samsung’s partner registration. It is not enabled by this Health Connect connection.",
                    style = MaterialTheme.typography.bodySmall)
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
            TextButton(enabled = !status.busy, onClick = {
                if (enabled && status.permitted) activity.lifecycleScope.launch {
                    if (!health.flush()) WaterHealth.schedule(activity)
                } else activity.connectWaterHealth()
            }) { Text(if (enabled && status.permitted) "Export now" else "Connect Health Connect") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

class WaterPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapTheme {
                Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Water privacy & permissions", style = MaterialTheme.typography.headlineMedium)
                        Text(WATER_PRIVACY)
                        Text(WATER_RETENTION)
                        Text("Samsung Health support depends on that app’s available water permissions. Tapwell does not change your Samsung Health account or bypass its access controls.")
                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shail9802.github.io/Tapwell/privacy.html"))) }) {
                            Text("Full privacy policy")
                        }
                        TextButton(onClick = ::finish) { Text("Close") }
                    }
                }
            }
        }
    }
}
