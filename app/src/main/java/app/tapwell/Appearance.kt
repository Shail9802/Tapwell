package app.tapwell

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ColorRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private enum class LauncherIcon(
    val component: String,
    val title: String,
    val description: String,
    @param:ColorRes val background: Int,
    @param:ColorRes val glyph: Int
) {
    AUTO("LauncherStandard", "Follow device", "Uses your launcher’s themed icon setting",
        R.color.tapwell_ink, R.color.tapwell_lime),
    STANDARD("LauncherPlain", "Normal Android", "Tapwell’s plum and lime",
        R.color.tapwell_ink, R.color.tapwell_lime),
    MONET("LauncherMonet", "Android Monet", "Wallpaper accent colors",
        R.color.tapwell_monet_background, R.color.tapwell_monet_glyph),
    SAMSUNG("LauncherSamsung", "Samsung Monet", "Softer palette and a smaller mark",
        R.color.tapwell_samsung_background, R.color.tapwell_samsung_glyph);

    private fun componentName(context: Context) = ComponentName(context, "${context.packageName}.$component")

    fun activate(context: Context) {
        val manager = context.packageManager
        val flag = PackageManager.DONT_KILL_APP
        val order = listOf(this) + entries.filter { it != this }
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(order.map { icon ->
                PackageManager.ComponentEnabledSetting(icon.componentName(context), icon.state(this), flag)
            })
        } else {
            for (icon in order) manager.setComponentEnabledSetting(icon.componentName(context), icon.state(this), flag)
        }
    }

    private fun state(selected: LauncherIcon) =
        if (this == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    companion object {
        fun active(context: Context): LauncherIcon = entries.firstOrNull {
            context.packageManager.getComponentEnabledSetting(it.componentName(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AUTO
    }
}

internal fun migrateLauncherAppearance(context: Context) {
    val store = Store.get(context)
    if (store.hasAutomaticLauncherDefault()) return
    if (LauncherIcon.active(context) != LauncherIcon.AUTO) LauncherIcon.AUTO.activate(context)
    store.markAutomaticLauncherDefault()
}

@Composable
internal fun AppearanceDialog(activity: MainActivity, dismiss: () -> Unit) {
    var wallpaperColors by rememberSaveable { mutableStateOf(activity.store.wallpaperColors()) }
    var icon by rememberSaveable { mutableStateOf(LauncherIcon.active(activity)) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Appearance") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().toggleable(wallpaperColors, role = Role.Switch) { wallpaperColors = it },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Follow device colors", style = MaterialTheme.typography.titleMedium)
                        Text("Use your system palette when available, or switch to Tapwell’s own colors.")
                    }
                    Switch(checked = wallpaperColors, onCheckedChange = null)
                }
                HorizontalDivider()
                Text("Launcher icon", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    LauncherIcon.entries.forEach { option ->
                        ListItem(
                            modifier = Modifier.selectable(icon == option, role = Role.RadioButton) { icon = option },
                            headlineContent = { Text(option.title) },
                            supportingContent = { Text(option.description) },
                            leadingContent = { LauncherIconPreview(option) },
                            trailingContent = { RadioButton(selected = icon == option, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
                Text("Follow device lets Android or One UI recolor the icon automatically. Your launcher may also tint other choices.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    if (icon != LauncherIcon.active(activity)) icon.activate(activity)
                    activity.store.setWallpaperColors(wallpaperColors)
                    dismiss()
                } catch (_: IllegalArgumentException) {
                    activity.notice("This launcher icon couldn’t be applied.")
                } catch (_: SecurityException) {
                    activity.notice("This launcher icon couldn’t be applied.")
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LauncherIconPreview(icon: LauncherIcon) {
    val samsung = icon == LauncherIcon.SAMSUNG
    Surface(
        modifier = Modifier.size(44.dp),
        shape = if (samsung) RoundedCornerShape(30) else CircleShape,
        color = colorResource(icon.background)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_tapwell_mark),
                contentDescription = null,
                tint = colorResource(icon.glyph),
                modifier = Modifier.size(if (samsung) 33.dp else 37.dp)
            )
        }
    }
}
