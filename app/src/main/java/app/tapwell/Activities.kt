package app.tapwell

import android.Manifest
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private class ScanRequest(
    val slot: TagSlot,
    val title: String,
    val detail: String,
    val inlinePrompt: Boolean,
    val label: String?,
    val onTag: (ScannedTag) -> String?
)

internal abstract class TapActivity : ComponentActivity() {
    val store by lazy { Store.get(this) }
    var permissionRevision by mutableStateOf(0)
        private set
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    private var request by mutableStateOf<ScanRequest?>(null)
    protected var scanError by mutableStateOf<String?>(null)
        private set
    private var resumed = false
    private var readerEnabled = false
    private var nextLocation: (() -> Unit)? = null
    private val precisePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = nextLocation
        nextLocation = null
        permissionRevision++
        if (hasPreciseLocation(this)) next?.invoke()
        else notice("Allow precise location to use a work break.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        Reminders.reconcile(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        permissionRevision++
        syncReader()
        Reminders.reconcile(this)
    }

    override fun onPause() {
        resumed = false
        syncReader()
        super.onPause()
    }

    fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    fun withPreciseLocation(action: () -> Unit) {
        if (hasPreciseLocation(this)) action()
        else {
            nextLocation = action
            precisePermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    fun nfcReady(): Boolean = nfc?.isEnabled == true

    fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            notice("Open this permission from Android Settings.")
        }
    }

