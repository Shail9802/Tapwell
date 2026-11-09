package app.tapwell

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@Composable
internal fun GoogleSyncDialog(activity: MainActivity, dismiss: () -> Unit) {
    val revision by activity.store.revision.collectAsState()
    val sync = activity.setupSync
    val status by sync.status.collectAsState()
    val saved = remember(revision) { activity.store.portableSetup.sync() }
    val available = remember { sync.available() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Google Drive sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sync your app locks, paired NFC tags, habits, alert settings, and work schedule and location in Tapwell’s private Google Drive folder.")
                Text("Reminder timers, daily progress, and Android permissions are managed separately on each phone.",
                    style = MaterialTheme.typography.bodySmall)
                if (saved != null) {
                    Text(saved.account.email, style = MaterialTheme.typography.titleSmall)
                    if (saved.lastSync > 0) {
                        Text("Last synced ${DateUtils.getRelativeTimeSpanString(saved.lastSync)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !status.busy, onClick = { activity.lifecycleScope.launch { sync.disconnect() } }) {
                        Text("Disconnect Google Drive")
                    }
                    Text("Disconnecting keeps your setup and backup. Other phones may need to reconnect.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("On first connection, existing backup settings take priority. Connect the same Google account on your other phones.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (status.busy) Text("Syncing…")
                status.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!available) Text("Google Play services is unavailable on this phone.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = available && !status.busy,
                onClick = {
                    if (saved == null || status.reconnect) activity.connectGoogleDrive()
                    else activity.lifecycleScope.launch { sync.refresh() }
                }
            ) { Text(if (saved == null) "Connect Google Drive" else if (status.reconnect) "Reconnect" else "Sync now") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}
