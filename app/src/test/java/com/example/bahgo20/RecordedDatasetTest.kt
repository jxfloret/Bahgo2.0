package com.example.bahgo20

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.time.OffsetDateTime

class RecordedDatasetTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun readsColumnsByNameAndSkipsBadRows() {
        val csv = """
            Rain_Status,Distance_cm,sensor,junk_1,Last_Updated,Rain_Value
            No Rain,90.5,Sensordata2,0.1,2026-10-13T00:35:00+08:00,1000
            No Rain,91.0,Sensordata2,0.2,2026-10-13T00:30:00+08:00,1023
            No Rain,,Sensordata2,0.3,2026-10-13T00:40:00+08:00,1023
            Light Rain,80.0,Sensordata1,0.4,not a time,500
        """.trimIndent()
        val rows = RecordedDataset.parse(StringReader(csv))
        assertEquals(setOf(2), rows.keys)
        val s2 = rows.getValue(2)
        assertEquals(2, s2.size)
        // Sorted oldest first
        assertEquals(ms("2026-10-13T00:30:00+08:00"), s2[0].timeMs)
        assertEquals(90.5, s2[1].distanceCm, 1e-9)
        assertEquals(1000.0, s2[1].rainValue, 1e-9)
    }

    @Test
    fun seriesTurnsDistanceIntoLevelAndRainIntoShare() {
        val rows = listOf(RecordedDataset.Row(0L, 30.0, 0.0), RecordedDataset.Row(1L, 100.0, 1023.0))
        val s = RecordedDataset.toSeries(rows)
        assertEquals(SensorTracker.SENSOR_HEIGHT_CM - 30.0, s.levels[0], 1e-9)
        assertEquals(0.0, s.levels[1], 1e-9)     // farther than the sensor height: no water
        assertEquals(1.0, s.rain[0], 1e-9)       // 0 = heaviest rain
        assertEquals(0.0, s.rain[1], 1e-9)       // 1023 = dry
    }

    @Test
    fun bundledFileHasAllFourSensorsForOct13To28() {
        val file = File("src/main/assets/" + RecordedDataset.ASSET)
        val rows = file.bufferedReader().use { RecordedDataset.parse(it) }
        assertEquals(setOf(1, 2, 3, 4), rows.keys)
        rows.values.forEach { r ->
            assertTrue("about 16 days of 5-minute readings, got ${r.size}", r.size > 4500)
            assertTrue(r.first().timeMs >= ms("2026-10-13T00:00:00+08:00"))
            assertTrue(r.last().timeMs <= ms("2026-10-29T00:00:00+08:00"))
        }
    }
}