    fun notice(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun scan(
        slot: TagSlot,
        title: String,
        detail: String,
        inlinePrompt: Boolean = false,
        label: String? = null,
        onTag: (ScannedTag) -> String?
    ) {
        scanError = null
        request = ScanRequest(slot, title, detail, inlinePrompt, label, onTag)
        syncReader()
    }

    fun scanRole(role: TagRole, title: String, detail: String, onMatch: () -> Unit) {
        if (!store.hasTags(role.slot)) {
            notice("Pair a ${role.title} tag in Tapwell’s Setup tab first.")
            return
        }
        verifyTag(role.slot, title, detail) {
            onMatch()
            null
        }
    }

    fun addTag(slot: TagSlot, label: String, replace: PairedTag? = null) {
        fun pairNew(approval: ScannedTag?) {
            val detail = if (approval == null) "Hold the back of your phone near the tag you want to add."
            else "Lift the first tag, then hold your phone near the new tag."
            scan(slot, "Pair ${slot.title} tag", detail, label = label) { tag ->
                val error = store.enroll(slot, tag, label, replace, approval)
                if (error == null) Reminders.reconcile(this)
                error
            }
        }
        if (!store.hasTags(slot)) pairNew(null)
        else verifyTag(slot, "Verify ${slot.title} tag", "Scan any paired tag for this task before adding a new one.") {
            pairNew(it)
            null
        }
    }

    fun removeTag(slot: TagSlot, tag: PairedTag) {
        verifyTag(slot, "Remove ${tag.label}?", "Scan any paired ${slot.title} tag to remove ${tag.label}.") {
            if (!store.removeTag(slot, tag, it)) "Keep at least one tag paired to this task."
            else null
        }
    }

    private fun verifyTag(slot: TagSlot, title: String, detail: String, onMatch: (ScannedTag) -> String?) {
        scan(slot, title, detail) { tag ->
            if (store.matches(slot, tag)) onMatch(tag) else "This tag isn’t paired to ${slot.title}."
        }
    }

    fun testReminder(habit: Habit = Habit.WATER) {
        if (!Reminders.notificationsAllowed(this)) {
            requestNotifications()
            notice("Allow notifications, then send the test alert again.")
        } else if (Reminders.test(this, habit)) {
            notice("Test alert sent")
        } else {
            notice("Enable this reminder’s notification channel in Android Settings.")
        }
    }

    fun log(habit: Habit, inlinePrompt: Boolean = false, test: Boolean = false, onSuccess: () -> Unit = {}) {
        fun complete(tag: ScannedTag?): String? {
            if (test) {
                if (tag == null) notice("Test scan matched")
                onSuccess()
                return null
            }
            if (!store.logHabit(habit, tag)) return "This reminder is paused, unavailable, or complete for today."
            Reminders.reconcile(this)
            if (tag == null) {
                notice(when (habit) {
                    Habit.WATER -> "${store.habitSettings(habit).servingMl} mL logged"
                    Habit.STAND -> "Stand break logged"
                    else -> "${habit.title} marked done"
                })
            }
            onSuccess()
            return null
        }

        if (!store.habitSettings(habit).needsNfc) {
            complete(null)?.let(::notice)
            return
        }
        scan(habit.tagSlot, "Scan ${habit.tagLabel} tag", "Hold your phone near any tag paired to this habit.", inlinePrompt) { tag ->
            if (!store.matches(habit.tagSlot, tag)) "This tag isn’t paired to ${habit.tagLabel}."
            else complete(tag)
        }
    }

    protected fun cancelScan() {
        request = null
        syncReader()
    }

    private fun syncReader() {
        val active = request
        if (resumed && active != null && nfcReady()) {
            if (readerEnabled) nfc?.disableReaderMode(this)
            readerEnabled = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            nfc?.enableReaderMode(this, { tag ->
                val reading = readNfcTag(tag)
                runOnUiThread {
                    if (request !== active || !resumed) return@runOnUiThread
                    if (reading.identity == null) {
                        scanError = "This tag doesn’t provide an ID. Try another NFC tag."
                        return@runOnUiThread
                    }
                    // The action can close the scanner before Android sees the tag leave the field.
                    nfc?.ignore(tag, TAG_REMOVAL_DEBOUNCE_MS, null, null)
                    val label = active.label ?: store.pairedLabel(active.slot, reading)
                    val error = active.onTag(reading)
                    if (error == null) {
                        TagScanFeedback.show(this, reading, tagScanMessage(active.slot, label))
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        if (request === active) cancelScan()
                    } else scanError = error
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
        } else if (readerEnabled) {
            nfc?.disableReaderMode(this)
            readerEnabled = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Composable
    fun Scanner() {
        val active = request ?: return
        if (active.inlinePrompt) return
        val revision = permissionRevision
        val ready = androidx.compose.runtime.remember(revision, active) { nfcReady() }
        AlertDialog(
            onDismissRequest = ::cancelScan,
            icon = {
                if (ready) NfcTapAnimation(Modifier.size(width = 144.dp, height = 94.dp))
                else TapIcon(Symbol.TAP)
            },
            title = { Text(active.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (ready) active.detail else "Turn on NFC to scan a tag.")
                    scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (ready) TextButton(onClick = ::cancelScan) { Text("Cancel") }
                else TextButton(onClick = { openSettings(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("NFC settings") }
            },
            dismissButton = { if (!ready) TextButton(onClick = ::cancelScan) { Text("Cancel") } }
        )
    }
}

internal class MainActivity : TapActivity() {
    val setupSync by lazy { SetupSync.get(this) }
    private val googleAuthorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val authorized = Identity.getAuthorizationClient(this@MainActivity)
                    .getAuthorizationResultFromIntent(result.data)
                setupSync.connect(authorized)
            } catch (error: com.google.android.gms.common.api.ApiException) {
                setupSync.authorizationFailed(error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLauncherAppearance(this)
        setContent { TapTheme { Dashboard(this) } }
    }

    override fun onResume() {
        super.onResume()
        val sync = store.portableSetup.sync()
        if (sync != null && System.currentTimeMillis() - sync.lastSync > 30_000) {
            SetupSync.scheduleEdit(this)
        }
    }

    fun connectGoogleDrive() {
        lifecycleScope.launch {
            try {
                val result = setupSync.authorize()
                val resolution = result.pendingIntent
                if (result.hasResolution() && resolution != null) {
                    googleAuthorization.launch(IntentSenderRequest.Builder(resolution).build())
                } else {
                    setupSync.connect(result)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setupSync.authorizationFailed(error)
            }
        }
    }
}

internal class GateActivity : TapActivity() {
    private var target by mutableStateOf("")
    private var working by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var locateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        setContent { TapTheme { key(target) { Gate() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cancelScan()
        locateJob?.cancel()
        working = false
        error = null
        target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
    }

    @Composable
    private fun Gate() {
        val now = liveNow(store)
        val activeTarget = target
        val work = store.work()
        val window = workWindow(work, now, ZoneId.systemDefault())
        val locked = activeTarget in store.locks(now) && store.accessLeft(activeTarget, now) == 0L
        val focusPaired = store.hasTags(TagRole.FOCUS.slot)
        val name = targetLabel(this, activeTarget)
        BackHandler { leaveTarget() }
        FullPage(
            symbol = Symbol.LOCK,
            eyebrow = "FOCUS LOCK",
            title = if (locked) "$name is locked" else "$name is available",
            body = if (locked) "Scan your Focus tag to open a ${store.unlockMinutes()}-minute session."
                else "You can continue to the app now."
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when {
                        !locked -> openTarget()
                        !focusPaired -> {
                            startActivity(Intent(this, MainActivity::class.java))
                            finishAndRemoveTask()
                        }
                        else -> scanRole(TagRole.FOCUS, "Scan Focus tag", "Hold your phone against your Focus tag.") {
                            store.unlock(activeTarget)
                            openTarget()
                        }
                    }
                }
            ) { Text(if (!locked) "Open $name" else if (focusPaired) "Scan Focus tag" else "Open Tapwell setup") }
            if (locked && work.enabled && window != null) {
                val used = store.workBreakUsed(window)
                TextButton(
                    onClick = { requestBreak(activeTarget, work) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !used && !working
                ) {
                    if (working) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        if (used) "Work break already used" else if (working) "Checking location…" else "Start work break",
                        modifier = Modifier.padding(start = if (working) 8.dp else 0.dp)
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = ::leaveTarget, modifier = Modifier.fillMaxWidth()) {
                Text(if (activeTarget == SHORTS) "Leave Shorts" else "Go home")
            }
        }
        Scanner()
    }

    private fun requestBreak(activeTarget: String, work: WorkSettings) {
        withPreciseLocation {
            working = true
            error = null
            locateJob = lifecycleScope.launch {
                try {
                    val fix = locate(this@GateActivity)
                    if (target != activeTarget || activeTarget !in store.locks()) return@launch
                    error = when {
                        fix == null -> "Couldn’t get a precise location. Check location settings and try again."
                        !atWork(work, fix) -> "Tapwell couldn’t verify that you’re within the saved work area."
                        !store.startWorkBreak(work, System.currentTimeMillis()) -> "This work break is no longer available."
                        else -> {
                            openTarget()
                            null
                        }
                    }
                } finally {
                    working = false
                }
            }
        }
    }

    private fun openTarget() {
        val pkg = if (target == SHORTS) YOUTUBE else target
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finishAndRemoveTask()
    }

    private fun leaveTarget() {
        if (target != SHORTS) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finishAndRemoveTask()
    }

    companion object { const val EXTRA_TARGET = "blocked_target" }
}

@Composable
private fun FullPage(
    symbol: Symbol,
    eyebrow: String,
    title: String,
    body: String,
    content: @Composable () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.safeDrawingPadding().fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconBadge(symbol, prominent = true)
                Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
                content()
            }
        }
    }
}
