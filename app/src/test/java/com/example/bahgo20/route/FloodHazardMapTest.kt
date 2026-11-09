package com.example.bahgo20.route

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Reads a real Project NOAH 5-year tile over Palar, Taguig (z14/13701/7523, copied from the
 * Hugging Face PMTiles file) through a tiny in-memory PMTiles file.
 */
class FloodHazardMapTest {

    private val tile: ByteArray =
        javaClass.classLoader!!.getResourceAsStream("noah_flood_5yr_14_13701_7523.mvt")!!.readBytes()

    @Test
    fun tileIdsFollowTheSpec() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
    }

    @Test
    fun decodesTheThreeHazardClasses() {
        val features = Mvt.decode(tile, "flood_5yr", "Var")
        assertEquals(setOf(1, 2, 3), features.map { it.value }.toSet())
    }

    @Test
    fun hazardAtKnownPoints() {
        val map = FloodHazardMap(singleTileFile(14, 13701, 7523, tile))
        val points = listOf(
            LatLon(14.5220, 121.0480),     // outside flood areas
            LatLon(14.5220, 121.0520),     // low
            LatLon(14.5310, 121.0570),     // medium
            LatLon(14.5230, 121.0510),     // high
            LatLon(14.532438, 121.055438)  // Samaritan Street sensor: low
        )
        assertEquals(
            listOf(Hazard.NONE, Hazard.LOW, Hazard.MEDIUM, Hazard.HIGH, Hazard.LOW),
            map.hazards(points)
        )
    }

    @Test
    fun missingTileMeansNoHazard() {
        val map = FloodHazardMap(singleTileFile(14, 13701, 7523, tile))
        assertEquals(listOf(Hazard.NONE), map.hazards(listOf(LatLon(14.60, 121.20))))
    }

    // ── A one-tile PMTiles v3 file, gzip-compressed like the real one ──

    private fun singleTileFile(z: Int, x: Int, y: Int, mvt: ByteArray): FloodHazardMap.ByteSource {
        val data = gzip(mvt)
        val dir = ByteArrayOutputStream().apply {
            varint(1)                          // entries
            varint(PmTiles.tileId(z, x, y))    // tile id (delta)
            varint(1)                          // run length
            varint(data.size.toLong())         // length
            varint(1)                          // offset + 1
        }.toByteArray().let(::gzip)
        val header = ByteArray(PmTiles.HEADER_SIZE)
        "PMTiles".toByteArray().copyInto(header)
        header[7] = 3
        fun put(at: Int, v: Long) { for (i in 0 until 8) header[at + i] = (v ushr (8 * i)).toByte() }
        val rootOffset = PmTiles.HEADER_SIZE.toLong()
        val dataOffset = rootOffset + dir.size
        put(8, rootOffset); put(16, dir.size.toLong())
        put(40, dataOffset); put(56, dataOffset)
        header[97] = 2; header[98] = 2; header[99] = 1; header[101] = z.toByte()
        val file = header + dir + data
        return FloodHazardMap.ByteSource { offset, length -> file.copyOfRange(offset.toInt(), offset.toInt() + length) }
    }

    private fun ByteArrayOutputStream.varint(v: Long) {
        var n = v
        while (n >= 0x80) { write(((n and 0x7F) or 0x80).toInt()); n = n ushr 7 }
        write(n.toInt())
    }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()
}
