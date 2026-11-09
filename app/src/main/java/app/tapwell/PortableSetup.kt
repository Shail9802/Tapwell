package app.tapwell

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class SyncAccount(val email: String, val id: String)

internal data class SavedSync(
    val account: SyncAccount,
    val baseline: Map<String, String>,
    val outgoing: SetupChangeSet,
    val lastSync: Long
)

internal class PortableSetup(private val prefs: SharedPreferences) {
    var applying = false
        private set

    fun deviceId(): String = prefs.getString("sync_device", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("sync_device", it).apply()
    }

    fun connected(): Boolean = prefs.contains("sync_state")

    fun sync(): SavedSync? {
        val encoded = prefs.getString("sync_state", null) ?: return null
        val json = JSONObject(encoded)
        val baseline = json.getJSONObject("baseline")
        return SavedSync(
            SyncAccount(json.getString("email"), json.getString("account")),
            baseline.keys().asSequence().associateWith(baseline::getString),
            readSetupChanges(json.getJSONObject("outgoing")),
            json.getLong("lastSync")
        )
    }

    fun disconnect() {
        // Use a new replica after reconnecting so old queued changes cannot leak to another account.
        prefs.edit().remove("sync_state").remove("sync_device").apply()
    }

    fun capture(): Map<String, String> {
        val raw = prefs.all
        val custom = (raw["custom_habits"] as? Set<*>)?.filterIsInstance<String>().orEmpty().filter(::customId)
        val groups = fixedGroups.keys + custom.map { "habit:$it" }
        return groups.mapNotNull { group ->
            val json = JSONObject()
            for (key in groupKeys(group)) {
                val value = raw[key] ?: continue
                json.put(key, if (value is Set<*>) JSONArray(value.filterIsInstance<String>().sorted()) else value)
            }
            if (json.length() == 0) null else group to json.toString()
        }.toMap()
    }

