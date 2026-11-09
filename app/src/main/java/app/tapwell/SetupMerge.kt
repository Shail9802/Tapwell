package app.tapwell

internal data class SetupChange(val revision: Long, val source: String, val value: String?)

internal data class SetupMerge(val setup: Map<String, String>, val outgoing: Map<String, SetupChange>)

/** Each installation writes its own file. The latest change wins within each setting group. */
internal fun mergeSetup(
    local: Map<String, String>,
    baseline: Map<String, String>?,
    pending: Map<String, SetupChange>,
    downloaded: Collection<SetupChangeSet>,
    device: String
): SetupMerge {
    val latest = mutableMapOf<String, SetupChange>()
    val order = compareBy(SetupChange::revision, SetupChange::source)
    for (changes in downloaded + listOf(pending)) {
        for ((group, change) in changes) {
            val current = latest[group]
            if (current == null || order.compare(change, current) > 0) latest[group] = change
        }
    }
    val revision = (latest.values.maxOfOrNull { it.revision } ?: 0L) + 1
    val outgoing = pending.toMutableMap()
    val edited = if (baseline == null) local.keys - latest.keys
        else (local.keys + baseline.keys).filter { local[it] != baseline[it] }
    for (group in edited) {
        val change = SetupChange(revision, device, local[group])
        outgoing[group] = change
        latest[group] = change
    }
    val setup = local.toMutableMap()
    for ((group, change) in latest) {
        val value = change.value
        if (value == null) setup.remove(group) else setup[group] = value
    }
    return SetupMerge(setup, outgoing)
}

internal typealias SetupChangeSet = Map<String, SetupChange>
