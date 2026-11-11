package app.tapwell

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class TapwellDeviceAdminReceiver : DeviceAdminReceiver()

@Composable
internal fun DeviceAdministrationDialog(activity: MainActivity, dismiss: () -> Unit) {
    val revision = activity.permissionRevision
    val policy = remember { activity.getSystemService(DevicePolicyManager::class.java) }
    val component = remember { ComponentName(activity, TapwellDeviceAdminReceiver::class.java) }
    val supported = remember { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) }
    val active = remember(revision) { policy.isAdminActive(component) }
    val deviceOwner = remember(revision) { policy.isDeviceOwnerApp(activity.packageName) }
    val profileOwner = remember(revision) { policy.isProfileOwnerApp(activity.packageName) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Device administration") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    when {
                        !supported -> "Device administration is unavailable on this device."
                        deviceOwner -> "Tapwell is the device owner."
                        profileOwner -> "Tapwell is the profile owner."
                        active -> "Tapwell device admin is active."
                        else -> "Tapwell device admin is inactive."
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text("Activate Tapwell through Android’s device-admin confirmation screen. You can review and deactivate it in Android’s Device admin apps settings.")
                Text("Device admin and NFC automation")
                Text("Automatic NFC switching requires system-app or managed-device owner status and secure-settings permission on Android 16 or later. Standard device-admin activation alone leaves direct NFC control unavailable.")
                Text("Device-owner enrollment is a separate device-management setup, usually performed on a new or factory-reset phone. Use Samsung Modes and Routines for NFC automation on your everyday phone.")
                if (supported && !active) {
                    TextButton(onClick = {
                        activity.openSettings(
                            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                                .putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Enable Tapwell as a device administrator. Automatic NFC control also requires managed-device owner or system-app status and secure-settings permission."
                                )
                        )
                    }) { Text("Activate device admin") }
                }
                TextButton(onClick = {
                    activity.openSettings(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }) { Text("Open security settings") }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
}
