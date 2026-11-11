package app.tapwell

import android.content.Intent
import android.net.Uri
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
internal fun GoogleAccountDialog(activity: MainActivity, dismiss: () -> Unit) {
    val revision by activity.store.revision.collectAsState()
    val sync = activity.setupSync
    val status by sync.status.collectAsState()
    val project = activity.getString(R.string.google_cloud_project)
    val user = remember(revision) { activity.store.googleUser()?.takeIf { it.project == project } }
    val saved = remember(revision) {
        syncForGoogleUser(activity.store.portableSetup.sync(), user, project)
    }
    val available = remember { sync.available() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Google account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (user == null) {
                    Text("Sign in with Google to sync your Tapwell setup across phones. Then approve private Drive sync for that account.")
                    Text("Your account stores app locks, NFC tags, habit settings, alert settings, and work rules including the saved work location.")
                    Text("When restoring, existing backup settings take priority where they overlap.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    user.name?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(user.email, style = MaterialTheme.typography.titleSmall)
                    Text(if (saved == null) "Signed in · Sync needs your permission" else "Drive sync uses this Google account.")
                    if (saved != null && saved.lastSync > 0) {
                        Text("Last synced ${DateUtils.getRelativeTimeSpanString(saved.lastSync)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !status.busy, onClick = {
                        activity.lifecycleScope.launch { sync.signOut() }
                    }) { Text("Sign out") }
                }
                Text("Daily progress, activity counts, reminder timers, widgets, and Android permissions are local to each phone.",
                    style = MaterialTheme.typography.bodySmall)
                if (status.busy) Text(status.operation)
                status.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!available) Text("Google Play services is unavailable on this phone.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    activity.openSettings(Intent(Intent.ACTION_VIEW, Uri.parse("https://shail9802.github.io/Tapwell/privacy.html")))
                }) { Text("Privacy policy") }
            }
        },
        confirmButton = {
            TextButton(enabled = available && !status.busy, onClick = {
                when {
                    user == null -> activity.signInWithGoogle()
                    saved == null || status.reconnect -> activity.connectGoogleDrive()
                    else -> activity.lifecycleScope.launch { sync.refresh() }
                }
            }) {
                Text(when {
                    user == null -> "Sign in with Google"
                    saved == null -> "Enable sync"
                    status.reconnect -> "Reconnect sync"
                    else -> "Sync now"
                })
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}
