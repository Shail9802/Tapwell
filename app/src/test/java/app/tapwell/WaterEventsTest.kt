package app.tapwell

import org.junit.Assert.*
import org.junit.Test

class WaterEventsTest {
    private val drink = WaterEvent("stable-record-id", 1_800_000_000_000, 19_800, 250, pending = true)

    @Test fun persistedRetryKeepsTheOriginalIdServingAndTime() {
        assertEquals(listOf(drink), decodeWaterEvents(encodeWaterEvents(listOf(drink))))
        val done = markWaterEventExported(listOf(drink), drink.id)
        assertEquals(drink.copy(pending = false, exported = true), done.single())
        assertEquals(done, markWaterEventExported(done, drink.id))
        assertEquals(listOf(drink), markWaterEventExported(listOf(drink), "another-id"))
    }

    @Test fun disconnectCancelsQueuedExportsWithoutLosingTheDrink() {
        val done = drink.copy(id = "already-sent", pending = false, exported = true)
        val events = disconnectWaterEvents(listOf(drink, done))
        assertEquals(drink.copy(pending = false), events.first())
        assertEquals(done, events.last())
        assertFalse(events.any { it.pending })
        assertEquals(500, events.sumOf { it.millilitres })
    }

    @Test fun retentionNeverDiscardsAnUnsentDrink() {
        val old = drink.copy(id = "old-local", pending = false)
        val now = drink.time + 91L * 24 * 60 * MINUTE
        assertEquals(listOf(drink), retainWaterEvents(listOf(old, drink), now))
        assertFalse(newWaterEvent(drink.time, 500, exportEnabled = false).pending)
    }

    @Test fun exportUsesStableIdsOriginalVolumeAndOriginalTimezone() {
        val record = drink.hydrationRecord()
        assertEquals(250.0, record.volume.inMilliliters, 0.0)
        assertEquals(drink.time, record.endTime.toEpochMilli())
        assertEquals(drink.offsetSeconds, record.startZoneOffset!!.totalSeconds)
        assertEquals("tapwell-water-${drink.id}", record.metadata.clientRecordId)
        assertEquals(1L, record.metadata.clientRecordVersion)
        assertEquals(record.metadata.clientRecordId, drink.hydrationRecord().metadata.clientRecordId)
    }

    @Test fun healthEventsAndConsentNeverEnterGoogleDriveConfigurationSync() {
        listOf("water_events", "water_export_enabled", "water_export_last").forEach { assertFalse(portablePreference(it)) }
    }
}
