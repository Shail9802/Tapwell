package app.tapwell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.BadParcelableException
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.IntentCompat

internal const val TAG_REMOVAL_DEBOUNCE_MS = 600

internal object TagScanFeedback {
    private var previousIdentity: String? = null
    private var previousScanAt = 0L

    fun show(context: Context, tag: ScannedTag, message: String, passive: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        val duplicate = passive && tag.identity == previousIdentity && now - previousScanAt < 1_500
        previousIdentity = tag.identity
        previousScanAt = now
        if (!duplicate) Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        return !duplicate
    }
}

/** Handles NFC text tags when the user taps outside Tapwell's foreground scanner. */
class TagNoticeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED || intent.type != "text/plain") return
            val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
            val message = Ndef.get(tag)?.cachedNdefMessage
                ?: IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                    ?.singleOrNull() as? NdefMessage
            val reading = readNfcTag(tag, message)
            val store = Store.get(this)
            val habits = store.habits()
            val focus = TagRole.FOCUS.slot
            val habit = habits.firstOrNull { store.matches(it.tagSlot, reading) }
            val slot = if (store.matches(focus, reading)) focus else habit?.tagSlot
            val feedback = slot?.let { tagScanMessage(it, store.pairedLabel(it, reading)) }
                ?: if (reading.code != null) "Unpaired Tapwell Tag" else null
            if (feedback == null) {
                openStockViewer(tag, message)
            } else {
                NfcAdapter.getDefaultAdapter(this)?.ignore(tag, TAG_REMOVAL_DEBOUNCE_MS, null, null)
                val fresh = TagScanFeedback.show(this, reading, feedback, passive = true)
                if (fresh && habit != null && slot == habit.tagSlot &&
                    store.canConfirmHabitLog(habit, reading)
                ) {
                    HabitTapActivity.prompt(this, habit, reading)
                }
            }
        } catch (_: BadParcelableException) {
            // A malformed NFC intent has no tag to display.
        } finally {
            finish()
        }
    }

    private fun openStockViewer(tag: Tag, message: NdefMessage?) {
        // Text records share one MIME type. Send non-Tapwell tags to the stock Android viewer.
        val display = Intent(NfcAdapter.ACTION_TECH_DISCOVERED)
            .setClassName("com.android.apps.tag", "com.android.apps.tag.TagViewer")
            .putExtra(NfcAdapter.EXTRA_TAG, tag)
        if (message != null) display.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(message))
        try {
            startActivity(display)
        } catch (_: ActivityNotFoundException) {
            // This device has no stock Android tag viewer.
        } catch (_: SecurityException) {
            // Some devices restrict the stock viewer to the NFC system service.
        }
    }
}

internal fun readNfcTag(tag: Tag, message: NdefMessage? = Ndef.get(tag)?.cachedNdefMessage): ScannedTag {
    val uid = tag.id?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) }
    val record = message?.records?.singleOrNull()
    val code = record?.let(::readTagfoldCode)
    return ScannedTag(uid, code)
}

private fun readTagfoldCode(record: NdefRecord): String? {
    if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
    val payload = record.payload
    if (payload.isEmpty()) return null
    val status = payload[0].toInt() and 0xff
    val textStart = 1 + (status and 0x3f)
    if (textStart >= payload.size) return null
    val encoding = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
    return tagfoldIdentity(String(payload, textStart, payload.size - textStart, encoding))
}
