package app.tapwell

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal object NfcAutomation {
    private fun prefs(context: Context) = context.getSharedPreferences("nfc_automation", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("direct", false)

    fun save(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("direct", enabled).apply()
    }

    fun unavailableReason(context: Context): String? {
        if (NfcAdapter.getDefaultAdapter(context) == null) return "This device has no NFC adapter."
        if (Build.VERSION.SDK_INT < 36) return "Direct control requires Android 16 or later."
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        val systemApp = context.applicationInfo.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (!systemApp && !policy.isDeviceOwnerApp(context.packageName) && !policy.isProfileOwnerApp(context.packageName)) {
            return "Android requires Tapwell to be a system app or a managed-device owner app for direct control."
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return "The device administrator must grant Tapwell the secure-settings permission."
        }
        return null
    }

    fun enableIfNeeded(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        if (adapter.isEnabled) return true
        if (!enabled(context) || Build.VERSION.SDK_INT < 36 || unavailableReason(context) != null) return false
        return try {
            adapter.enable()
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }
}

@Composable
internal fun NfcAutomationDialog(activity: MainActivity, dismiss: () -> Unit) {
    val revision = activity.permissionRevision
    val reason = remember(revision) { NfcAutomation.unavailableReason(activity) }
    var enabled by remember { mutableStateOf(NfcAutomation.enabled(activity)) }
    var result by remember { mutableStateOf<String?>(null) }
    val routines = remember {
        activity.packageManager.getLaunchIntentForPackage("com.samsung.android.app.routines")
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("NFC automation") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Samsung Modes and Routines", style = MaterialTheme.typography.titleSmall)
                Text("Create a routine in Samsung’s app to turn NFC on for Tapwell.")
                Text("In Routines, add a routine with Notification received from Tapwell as the condition and NFC On as the action. Allow notification access if Samsung asks.")
                Text("You can also use App opened → Tapwell for scans started inside the app. Available conditions and actions depend on your One UI version.")
                TextButton(onClick = {
                    activity.openSettings(routines ?: Intent(Settings.ACTION_SETTINGS))
                }) { Text(if (routines != null) "Open Modes and Routines" else "Open Android settings") }
                TextButton(onClick = activity::requestNotifications) { Text("Tapwell notification settings") }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Direct NFC control", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = enabled,
                        enabled = reason == null || enabled,
                        onCheckedChange = {
                            enabled = it
                            NfcAutomation.save(activity, it)
                            result = null
                        }
                    )
                }
                Text("Turn NFC on when an NFC reminder is posted or a tag scanner opens. This setting applies to this phone.")
                if (reason != null) Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Android offers this control to system and managed-device owner apps with secure-settings permission. An ordinary installation can use the Samsung routine above.")
                if (enabled && reason == null) {
                    TextButton(onClick = {
                        result = if (NfcAutomation.enableIfNeeded(activity)) "NFC is on or starting."
                            else "Android could not turn NFC on. Open NFC settings to enable it."
                    }) { Text("Try enabling NFC") }
                }
                result?.let { Text(it) }
                TextButton(onClick = { activity.openSettings(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                    Text("Open NFC settings")
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
}
