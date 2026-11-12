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
        listOf("water_events", "water_export_enabled", "water_export_last", "water_export_destination").forEach { assertFalse(portablePreference(it)) }
    }

    @Test fun oldJournalKeepsHealthConnectDestinationWithoutReplayingToSamsung() {
        val legacy = """[{"id":"old","time":1800000000000,"offset":19800,"ml":250,"pending":true,"exported":false}]"""
        val old = decodeWaterEvents(legacy).single()
        assertEquals(WaterDestination.HEALTH_CONNECT, old.destination)
        assertTrue(pendingWaterEvents(listOf(old), WaterDestination.SAMSUNG).isEmpty())
        assertEquals(listOf(old), pendingWaterEvents(listOf(old), WaterDestination.HEALTH_CONNECT))
    }

    @Test fun samsungQueueSurvivesRestartAndRemainsSeparateFromPreviouslySentHealthConnect() {
        val samsung = newWaterEvent(drink.time, 500, true, WaterDestination.SAMSUNG)
        val sent = drink.copy(pending = false, exported = true)
        val restored = decodeWaterEvents(encodeWaterEvents(listOf(sent, samsung)))
        assertEquals(listOf(samsung), pendingWaterEvents(restored, WaterDestination.SAMSUNG))
        assertTrue(pendingWaterEvents(restored, WaterDestination.HEALTH_CONNECT).isEmpty())
        val disconnected = disconnectWaterEvents(restored)
        assertTrue(pendingWaterEvents(disconnected, WaterDestination.SAMSUNG).isEmpty())
        assertEquals(750, disconnected.sumOf { it.millilitres })
        assertTrue(disconnected.first().exported)
    }
}
