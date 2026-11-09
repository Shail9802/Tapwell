package app.tapwell

import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef

internal fun readNfcTag(tag: Tag): ScannedTag {
    val uid = tag.id?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) }
    val record = Ndef.get(tag)?.cachedNdefMessage?.records?.singleOrNull()
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
