package app.tapwell

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun ManageTagsDialog(activity: MainActivity, slot: TagSlot, dismiss: () -> Unit) {
    val revision by activity.store.revision.collectAsState()
    val tags = remember(revision, slot) { activity.store.tags(slot) }
    var label by rememberSaveable(slot.key) { mutableStateOf("") }
    var testCode by rememberSaveable(slot.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(tags.size) { label = "" }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("${slot.title} tags") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (tags.isEmpty()) "Add a tag for this task." else "Any of these tags can complete this task.")
                tags.forEach { tag ->
                    ListItem(
                        headlineContent = { Text(tag.label) },
                        supportingContent = { Text(if (tag.id.startsWith(TAGFOLD_PREFIX)) "Tapwell code" else "Physical tag") },
                        trailingContent = {
                            TextButton(onClick = {
                                if (tags.size == 1) activity.addTag(slot, label.ifBlank { tag.label }, replace = tag)
                                else activity.removeTag(slot, tag)
                            }) { Text(if (tags.size == 1) "Replace" else "Remove") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider()
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("Tag name (optional)") },
                    placeholder = { Text("Home or work") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (tags.isNotEmpty()) {
                    Text("You’ll verify a paired tag before changing the list.", style = MaterialTheme.typography.bodySmall)
                }
                val code = testCode
                if (code == null) {
                    TextButton(onClick = { testCode = TAGFOLD_PREFIX + UUID.randomUUID().toString() }) {
                        Text("Generate Tagfold code")
                    }
                } else {
                    Text("On another phone, paste this into a Tagfold Text tag and start emulating it.")
                    SelectionContainer { Text(code, style = MaterialTheme.typography.bodyMedium) }
                    TextButton(onClick = {
                        val clipboard = activity.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Tapwell tag", code))
                        activity.notice("Tagfold code copied")
                    }) { Text("Copy code") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { activity.addTag(slot, label) }) { Text("Add tag") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}
