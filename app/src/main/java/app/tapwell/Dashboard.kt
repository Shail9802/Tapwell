package app.tapwell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Tab(val title: String, val symbol: Symbol) {
    FOCUS("Focus", Symbol.LOCK), HABITS("Habits", Symbol.HABITS),
    STATS("Activity", Symbol.TAP), SETUP("Setup", Symbol.SETTINGS)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Dashboard(activity: MainActivity) {
    val store = activity.store
    val now = liveNow(store)
    var tab by rememberSaveable {
        mutableStateOf(if (activity.intent.getStringExtra("open_tab") == "STATS") Tab.STATS else Tab.FOCUS)
    }
    var tagSlot by remember { mutableStateOf<TagSlot?>(null) }
    val revision = activity.permissionRevision
    val blocker = remember(revision) { FocusService.enabled(activity) }
    val nfc = remember(revision) { activity.nfcReady() }
    val notify = remember(revision) { Reminders.notificationsAllowed(activity) }
    val alarms = remember(revision) { Reminders.exactAllowed(activity) }
    val popups = remember(revision) { Settings.canDrawOverlays(activity) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TapIcon(Symbol.BRAND)
                        Text("Tapwell", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { TapIcon(item.symbol) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { insets ->
        androidx.compose.runtime.key(tab) {
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (tab) {
                    Tab.FOCUS -> FocusPage(activity, now, blocker, onSetup = { tab = Tab.SETUP })
                    Tab.HABITS -> HabitsPage(activity, now, notify, alarms, onSetup = { tab = Tab.SETUP }, manageTags = { tagSlot = it })
                    Tab.STATS -> ActivityPage(activity, now)
                    Tab.SETUP -> SetupPage(activity, blocker, nfc, notify, alarms, popups, manageTags = { tagSlot = it })
                }
                Spacer(Modifier.padding(bottom = 4.dp))
            }
        }
    }
    tagSlot?.let { ManageTagsDialog(activity, it, dismiss = { tagSlot = null }) }
    activity.Scanner()
}

@Composable
private fun FocusPage(activity: MainActivity, now: Long, blocker: Boolean, onSetup: () -> Unit) {
    val store = activity.store
    val locks = store.locks(now)
    val paired = store.hasTags(TagRole.FOCUS.slot)
    val ordered = remember(locks) { locks.map { it to targetLabel(activity, it) }.sortedBy { it.second.lowercase() } }
    val work = store.work()
    val workLeft = store.workAccessLeft(now)
    var showApps by remember { mutableStateOf(false) }
    var workDialog by remember { mutableStateOf(false) }
    var durationDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var limitTarget by rememberSaveable { mutableStateOf<String?>(null) }

    PageHeading("Focus", "Put a physical tap between you and the apps you want to spend less time in.")
    FeatureCard(
        Symbol.LOCK,
        when {
            !blocker -> "App blocker is off"
            !paired -> "Pair a Focus tag"
            else -> "Focus lock is on"
        },
        when {
            !blocker -> "Enable Tapwell in Accessibility to enforce your app list."
            !paired -> "Your locked apps need a Focus tag for timed access."
            else -> "${locks.size} ${if (locks.size == 1) "rule" else "rules"} · ${store.unlockMinutes()} minutes per NFC unlock"
        }
    ) {
        if (!blocker || !paired) {
            Button(onClick = onSetup) { Text("Finish setup") }
        } else {
            FilledTonalButton(onClick = { durationDialog = true }) { Text("Unlock duration") }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Locked apps", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        FilledTonalButton(onClick = { showApps = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
            TapIcon(Symbol.PLUS)
            Text("Add app", Modifier.padding(start = 4.dp))
        }
    }
    if (ordered.isEmpty()) Text("Add an app or the YouTube Shorts rule to get started.", style = MaterialTheme.typography.bodyMedium)
    else Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column {
            ordered.forEachIndexed { index, (target, name) ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                val removal = store.removalLeft(target, now)
                val access = store.accessLeft(target, now)
                val status = when {
                    removal > 0 -> "Removes in ${timeLeft(removal)}"
                    access > 0 -> "Open for ${timeLeft(access)}"
                    target == SHORTS -> "Shorts only"
                    else -> "Whole app"
                }
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = {
                        val limit = store.dailyLimit(target)
                        Text(if (limit > 0) "$status · $limit min daily limit" else status)
                    },
                    trailingContent = {
                        val padding = PaddingValues(horizontal = 8.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { limitTarget = target }, contentPadding = padding) {
                                Text("Daily limit", maxLines = 1)
                            }
                            TextButton(
                                onClick = {
                                    if (removal > 0) store.cancelRemoval(target) else removeTarget = target to name
                                },
                                contentPadding = padding
                            ) { Text(if (removal > 0) "Cancel" else "Remove", maxLines = 1) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                )
            }
        }
    }
    FeatureCard(
        Symbol.PLACE, "Work break",
        when {
            workLeft > 0 -> "Apps are open for ${timeLeft(workLeft)}."
            work.enabled -> "${work.minutes} minutes during each work window, available from a lock screen after a location check."
            else -> "Set work hours and a location to allow one timed break per work window."
        },
        emphasized = false
    ) {
        FilledTonalButton(onClick = { workDialog = true }) { Text(if (work.enabled) "Edit work rules" else "Set up work break") }
    }

    if (showApps) AppPicker(activity, locks, dismiss = { showApps = false }) { target ->
        showApps = false
        addTarget = target
    }
    addTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { addTarget = null },
            title = { Text("Lock ${targetLabel(activity, target)}?") },
            text = {
                Text("A Focus tag is required to open this app. Removing the rule also requires a tag and a two-hour wait. Keep any apps you need in an emergency accessible.")
            },
            confirmButton = {
                TextButton(onClick = {
                    store.addLock(target)
                    addTarget = null
                }) { Text("Add to Focus") }
            },
            dismissButton = { TextButton(onClick = { addTarget = null }) { Text("Cancel") } }
        )
    }
    limitTarget?.let { target ->
        DailyLimitDialog(activity, target, dismiss = { limitTarget = null })
    }
    if (durationDialog) DurationDialog(store.unlockMinutes(), dismiss = { durationDialog = false }) { minutes ->
        durationDialog = false
        activity.scanRole(TagRole.FOCUS, "Verify Focus tag", "Scan your tag to change your NFC unlock duration.") {
            store.saveUnlockMinutes(minutes)
        }
    }
    if (workDialog) WorkDialog(activity, work, dismiss = { workDialog = false }) { settings ->
        workDialog = false
        activity.scanRole(TagRole.FOCUS, "Verify Focus tag", "Scan your tag to save the work rules.") {
            store.saveWork(settings)
        }
    }
    removeTarget?.let { (target, name) ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove $name?") },
            text = { Text("Scan your Focus tag to start a two-hour wait. The rule stays active until the timer ends.") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    activity.scanRole(TagRole.FOCUS, "Verify Focus tag", "Scan your tag to start the two-hour removal timer.") {
                        store.queueRemoval(target)
                    }
                }) { Text("Scan Focus tag") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ActivityPage(activity: MainActivity, now: Long) {
    val store = activity.store
    val revision by store.revision.collectAsState()
    var days by rememberSaveable { mutableStateOf(1) }
    val today = localDay(now, ZoneId.systemDefault())
    val history = remember(today, days, revision) {
        (days - 1 downTo 0).associate { offset ->
            val date = today.minusDays(offset.toLong())
            date to store.activityCounts(date)
        }
    }
    val totals = remember(history) {
        val counts = mutableMapOf<String, MutableMap<ActivityCount, Int>>()
        for (day in history.values) {
            for ((id, activity) in day) {
                val total = counts.getOrPut(id) { mutableMapOf() }
                for ((metric, count) in activity) total[metric] = (total[metric] ?: 0) + count
            }
        }
        counts
    }
    PageHeading("Activity", "See screen time, Focus activity, and habit logs.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(days == 1, { days = 1 }, label = { Text("Today") })
        FilterChip(days == 7, { days = 7 }, label = { Text("Last 7 days") })
    }
    Text("Focus apps", style = MaterialTheme.typography.titleLarge)
    val lockedTargets = store.locks(now)
    FocusUsageCharts(activity, lockedTargets, history.keys.toList(), now)
    val colors = MaterialTheme.colorScheme
    ActivityChart(
        title = "Focus activity",
        history = history,
        metrics = listOf(
            ChartMetric(ActivityCount.FOCUS_PROMPT, "Prompts", colors.primary),
            ChartMetric(ActivityCount.FOCUS_LEFT, "Left", colors.tertiary),
            ChartMetric(ActivityCount.FOCUS_OPENED, "Opened", colors.secondary)
        )
    )
    val targets = lockedTargets + totals.filterValues { ActivityCount.FOCUS_PROMPT in it }.keys
    for (target in targets.sortedBy { targetLabel(activity, it).lowercase() }) {
        val counts = totals[target].orEmpty()
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            ListItem(
                headlineContent = { Text(targetLabel(activity, target)) },
                supportingContent = {
                    Text("${counts[ActivityCount.FOCUS_PROMPT] ?: 0} prompts · ${counts[ActivityCount.FOCUS_LEFT] ?: 0} left · ${counts[ActivityCount.FOCUS_OPENED] ?: 0} opened")
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }
    }
    Text("Habits", style = MaterialTheme.typography.titleLarge)
    ActivityChart(
        title = "Habit activity",
        history = history,
        metrics = listOf(
            ChartMetric(ActivityCount.HABIT_ALERT, "Alerts", colors.primary),
            ChartMetric(ActivityCount.HABIT_DONE, "Logged", colors.tertiary),
            ChartMetric(ActivityCount.HABIT_EARLY, "Logged early", colors.secondary)
        )
    )
    for (habit in store.habits()) {
        val counts = totals[habit.id].orEmpty()
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            ListItem(
                headlineContent = { Text(habit.title) },
                supportingContent = {
                    Text("${counts[ActivityCount.HABIT_ALERT] ?: 0} alerts · ${counts[ActivityCount.HABIT_DONE] ?: 0} logged · ${counts[ActivityCount.HABIT_EARLY] ?: 0} logged early")
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }
    }
    Text("Counts are kept on this phone. Opened counts launches from a Focus prompt. Alerts include repeats.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HabitsPage(
    activity: MainActivity, now: Long, notify: Boolean, alarms: Boolean, onSetup: () -> Unit,
    manageTags: (TagSlot) -> Unit
) {
    val store = activity.store
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    PageHeading("Habits", "Set reminders for your routines and choose how to complete them.")
    FilledTonalButton(onClick = { editingId = "" }) {
        TapIcon(Symbol.PLUS)
        Text("Add habit", Modifier.padding(start = 4.dp))
    }
    if (!notify || !alarms) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (!notify) "Notifications are off" else "Reminder times can drift", style = MaterialTheme.typography.titleMedium)
                Text(if (!notify) "Turn on notifications to receive reminders." else "Allow exact alarms to improve reminder timing.")
                TextButton(onClick = onSetup) { Text("Open setup") }
            }
        }
    }
    store.habits().forEach { habit ->
        androidx.compose.runtime.key(habit.id) {
            HabitCard(activity, habit, now, onConfigure = { editingId = habit.id }, onManageTags = { manageTags(habit.tagSlot) })
        }
    }
    editingId?.let { id ->
        val habit = store.habit(id)
        if (id.isEmpty() || habit != null) {
            val settings = habit?.let(store::habitSettings) ?: HabitSettings(enabled = true, needsNfc = false)
            HabitDialog(
                habit, settings, paired = habit != null && store.hasTags(habit.tagSlot),
                dismiss = { editingId = null },
                test = {
                    editingId = null
                    if (habit != null) activity.testReminder(habit)
                },
                systemReminders = if (habit == null) emptyMap() else ReminderDestination.entries
                    .filter { it.available(activity) }
                    .associate { destination -> destination.label to { destination.open(activity, habit) } },
                delete = {
                    if (habit != null) {
                        store.deleteHabit(habit)
                        Reminders.cancel(activity, habit)
                    }
                    editingId = null
                }
            ) { title, updated ->
                store.saveHabit(habit, title, updated)
                editingId = null
                Reminders.reconcile(activity)
            }
        }
    }
}

@Composable
private fun HabitCard(activity: MainActivity, habit: Habit, now: Long, onConfigure: () -> Unit, onManageTags: () -> Unit) {
    val store = activity.store
    val settings = store.habitSettings(habit)
    val state = store.habitState(habit, now)
    val needsPairing = settings.needsNfc && !store.hasTags(habit.tagSlot)
    val status = when {
        !settings.enabled -> "Paused · every ${settings.minutes} min when on"
        needsPairing -> "Pair a tag to start reminders"
        state.dueAt == 0L -> "Daily goal complete"
        state.dueAt <= now && settings.repeats -> "Waiting for completion · repeats every ${settings.retryMinutes} min"
        state.dueAt <= now -> "Waiting for completion"
        else -> "Next reminder · ${formatReminderTime(state.dueAt, now)}"
    }
    val symbol = when (habit) {
        Habit.WATER -> Symbol.WATER
        Habit.STAND -> Symbol.STAND
        else -> Symbol.HABITS
    }
    FeatureCard(symbol, habit.title, status, habit == Habit.WATER) {
        if (habit == Habit.WATER) {
            Text("${state.loggedMl} / ${settings.goalMl} mL", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(
                progress = { (state.loggedMl.toFloat() / settings.goalMl).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            val servings = (settings.goalMl + settings.servingMl - 1) / settings.servingMl
            Text("${settings.servingMl} mL per scan · $servings ${if (servings == 1) "serving" else "servings"} per day",
                style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (needsPairing) {
                Button(onClick = onManageTags, modifier = Modifier.weight(1f)) { Text("Pair tag") }
            } else {
                Button(
                    onClick = { activity.log(habit) },
                    enabled = settings.enabled && (state.dueAt != 0L || habit == Habit.WATER),
                    modifier = Modifier.weight(1f)
                ) { Text(if (settings.needsNfc) "Scan tag" else "Mark done") }
            }
            OutlinedButton(onClick = onConfigure, modifier = Modifier.weight(1f)) { Text("Configure") }
        }
        if (settings.needsNfc && !needsPairing) {
            TextButton(onClick = onManageTags) { Text("Manage tags") }
        }
    }
}

@Composable
private fun SetupPage(
    activity: MainActivity, blocker: Boolean, nfc: Boolean, notify: Boolean, alarms: Boolean, popups: Boolean,
    manageTags: (TagSlot) -> Unit
) {
    val revision by activity.store.revision.collectAsState()
    var appearance by rememberSaveable { mutableStateOf(false) }
    var alertSettings by rememberSaveable { mutableStateOf(false) }
    var nfcAutomation by rememberSaveable { mutableStateOf(false) }
    var deviceAdministration by rememberSaveable { mutableStateOf(false) }
    var googleSync by rememberSaveable { mutableStateOf(false) }
    var waterHealth by rememberSaveable { mutableStateOf(false) }
    var mealSnoozes by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    PageHeading("Setup", "Pair your tags and turn on the Android permissions Tapwell uses.")
    SetupRow("Appearance", "Choose Monet colors and your launcher icon.", "Open", false) { appearance = true }
    if (appearance) AppearanceDialog(activity, dismiss = { appearance = false })
    SetupRow("NFC automation", "Set up Samsung routines or direct NFC control.", "Open", false) { nfcAutomation = true }
    if (nfcAutomation) NfcAutomationDialog(activity, dismiss = { nfcAutomation = false })
    SetupRow("Device administration", "Review and activate Tapwell’s Android device-admin status.", "Open", false) {
        deviceAdministration = true
    }
    if (deviceAdministration) DeviceAdministrationDialog(activity, dismiss = { deviceAdministration = false })
    SetupRow("Reminder alerts", "Choose a gentle chime or vibration only, and send a test alert.", "Open", false) { alertSettings = true }
    if (alertSettings) ReminderAlertsDialog(activity, dismiss = { alertSettings = false })
    SetupRow("Meal snooze", "Pause habit alerts during lunch and dinner.", "Open",
        activity.store.mealSnoozes().any { it.enabled }) { mealSnoozes = true }
    if (mealSnoozes) MealSnoozeDialog(activity, dismiss = { mealSnoozes = false })
    val googleUser = remember(revision) { activity.store.googleUser() }
    SetupRow("Google account", googleUser?.email ?: "Sign in to sync and restore your Tapwell setup.", "Open", googleUser != null) { googleSync = true }
    if (googleSync) GoogleAccountDialog(activity, dismiss = { googleSync = false })
    SetupRow("Water & health apps", "Optional water export through Health Connect. Check Samsung Health compatibility.", "Open",
        activity.store.waterExportEnabled()) { waterHealth = true }
    if (waterHealth) WaterHealthDialog(activity, dismiss = { waterHealth = false })
    Text("NFC tags", style = MaterialTheme.typography.titleLarge)
    TagRole.entries.forEach { role ->
        val count = remember(revision, role) { activity.store.tags(role.slot).size }
        val detail = when (role) {
            TagRole.FOCUS -> "Unlock apps and approve lock changes"
            TagRole.WATER -> "Log one serving of water"
            TagRole.STAND -> "Log one stand break"
        }
        val status = if (count == 0) detail else "$detail · $count paired"
        SetupRow("${role.title} tags", status, if (count > 0) "Manage" else "Pair", count > 0) { manageTags(role.slot) }
    }
    Text("Permissions", style = MaterialTheme.typography.titleLarge)
    SetupRow("Accessibility", "Detects locked apps and the visible Shorts player on your phone.", "Open", blocker) {
        activity.openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    SetupRow("NFC", "Reads the tag held to the back of your phone while the scanner is open.", "Open", nfc) {
        activity.openSettings(Intent(Settings.ACTION_NFC_SETTINGS))
    }
    SetupRow("Notifications", "Shows water and stand reminders.", "Open", notify, activity::requestNotifications)
    SetupRow("Exact alarms", "Improves reminder timing. Android can still delay retries while idle.", "Open", alarms) {
        activity.openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
    }
    SetupRow("Reminder pop-ups", "Allow Tapwell to show the reminder screen over apps and on the lock screen.", "Open", popups) {
        activity.openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
    }
    SetupRow("About", "Meet the developer and find Tapwell’s source code.", "Open", false) { about = true }
    if (about) AboutDialog(activity, dismiss = { about = false })
}

@Composable
private fun SetupRow(title: String, detail: String, action: String, ready: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text("${if (ready) "Ready · " else ""}$detail") },
            trailingContent = { TextButton(onClick = onClick) { Text(action) } },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        )
    }
}

@Composable
private fun PageHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeatureCard(
    symbol: Symbol,
    title: String,
    description: String,
    emphasized: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) colors.primaryContainer else colors.surfaceContainerLow,
            contentColor = if (emphasized) colors.onPrimaryContainer else colors.onSurface
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(symbol, prominent = emphasized)
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(activity: MainActivity, locks: Set<String>, dismiss: () -> Unit, add: (String) -> Unit) {
    val candidates = remember(locks) { selectableApps(activity).filterNot { it.first in locks } }
    var filter by rememberSaveable { mutableStateOf("") }
    val matches = candidates.filter { it.second.contains(filter.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add a lock", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, label = { Text("Search installed apps") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            if (matches.isEmpty()) Text("No matching apps.", Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(matches, key = { it.first }) { (target, name) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(if (target == SHORTS) "Keep regular YouTube available" else "Whole app") },
                        trailingContent = { TextButton(onClick = { add(target) }) { Text("Add") } }
                    )
                }
            }
        }
    }
}

private fun selectableApps(context: Context): List<Pair<String, String>> {
    val packages = context.packageManager
    val homes = packages.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.ResolveInfoFlags.of(0)
    ).map { it.activityInfo.packageName }.toSet()
    val dialer = context.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage
    val excluded = homes + setOf(context.packageName, YOUTUBE, "com.android.settings", "com.android.systemui", dialer)
    val apps = packages.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0)
    ).filter { it.activityInfo.packageName !in excluded }.map {
        it.activityInfo.packageName to it.loadLabel(packages).toString()
    }.distinctBy { it.first }
    return (apps + (SHORTS to "YouTube Shorts")).sortedBy { it.second.lowercase() }
}

internal fun targetLabel(context: Context, target: String): String {
    if (target == SHORTS) return "YouTube Shorts"
    if (target == INSTAGRAM) return "Instagram"
    return try {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(target, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        target
    }
}

internal fun timeLeft(millis: Long): String {
    val minutes = (millis + MINUTE - 1) / MINUTE
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

private fun formatReminderTime(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val due = Instant.ofEpochMilli(millis).atZone(zone)
    val pattern = if (due.toLocalDate() == localDay(now, zone)) "h:mm a" else "EEE, d MMM 'at' h:mm a"
    return due.format(DateTimeFormatter.ofPattern(pattern))
}
