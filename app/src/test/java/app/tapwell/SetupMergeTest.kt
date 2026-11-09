package app.tapwell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SetupMergeTest {
    @Test
    fun freshInstallRestoresCloudAndKeepsLocalOnlyHabits() {
        val remote = mapOf("locks" to SetupChange(2, "a", "cloud locks"))
        val local = mapOf("locks" to "default locks", "habit:b" to "local habit")
        val merged = mergeSetup(local, null, emptyMap(), listOf(remote), "b")
        assertEquals(mapOf("locks" to "cloud locks", "habit:b" to "local habit"), merged.setup)
        assertEquals(mapOf("habit:b" to SetupChange(3, "b", "local habit")), merged.outgoing)
    }

    @Test
    fun independentEditsMergeAndPendingUploadSurvivesRetry() {
        val baseline = mapOf("work" to "old work", "habit:a" to "old habit")
        val remote = mapOf("work" to SetupChange(7, "a", "new work"))
        val local = baseline + ("habit:a" to "new habit")
        val merged = mergeSetup(local, baseline, emptyMap(), listOf(remote), "b")
        val retried = mergeSetup(merged.setup, merged.setup, merged.outgoing, listOf(remote), "b")
        assertEquals(mapOf("work" to "new work", "habit:a" to "new habit"), merged.setup)
        assertEquals(merged, retried)
    }

    @Test
    fun deletionSurvivesAReinstallAndWinsItsTieDeterministically() {
        val group = "habit:a"
        val older = mapOf(group to SetupChange(4, "a", "old habit"))
        val delete = mergeSetup(emptyMap(), mapOf(group to "old habit"), emptyMap(), listOf(older), "c")
        val concurrent = mapOf(group to SetupChange(5, "b", "edited habit"))
        val restored = mergeSetup(mapOf(group to "old habit"), null, emptyMap(), listOf(delete.outgoing, older, concurrent), "d")
        assertFalse(group in restored.setup)
        assertEquals(SetupChange(5, "c", null), delete.outgoing[group])
    }
}
