package app.tapwell

/** Recovery is limited to habit tags; Focus still requires its existing NFC approval. */
internal fun supportsHabitTagRecovery(slot: TagSlot): Boolean =
    slot.key == TagRole.WATER.slot.key || slot.key == TagRole.STAND.slot.key ||
        (slot.habitId != null && slot.key == "${slot.habitId}_tag")

internal fun habitTagRecoveryError(
    slot: TagSlot,
    current: Set<String>,
    expected: Set<String>,
    replacement: ScannedTag,
    otherTasks: Set<String>
): String? = when {
    !supportsHabitTagRecovery(slot) -> "Focus tags cannot be reset through habit recovery."
    current.isEmpty() || current != expected -> "The paired tags changed. Close this scanner and start recovery again."
    replacement.identity == null -> "This tag doesn’t provide an ID."
    replacement.matches(current) -> "This tag is already paired. Scan your new replacement tag."
    replacement.matches(otherTasks) -> "This tag is paired to another task."
    else -> null
}
