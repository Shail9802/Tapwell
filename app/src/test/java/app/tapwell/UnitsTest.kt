package app.tapwell

import org.junit.Assert.*
import org.junit.Test

class UnitsTest {
    @Test fun regionalDefaultsAreMetricForIndiaAndCanada() {
        assertEquals("metric", defaultUnitSystem("IN"))
        assertEquals("metric", defaultUnitSystem("CA"))
        assertEquals("us", defaultUnitSystem("US"))
    }
    @Test fun litresAndDistinctFluidOuncesConvertToCanonicalMillilitres() {
        assertEquals(1500, amountBase("1.5", metricVolume[1], 1, 20000))
        assertEquals(1500, amountBase("1,5", metricVolume[1], 1, 20000))
        assertEquals(30, amountBase("1", usVolume[0], 1, 20000))
        assertEquals(28, amountBase("1", imperialVolume[0], 1, 20000))
        assertNull(amountBase("NaN", metricVolume[0], 1, 20000))
        assertNull(amountBase("21", metricVolume[1], 1, 20000))
    }
}
