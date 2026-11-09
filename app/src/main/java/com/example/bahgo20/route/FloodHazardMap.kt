package com.example.bahgo20.route

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * Looks up the Project NOAH 5-year flood hazard (Low / Medium / High) at points on the map,
 * from the same PMTiles file the Home map draws. Only the few tiles under a route are read
 * (a few tens of KB each), never the whole file: from the local copy if the app has one,
 * otherwise with partial downloads from Hugging Face.
 */
class FloodHazardMap(private val source: ByteSource) {

    /** Reads [length] bytes at [offset] of the PMTiles file. */
    fun interface ByteSource { fun read(offset: Long, length: Int): ByteArray }

    private var header: PmTiles.Header? = null
    private val directories = HashMap<Long, List<PmTiles.Entry>>()
    private val tiles = object : LinkedHashMap<Long, List<Mvt.Feature>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Mvt.Feature>>?) = size > 24
    }

    /** Hazard at each point (same order). Throws if the map can't be read (e.g. no internet). */
    @Synchronized
    fun hazards(points: List<LatLon>): List<Hazard> {
        val h = header ?: PmTiles.parseHeader(source.read(0, PmTiles.HEADER_SIZE)).also { header = it }
        val z = minOf(ZOOM, h.maxZoom)
        val n = 1 shl z
        return points.map { p ->
            val xf = (p.lon + 180.0) / 360.0 * n
            val yf = (1.0 - asinh(tan(p.lat * PI / 180.0)) / PI) / 2.0 * n
            val x = floor(xf).toInt(); val y = floor(yf).toInt()
            val features = tileFeatures(h, z, x, y)
            if (features.isEmpty()) return@map Hazard.NONE
            val extent = features.first().extent
            val px = (xf - x) * extent; val py = (yf - y) * extent
            Hazard.of(features.filter { it.contains(px, py) }.maxOfOrNull { it.value } ?: 0)
        }
    }

    private fun tileFeatures(h: PmTiles.Header, z: Int, x: Int, y: Int): List<Mvt.Feature> {
        val id = PmTiles.tileId(z, x, y)
        tiles[id]?.let { return it }
        val raw = findTile(h, id)
        val features = if (raw == null) emptyList() else
            Mvt.decode(PmTiles.decompress(raw, h.tileCompression), LAYER, KEY)
        tiles[id] = features
        return features
    }

    /** The tile's compressed bytes, or null if the file has no tile there (no flood area). */
    private fun findTile(h: PmTiles.Header, id: Long): ByteArray? {
        var offset = h.rootOffset
        var length = h.rootLength
        repeat(4) { depth ->
            val dir = directories.getOrPut(offset) {
                PmTiles.parseDirectory(PmTiles.decompress(source.read(offset, length.toInt()), h.internalCompression))
            }
            val e = PmTiles.find(dir, id) ?: return null
            if (e.runLength > 0) return source.read(h.dataOffset + e.offset, e.length)
            // A leaf directory: look there next
            offset = h.leafOffset + e.offset
            length = e.length.toLong()
            if (depth == 3) return null
        }
        return null
    }

    companion object {
        private const val TAG = "FloodHazardMap"
        /** Zoom with the most detail in the NOAH file (z14 tile ≈ 2.4 km, 4096 units ≈ 0.6 m). */
        const val ZOOM = 14
        const val LAYER = "flood_5yr"
        const val KEY = "Var"
        const val LOCAL_ASSET = "taguig_flood_5yr.pmtiles"
        const val REMOTE_URL = "https://huggingface.co/datasets/bettergovph/project-noah-hazard-maps/resolve/main/PMTiles/layers/flood_5yr.pmtiles"

        @Volatile private var instance: FloodHazardMap? = null

        /** Shared reader: the local copy if the app ships one (same as the Home map), else Hugging Face. */
        fun get(context: Context, http: OkHttpClient): FloodHazardMap = instance ?: synchronized(this) {
            instance ?: FloodHazardMap(localSource(context) ?: httpSource(http, REMOTE_URL)).also { instance = it }
        }

        private fun localSource(context: Context): ByteSource? {
            val file = File(context.cacheDir, LOCAL_ASSET)
            if (!file.exists() || file.length() == 0L) {
                try {
                    context.assets.open(LOCAL_ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
                } catch (e: Exception) {
                    file.delete()
                    return null
                }
            }
            return ByteSource { offset, length ->
                RandomAccessFile(file, "r").use { f ->
                    ByteArray(length).also { f.seek(offset); f.readFully(it) }
                }
            }
        }

        private fun httpSource(http: OkHttpClient, url: String) = ByteSource { offset, length ->
            val request = Request.Builder().url(url)
                .header("Range", "bytes=$offset-${offset + length - 1}")
                .header("User-Agent", "BahGo20FloodApp/1.0 (Android)")
                .build()
            http.newCall(request).execute().use { r ->
                // 206 = just the bytes asked for. Anything else could be the whole 500 MB file: stop.
                if (r.code != 206) error("NOAH map: HTTP ${r.code}")
                val bytes = r.body?.bytes() ?: error("NOAH map: empty response")
                if (bytes.size != length) Log.w(TAG, "Asked for $length bytes, got ${bytes.size}")
                bytes
            }
        }
    }
}

