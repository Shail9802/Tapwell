package app.tapwell

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Each confirmed drink keeps its original serving size and timestamp, independent of daily totals. */
internal data class WaterEvent(
    val id: String,
    val time: Long,
    val offsetSeconds: Int,
    val millilitres: Int,
    val pending: Boolean,
    val exported: Boolean = false
) {
    init {
        require(id.isNotBlank() && time > 0 && millilitres > 0)
        require(offsetSeconds in -64_800..64_800)
        require(!pending || !exported)
    }
}

internal fun newWaterEvent(time: Long, millilitres: Int, exportEnabled: Boolean): WaterEvent =
    WaterEvent(UUID.randomUUID().toString(), time,
        ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(time)).totalSeconds,
        millilitres, pending = exportEnabled)

internal fun decodeWaterEvents(encoded: String): List<WaterEvent> {
    val array = JSONArray(encoded)
    return (0 until array.length()).map { index ->
        val value = array.getJSONObject(index)
        WaterEvent(value.getString("id"), value.getLong("time"), value.getInt("offset"),
            value.getInt("ml"), value.getBoolean("pending"), value.getBoolean("exported"))
    }
}

internal fun encodeWaterEvents(events: List<WaterEvent>): String = JSONArray().apply {
    events.forEach { event ->
        put(JSONObject().put("id", event.id).put("time", event.time).put("offset", event.offsetSeconds)
            .put("ml", event.millilitres).put("pending", event.pending).put("exported", event.exported))
    }
}.toString()

internal fun markWaterEventExported(events: List<WaterEvent>, id: String): List<WaterEvent> =
    events.map { if (it.id == id && it.pending) it.copy(pending = false, exported = true) else it }

internal fun disconnectWaterEvents(events: List<WaterEvent>): List<WaterEvent> =
    events.map { if (it.pending) it.copy(pending = false) else it }

internal fun retainWaterEvents(events: List<WaterEvent>, now: Long): List<WaterEvent> =
    events.filter { it.pending || it.time >= now - 90L * 24 * 60 * MINUTE }
