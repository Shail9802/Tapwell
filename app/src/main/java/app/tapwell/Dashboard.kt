package app.tapwell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Tab(val title: String, val symbol: Symbol) {
    FOCUS("Focus", Symbol.LOCK), HABITS("Habits", Symbol.HABITS), SLEEP("Sleep", Symbol.STAND),
    STATS("Activity", Symbol.TAP), SETUP("Settings", Symbol.SETTINGS)
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
                    val iconScale by animateFloatAsState(if (tab == item) 1.08f else 1f,
                        spring(dampingRatio = .65f, stiffness = 450f), label = "navigation icon")
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { TapIcon(item.symbol, Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { insets ->
        AnimatedContent(targetState = tab, transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 24 }) togetherWith fadeOut(tween(100))
        }, label = "destination") { destination ->
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (destination == Tab.SETUP) 6.dp else 16.dp)
            ) {
                when (destination) {
                    Tab.FOCUS -> FocusPage(activity, now, blocker, onSetup = { tab = Tab.SETUP })
                    Tab.HABITS -> HabitsPage(activity, now, notify, alarms, onSetup = { tab = Tab.SETUP }, manageTags = { tagSlot = it })
                    Tab.STATS -> ActivityPage(activity, now)
                    Tab.SLEEP -> SleepRoutine(activity, now)
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

    PageHeading("Focus", "A little space from your apps.")
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
            ExpressiveAction(onClick = onSetup) { Text("Finish setup") }
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
    PageHeading("Activity", "Your day at a glance.")
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
            ChartMetric(ActivityCount.HABIT_REMINDER, "Reminder cycles", colors.primary),
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
                    Text("${counts[ActivityCount.HABIT_REMINDER] ?: 0} reminder cycles · ${counts[ActivityCount.HABIT_DONE] ?: 0} logged · ${counts[ActivityCount.HABIT_EARLY] ?: 0} logged early")
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }
    }
    Text("Counts are kept on this phone. A reminder cycle is counted once when a habit becomes due; retries and quiet hours do not add to it.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HabitsPage(
    activity: MainActivity, now: Long, notify: Boolean, alarms: Boolean, onSetup: () -> Unit,
    manageTags: (TagSlot) -> Unit
) {
    val store = activity.store
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    PageHeading("Habits", "Small routines, one tap at a time.")
    FilledTonalButton(onClick = { editingId = "" }) {
        TapIcon(Symbol.PLUS)
        Text("Add habit", Modifier.padding(start = 4.dp))
    }
    if (!notify || !alarms) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large) {
            ListItem(
                headlineContent = { Text(if (!notify) "Notifications are off" else "Improve reminder timing") },
                supportingContent = { Text(if (!notify) "Allow habit notifications." else "Allow exact alarms in Android.") },
                trailingContent = { TextButton(onClick = onSetup) { Text("Set up") } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            )
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun HabitCard(activity: MainActivity, habit: Habit, now: Long, onConfigure: () -> Unit, onManageTags: () -> Unit) {
    val store = activity.store
    val settings = store.habitSettings(habit)
    val state = store.habitState(habit, now)
    val needsPairing = settings.needsNfc && !store.hasTags(habit.tagSlot)
    val zone = ZoneId.systemDefault()
    val pausedUntil = snoozedUntil(store.reminderSnoozes(), now, zone)
    val sleepEnds = quietUntil(store.quietHours(), now, zone)
    val status = when {
        !settings.enabled -> "Paused · every ${durationText(settings.intervalMillis)} when on"
        needsPairing -> "Pair a tag to start reminders"
        state.dueAt == 0L -> "Daily goal complete"
        state.dueAt <= now && sleepEnds > now -> "Quiet until ${formatReminderTime(sleepEnds, now)}"
        state.dueAt <= now && pausedUntil > now -> "Paused until ${formatReminderTime(pausedUntil, now)}"
        state.dueAt <= now && settings.repeats -> "Waiting for completion · repeats every ${durationText(settings.repeatMillis)}"
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
            val waterProgress by animateFloatAsState((state.loggedMl.toFloat() / settings.goalMl).coerceIn(0f, 1f),
                spring(dampingRatio = 1f, stiffness = 120f), label = "water progress")
            Text(waterAmount(activity, state.loggedMl), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("of ${waterAmount(activity, settings.goalMl)} today", style = MaterialTheme.typography.bodyLarge)
            LinearWavyProgressIndicator(
                progress = { waterProgress },
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .18f),
                modifier = Modifier.fillMaxWidth()
            )
            val servings = (settings.goalMl + settings.servingMl - 1) / settings.servingMl
            Text("${waterAmount(activity, settings.servingMl)} per tap · $servings servings",
                style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (needsPairing) {
                ExpressiveAction(onClick = onManageTags, modifier = Modifier.weight(1f)) { Text("Pair tag") }
            } else {
                ExpressiveAction(
                    onClick = { activity.log(habit) },
                    enabled = settings.enabled && (state.dueAt != 0L || habit == Habit.WATER),
                    modifier = Modifier.weight(1f)
                ) { Text(if (settings.needsNfc) "Scan tag" else "Mark done") }
            }
            FilledTonalButton(onClick = onConfigure, modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) { Text("Configure") }
        }
        if (settings.needsNfc && !needsPairing) TextButton(onClick = onManageTags) { Text("Tags") }
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
    var quietHours by rememberSaveable { mutableStateOf(false) }
    var mealSnoozes by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("Reminders") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    PageHeading("Settings", "Reminders, tags and your account.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Reminders", "Tags", "Access", "App").forEach { name ->
            FilterChip(section == name, { section = name }, label = { Text(name) })
        }
    }
    Crossfade(section, animationSpec = tween(180), label = "settings section") { visibleSection ->
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (visibleSection == "App") {
    ClockFormatSetting()
    RegionalUnitsSetting()
    SetupRow("Colors & app icon", "Follow your wallpaper or choose Tapwell’s colors.", "Open", false) { appearance = true }
    if (appearance) AppearanceDialog(activity, dismiss = { appearance = false })
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced options" else "Advanced device options") }
    if (advanced) {
    SetupRow("NFC automation", "Set up Samsung routines or direct NFC control.", "Open", false) { nfcAutomation = true }
    if (nfcAutomation) NfcAutomationDialog(activity, dismiss = { nfcAutomation = false })
    SetupRow("Device administration", "Review and activate Tapwell’s Android device-admin status.", "Open", false) {
        deviceAdministration = true
    }
    if (deviceAdministration) DeviceAdministrationDialog(activity, dismiss = { deviceAdministration = false })
    }
    }
    if (visibleSection == "Reminders") {
    SetupRow("Sound & vibration", "Choose how your reminders feel and sound.", "Open", false) { alertSettings = true }
    if (alertSettings) ReminderAlertsDialog(activity, dismiss = { alertSettings = false })
    val hours = activity.store.quietHours()
    SetupRow("Quiet hours", if (hours.enabled) "Reminders pause ${clockText(activity, hours.startMinute)}–${clockText(activity, hours.endMinute)}." else "Let your reminders rest while you sleep.", "Open",
        activity.store.quietHours().enabled) { quietHours = true }
    if (quietHours) QuietHoursDialog(activity, dismiss = { quietHours = false })
    SetupRow("Meal breaks", "Pause reminders during lunch and dinner.", "Open",
        activity.store.mealSnoozes().any { it.enabled }) { mealSnoozes = true }
    if (mealSnoozes) MealSnoozeDialog(activity, dismiss = { mealSnoozes = false })
    }
    if (visibleSection == "App") {
    val googleUser = remember(revision) { activity.store.googleUser() }
    SetupRow("Google account", googleUser?.email ?: "Sign in to sync and restore your Tapwell setup.", "Open", googleUser != null) { googleSync = true }
    if (googleSync) GoogleAccountDialog(activity, dismiss = { googleSync = false })
    SetupRow("Water sync", "Send confirmed drinks to a connected health app.", "Open",
        activity.store.waterExportEnabled()) { waterHealth = true }
    if (waterHealth) WaterHealthDialog(activity, dismiss = { waterHealth = false })
    }
    if (visibleSection == "Tags") {
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
    }
    if (visibleSection == "Access") {
    SetupRow("App blocking", if (blocker) "Enabled in Android Accessibility." else "Allow Tapwell to detect and block Focus apps.", "Open", blocker) {
        activity.openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    SetupRow("NFC scanning", if (nfc) "NFC is on and ready to scan." else "Turn on NFC to scan your habit and Focus tags.", "Open", nfc) {
        activity.openSettings(Intent(Settings.ACTION_NFC_SETTINGS))
    }
    SetupRow("Notifications", if (notify) "Habit notifications are allowed." else "Allow habit reminders in your notification shade.", "Open", notify, activity::requestNotifications)
    SetupRow("Reminder timing", "Allow Android to schedule reminders accurately.", "Open", alarms) {
        activity.openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
    }
    SetupRow("Pop-up reminders", "Show the scan prompt over apps and the lock screen.", "Open", popups) {
        activity.openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
    }
    }
    if (visibleSection == "App") {
    SetupRow("About", "Meet the developer and find Tapwell’s source code.", "Open", false) { about = true }
    if (about) AboutDialog(activity, dismiss = { about = false })
    }
    }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, action: String, ready: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(28.dp)) {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = action, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(detail, style = MaterialTheme.typography.bodyMedium) },
            trailingContent = { Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        )
    }
}

@Composable
internal fun PageHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.animateContentSize(spring(stiffness = 400f))
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(symbol, prominent = emphasized)
                Text(title, style = MaterialTheme.typography.titleMedium)
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

@Suppress("DEPRECATION")
private fun selectableApps(context: Context): List<Pair<String, String>> {
    val packages = context.packageManager
    val homes = packages.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
    ).map { it.activityInfo.packageName }.toSet()
    val dialer = context.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage
    val excluded = homes + setOf(context.packageName, YOUTUBE, "com.android.settings", "com.android.systemui", dialer)
    val apps = packages.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
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

@Composable
private fun formatReminderTime(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val due = Instant.ofEpochMilli(millis).atZone(zone)
    val clock = if (uses24Hours(androidx.compose.ui.platform.LocalContext.current)) "HH:mm" else "h:mm a"
    val pattern = if (due.toLocalDate() == localDay(now, zone)) clock else "EEE, d MMM 'at' $clock"
    return due.format(DateTimeFormatter.ofPattern(pattern))
}