/** Reading the PMTiles v3 format (header, directories, Hilbert tile ids). */
object PmTiles {
    const val HEADER_SIZE = 127

    class Header(
        val rootOffset: Long, val rootLength: Long,
        val leafOffset: Long, val dataOffset: Long,
        val internalCompression: Int, val tileCompression: Int,
        val maxZoom: Int
    )

    class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    fun parseHeader(b: ByteArray): Header {
        require(b.size >= HEADER_SIZE && String(b, 0, 7) == "PMTiles" && b[7].toInt() == 3) { "not a PMTiles v3 file" }
        fun u64(at: Int): Long = (0 until 8).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
        return Header(
            rootOffset = u64(8), rootLength = u64(16),
            leafOffset = u64(40), dataOffset = u64(56),
            internalCompression = b[97].toInt(), tileCompression = b[98].toInt(),
            maxZoom = b[101].toInt()
        )
    }

    /** Tile id along the Hilbert curve, as defined by the PMTiles spec. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        var acc = 0L
        for (t in 0 until z) acc += 1L shl (2 * t)
        var tx = x.toLong(); var ty = y.toLong()
        var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if ((tx and s) > 0) 1L else 0L
            val ry = if ((ty and s) > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val tmp = tx; tx = ty; ty = tmp
            }
            s /= 2
        }
        return acc + d
    }

    fun parseDirectory(b: ByteArray): List<Entry> {
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val c = b[pos++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return result
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n); var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        val runs = IntArray(n) { varint().toInt() }
        val lengths = IntArray(n) { varint().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = varint()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    /** The entry covering [tileId]: the last one starting at or before it (a tile run or a leaf directory). */
    fun find(entries: List<Entry>, tileId: Long): Entry? {
        var lo = 0; var hi = entries.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].tileId <= tileId) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        val e = entries[found]
        if (e.runLength == 0) return e                        // leaf directory
        return if (tileId < e.tileId + e.runLength) e else null
    }

    /** 1 = none, 2 = gzip (the only ones tippecanoe writes). */
    fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
        0, 1 -> b
        2 -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        else -> error("PMTiles compression $compression not supported")
    }
}

/** Minimal Mapbox Vector Tile reader: polygons of one layer and one number property. */
object Mvt {

    /** One polygon feature. [rings] hold x,y pairs in tile units (0..[extent]). */
    class Feature(val value: Int, val rings: List<IntArray>, val extent: Int) {
        private val boxes = rings.map { r ->
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            for (i in r.indices step 2) {
                minX = minOf(minX, r[i]); maxX = maxOf(maxX, r[i]); minY = minOf(minY, r[i + 1]); maxY = maxOf(maxY, r[i + 1])
            }
            intArrayOf(minX, minY, maxX, maxY)
        }

