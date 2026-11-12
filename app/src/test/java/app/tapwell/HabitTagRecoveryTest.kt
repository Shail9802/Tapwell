package app.tapwell

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitTagRecoveryTest {
    private val old = setOf("unknown-restored-tag")
    private val replacement = ScannedTag("new-physical-tag")

    @Test fun recoveryAcceptsAnUnavailableHabitPairingButNeverFocus() {
        assertNull(habitTagRecoveryError(TagRole.WATER.slot, old, old, replacement, emptySet()))
        assertNull(habitTagRecoveryError(TagRole.STAND.slot, old, old, replacement, emptySet()))
        assertTrue(supportsHabitTagRecovery(TagSlot("skincare_tag", "Skin care", "skincare")))
        assertFalse(supportsHabitTagRecovery(TagSlot("tag_FOCUS", "Skin care", "skincare")))
        assertNotNull(habitTagRecoveryError(TagRole.FOCUS.slot, old, old, replacement, emptySet()))
    }

    @Test fun refusesConcurrentSyncChangesMissingIdsAndTagsUsedForAnotherTask() {
        assertNotNull(habitTagRecoveryError(TagRole.WATER.slot, setOf("synced-later"), old, replacement, emptySet()))
        assertNotNull(habitTagRecoveryError(TagRole.WATER.slot, emptySet(), emptySet(), replacement, emptySet()))
        assertNotNull(habitTagRecoveryError(TagRole.WATER.slot, old, old, ScannedTag(null), emptySet()))
        assertNotNull(habitTagRecoveryError(TagRole.WATER.slot, old, old, ScannedTag(old.first()), emptySet()))
        assertNotNull(habitTagRecoveryError(TagRole.WATER.slot, old, old, replacement, setOf("new-physical-tag")))
        assertNotNull(habitTagRecoveryError(TagRole.WATER.slot, old, old,
            ScannedTag("new-uid", "focus-code"), setOf("focus-code")))
    }
}