    fun apply(setup: Map<String, String>, state: SavedSync) {
        val old = capture()
        val groups = setup.mapValues { (group, encoded) -> decodeGroup(group, encoded) }
        val oldCustom = old.keys.filter(::customGroup)
        val newCustom = groups.keys.filter(::customGroup)
        val edit = prefs.edit()
        for (group in (old.keys + setup.keys).filter { old[it] != setup[it] }) {
            val values = groups[group].orEmpty()
            groupKeys(group).forEach { key ->
                when (val value = values[key]) {
                    is String -> edit.putString(key, value)
                    is Int -> edit.putInt(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is JSONArray -> edit.putStringSet(key, (0 until value.length()).map(value::getString).toSet())
                    else -> edit.remove(key)
                }
            }
        }
        if (oldCustom.toSet() != newCustom.toSet()) {
            edit.putStringSet("custom_habits", newCustom.map { it.removePrefix("habit:") }.toSet())
            for (group in oldCustom - newCustom.toSet()) {
                val id = group.removePrefix("habit:")
                listOf("day", "logged", "due", "sent", "retry").forEach { edit.remove("${id}_$it") }
            }
        }
        if (old["locks"] != setup["locks"]) {
            val oldLocks = lockSet(old["locks"])
            val newLocks = lockSet(setup["locks"])
            for (target in (oldLocks - newLocks) + (newLocks - oldLocks)) {
                for (kind in listOf("pass", "remove")) clearDeadline(edit, "${kind}_$target")
            }
        }
        if (old["work"] != setup["work"]) clearDeadline(edit, "work_pass")
        edit.putString("sync_state", encodeSync(state))
        applying = true
        try {
            edit.apply()
        } finally {
            applying = false
        }
    }

    fun markSynced(state: SavedSync) {
        prefs.edit().putString("sync_state", encodeSync(state)).apply()
    }

    private fun encodeSync(state: SavedSync) = JSONObject()
        .put("email", state.account.email)
        .put("account", state.account.id)
        .put("baseline", JSONObject(state.baseline))
        .put("outgoing", writeSetupChanges(state.outgoing))
        .put("lastSync", state.lastSync)
        .toString()

    private fun lockSet(group: String?): Set<String> {
        val items = group?.let { JSONObject(it).optJSONArray("locks") } ?: return setOf(INSTAGRAM, SHORTS)
        return (0 until items.length()).map(items::getString).toSet()
    }
}

private fun clearDeadline(edit: SharedPreferences.Editor, key: String) {
    listOf("wall", "elapsed", "boot").forEach { edit.remove("${key}_$it") }
}

private val uuid = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private fun customId(id: String) = uuid.matches(id)
private fun customGroup(group: String) = group.startsWith("habit:") && customId(group.removePrefix("habit:"))
private fun habitKeys(id: String) = listOf("enabled", "minutes", "goal", "serving", "needs_nfc", "repeats", "retry_minutes")
    .map { "${id}_$it" }

private val fixedGroups = mapOf(
    "locks" to listOf("locks"),
    "unlock" to listOf("unlock_minutes"),
    "appearance" to listOf("wallpaper_colors"),
    "sound" to listOf("reminder_chime"),
    "work" to listOf("work_enabled", "work_days", "work_start", "work_end", "work_minutes", "work_radius", "work_latitude", "work_longitude"),
    "focus" to listOf("tag_FOCUS", "tag_FOCUS_list"),
    "habit:WATER" to (habitKeys("WATER") + listOf("tag_WATER", "tag_WATER_list")),
    "habit:STAND" to (habitKeys("STAND") + listOf("tag_STAND", "tag_STAND_list"))
)

private fun groupKeys(group: String): List<String> = fixedGroups[group] ?: if (customGroup(group)) {
    val id = group.removePrefix("habit:")
    habitKeys(id) + listOf("${id}_title", "${id}_tag", "${id}_tag_list")
} else emptyList()

internal fun portablePreference(key: String?): Boolean {
    if (key == null) return true
    if (key == "custom_habits" || fixedGroups.values.any { key in it }) return true
    return key in groupKeys("habit:${key.substringBefore('_')}")
}

private fun decodeGroup(group: String, encoded: String): Map<String, Any> {
    val json = JSONObject(encoded)
    val keys = groupKeys(group)
    require(keys.isNotEmpty() && json.length() in 1..keys.size)
    if (customGroup(group)) require(json.has("${group.removePrefix("habit:")}_title"))
    return json.keys().asSequence().associateWith { key ->
        require(key in keys)
        val value = json.get(key)
        when {
            key == "locks" -> {
                require(value is JSONArray && value.length() <= 5000)
                for (index in 0 until value.length()) {
                    val target = value.get(index)
                    require(target is String && target.length in 1..250)
                }
            }
            key == "wallpaper_colors" || key == "reminder_chime" ||
                key.endsWith("_enabled") || key.endsWith("_needs_nfc") || key.endsWith("_repeats") -> require(value is Boolean)
            key == "work_latitude" || key == "work_longitude" -> {
                val limit = if (key == "work_latitude") 90.0 else 180.0
                require(value is String && (value.toDoubleOrNull() ?: Double.NaN) in -limit..limit)
            }
            key.endsWith("_tag") || key.startsWith("tag_") || key.endsWith("_tag_list") -> {
                require(value is String)
                if (key.endsWith("_list")) {
                    val tags = JSONObject(value)
                    require(tags.keys().asSequence().all { tag ->
                        tag.length in 1..256 && tags.get(tag) is String && tags.getString(tag).length <= 100
                    })
                } else require(value.length in 1..256)
            }
            key.endsWith("_title") -> require(value is String && value.isNotBlank() && value.length <= 60)
            else -> {
                val range = when (key) {
                    "unlock_minutes", "work_minutes" -> 1..120
                    "work_start", "work_end" -> 0..1439
                    "work_days" -> 0..127
                    "work_radius" -> 100..5000
                    else -> when {
                        key.endsWith("_retry_minutes") -> 1..1440
                        key.endsWith("_goal") -> 1..20000
                        key.endsWith("_serving") -> 1..10000
                        else -> 1..10080
                    }
                }
                require(value is Int && value in range)
            }
        }
        value
    }
}

internal fun writeSetupChanges(changes: SetupChangeSet): JSONObject = JSONObject().apply {
    for ((group, change) in changes.toSortedMap()) {
        put(group, JSONObject().put("revision", change.revision).put("source", change.source)
            .put("value", change.value ?: JSONObject.NULL))
    }
}

internal fun readSetupChanges(json: JSONObject): SetupChangeSet = json.keys().asSequence().associateWith { group ->
    require(groupKeys(group).isNotEmpty())
    val change = json.getJSONObject(group)
    val revision = change.getLong("revision")
    val source = change.getString("source")
    require(revision in 1 until Long.MAX_VALUE && customId(source))
    require(change.has("value"))
    val value = if (change.isNull("value")) null else change.getString("value").also { decodeGroup(group, it) }
    SetupChange(revision, source, value)
}