        /** Even-odd test over all rings, so holes inside a polygon count as outside. */
        fun contains(x: Double, y: Double): Boolean {
            var inside = false
            rings.forEachIndexed { k, r ->
                val box = boxes[k]
                if (x < box[0] || x > box[2] || y < box[1] || y > box[3]) return@forEachIndexed
                val n = r.size / 2
                var j = n - 1
                for (i in 0 until n) {
                    val xi = r[2 * i].toDouble(); val yi = r[2 * i + 1].toDouble()
                    val xj = r[2 * j].toDouble(); val yj = r[2 * j + 1].toDouble()
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }
    }

    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val c = b[pos++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return result
                shift += 7
            }
        }
        fun sub(): Reader { val len = varint().toInt(); val r = Reader(b, pos, pos + len); pos += len; return r }
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> error("bad wire type $wireType")
            }
        }
    }

    private fun zigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

    fun decode(tile: ByteArray, layerName: String, key: String): List<Feature> {
        val out = mutableListOf<Feature>()
        val r = Reader(tile)
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag shr 3 == 3 && tag and 7 == 2) readLayer(r.sub(), layerName, key, out) else r.skip(tag and 7)
        }
        return out
    }

    private fun readLayer(r: Reader, layerName: String, key: String, out: MutableList<Feature>) {
        var name = ""
        var extent = 4096
        val keys = mutableListOf<String>()
        val values = mutableListOf<Double?>()
        val features = mutableListOf<Reader>()
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                1 -> { val s = r.sub(); name = String(s.b, s.pos, s.end - s.pos) }
                2 -> features += r.sub()
                3 -> { val s = r.sub(); keys += String(s.b, s.pos, s.end - s.pos) }
                4 -> values += readValue(r.sub())
                5 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        if (name != layerName) return
        val keyIndex = keys.indexOf(key)
        for (f in features) {
            var type = 0
            var tags = LongArray(0)
            var geometry = LongArray(0)
            while (f.more()) {
                val tag = f.varint().toInt()
                when (tag shr 3) {
                    2 -> tags = packed(f.sub())
                    3 -> type = f.varint().toInt()
                    4 -> geometry = packed(f.sub())
                    else -> f.skip(tag and 7)
                }
            }
            if (type != 3) continue  // polygons only
            var value: Double? = null
            for (i in 0 until tags.size - 1 step 2) {
                if (tags[i].toInt() == keyIndex) value = values.getOrNull(tags[i + 1].toInt())
            }
            out += Feature(value?.toInt() ?: 0, rings(geometry), extent)
        }
    }

    private fun packed(r: Reader): LongArray {
        val list = ArrayList<Long>()
        while (r.more()) list += r.varint()
        return list.toLongArray()
    }

    /** Number values only (string values come back as their number, if they are one). */
    private fun readValue(r: Reader): Double? {
        var v: Double? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                1 -> { val s = r.sub(); v = String(s.b, s.pos, s.end - s.pos).toDoubleOrNull() }
                2 -> { v = java.lang.Float.intBitsToFloat(le(r, 4).toInt()).toDouble() }
                3 -> { v = java.lang.Double.longBitsToDouble(le(r, 8)) }
                4, 5 -> v = r.varint().toDouble()
                6 -> v = zigzag(r.varint()).toDouble()
                7 -> v = r.varint().toDouble()
                else -> r.skip(tag and 7)
            }
        }
        return v
    }

    private fun le(r: Reader, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((r.b[r.pos + i].toLong() and 0xFF) shl (8 * i))
        r.pos += n
        return v
    }

    /** MoveTo / LineTo / ClosePath commands → rings of absolute x,y pairs. */
    private fun rings(g: LongArray): List<IntArray> {
        val rings = mutableListOf<IntArray>()
        var cur = ArrayList<Int>()
        var x = 0; var y = 0
        var i = 0
        while (i < g.size) {
            val cmd = (g[i] and 7).toInt()
            val count = (g[i] shr 3).toInt()
            i++
            when (cmd) {
                1, 2 -> repeat(count) {
                    if (cmd == 1 && cur.isNotEmpty()) { rings += cur.toIntArray(); cur = ArrayList() }
                    x += zigzag(g[i]).toInt(); y += zigzag(g[i + 1]).toInt()
                    cur += x; cur += y
                    i += 2
                }
                7 -> { if (cur.size >= 6) rings += cur.toIntArray(); cur = ArrayList() }
            }
        }
        if (cur.size >= 6) rings += cur.toIntArray()
        return rings
    }
}
