package com.nwbort.gview.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * A small decoder for Garmin FIT activity files: just enough to pull out the
 * per-second records (position, distance, speed, heart rate, altitude). Garmin's
 * Personal API has no route or stream endpoint, so the route and charts come
 * from the activity's original file.
 */
object Fit {
    class Record(
        val time: Long,
        val lat: Double?,
        val lon: Double?,
        val dist: Double?,
        val speed: Double?,
        val hr: Int?,
        val alt: Double?,
    )

    /** The FIT file inside Garmin's download ZIP, or the bytes themselves if they're already FIT. */
    fun unzip(bytes: ByteArray): ByteArray? {
        if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") return bytes
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return null
                if (!e.isDirectory && e.name.lowercase().endsWith(".fit")) return zip.readBytes()
            }
        }
    }

    private class Def(val little: Boolean, val global: Int, val fields: IntArray, val sizes: IntArray, val types: IntArray, val devBytes: Int)

    fun records(fit: ByteArray): List<Record> {
        if (fit.size < 12) return emptyList()
        val headerSize = fit[0].toInt() and 0xFF
        if (String(fit, 8, 4, Charsets.US_ASCII) != ".FIT") return emptyList()
        val dataSize = u32(fit, 4, true)
        val end = minOf(fit.size.toLong(), headerSize + dataSize).toInt()
        val defs = arrayOfNulls<Def>(16)
        val out = ArrayList<Record>()
        var p = headerSize
        var lastTime = 0L
        while (p < end) {
            val h = fit[p].toInt() and 0xFF
            p++
            if (h and 0x80 != 0) {
                // Compressed timestamp header: a data message with a 5-bit time offset.
                val def = defs[(h shr 5) and 0x3] ?: return out
                val offset = h and 0x1F
                var t = (lastTime and 0x1F.toLong().inv()) + offset
                if (offset < (lastTime and 0x1F)) t += 0x20
                lastTime = t
                p = readData(fit, p, def, t, out) { lastTime = it }
            } else if (h and 0x40 != 0) {
                val local = h and 0x0F
                val hasDev = h and 0x20 != 0
                val little = fit[p + 1].toInt() == 0
                val global = if (little) u16(fit, p + 2, true) else u16(fit, p + 2, false)
                val n = fit[p + 4].toInt() and 0xFF
                p += 5
                val nums = IntArray(n); val sizes = IntArray(n); val types = IntArray(n)
                for (i in 0 until n) {
                    nums[i] = fit[p].toInt() and 0xFF
                    sizes[i] = fit[p + 1].toInt() and 0xFF
                    types[i] = fit[p + 2].toInt() and 0xFF
                    p += 3
                }
                var dev = 0
                if (hasDev) {
                    val nd = fit[p].toInt() and 0xFF
                    p++
                    for (i in 0 until nd) { dev += fit[p + 1].toInt() and 0xFF; p += 3 }
                }
                defs[local] = Def(little, global, nums, sizes, types, dev)
            } else {
                val def = defs[h and 0x0F] ?: return out
                p = readData(fit, p, def, null, out) { lastTime = it }
            }
            if (p > end) break
        }
        return out
    }

    private inline fun readData(fit: ByteArray, start: Int, def: Def, compressedTime: Long?, out: MutableList<Record>, onTime: (Long) -> Unit): Int {
        var p = start
        var time = compressedTime
        var lat: Double? = null; var lon: Double? = null; var dist: Double? = null
        var speed: Double? = null; var espeed: Double? = null; var hr: Int? = null
        var alt: Double? = null; var ealt: Double? = null
        for (i in def.fields.indices) {
            val size = def.sizes[i]
            if (p + size > fit.size) return fit.size + 1
            val v = value(fit, p, size, def.types[i], def.little)
            p += size
            if (v == null) continue
            val f = def.fields[i]
            if (f == 253) { time = v; onTime(v) }
            if (def.global != 20) continue
            when (f) {
                0 -> lat = v.toInt() * SEMI
                1 -> lon = v.toInt() * SEMI
                2 -> alt = v / 5.0 - 500
                3 -> hr = v.toInt()
                5 -> dist = v / 100.0
                6 -> speed = v / 1000.0
                73 -> espeed = v / 1000.0
                78 -> ealt = v / 5.0 - 500
            }
        }
        p += def.devBytes
        if (def.global == 20 && time != null) {
            out += Record(time, lat, lon, dist, espeed ?: speed, hr, ealt ?: alt)
        }
        return p
    }

    /** One field's value, or null for FIT's "invalid" sentinel and for arrays/strings. */
    private fun value(b: ByteArray, p: Int, size: Int, type: Int, little: Boolean): Long? {
        val base = type and 0x1F
        // The "z" types (0x0A, 0x0B, 0x0C) use zero as their invalid value.
        val zeroInvalid = base == 0x0A || base == 0x0B || base == 0x0C
        val v = when (base) {
            0x00, 0x02, 0x0A, 0x0D -> if (size != 1) null else (b[p].toLong() and 0xFF).takeIf { it != 0xFFL }
            0x01 -> if (size != 1) null else b[p].toLong().takeIf { it != 0x7FL }
            0x04, 0x0B -> if (size != 2) null else u16(b, p, little).toLong().takeIf { it != 0xFFFFL }
            0x03 -> if (size != 2) null else u16(b, p, little).toShort().toLong().takeIf { it != 0x7FFFL }
            0x06, 0x0C -> if (size != 4) null else u32(b, p, little).takeIf { it != 0xFFFFFFFFL }
            0x05 -> if (size != 4) null else u32(b, p, little).toInt().toLong().takeIf { it != 0x7FFFFFFFL }
            else -> null
        }
        return if (zeroInvalid && v == 0L) null else v
    }

    private fun u16(b: ByteArray, p: Int, little: Boolean): Int {
        val a = b[p].toInt() and 0xFF; val c = b[p + 1].toInt() and 0xFF
        return if (little) a or (c shl 8) else (a shl 8) or c
    }

    private fun u32(b: ByteArray, p: Int, little: Boolean): Long {
        var v = 0L
        for (i in 0 until 4) {
            val byte = b[p + if (little) i else 3 - i].toLong() and 0xFF
            v = v or (byte shl (8 * i))
        }
        return v
    }

    /** Bump when [track] changes, so routes decoded before are decoded again. */
    const val VERSION = 5

    private const val SEMI = 180.0 / 2147483648.0

    /**
     * The route, charts and splits for an activity, downsampled so a long ride
     * still draws instantly. Splits are every kilometre (5 km for rides).
     */
    fun track(records: List<Record>, sport: Sport, date: String = ""): Track {
        if (records.isEmpty()) return Track(v = VERSION)
        val t0 = records.first().time
        fun valid(r: Record) = r.lat != null && r.lon != null && abs(r.lat) <= 90 && !(r.lat == 0.0 && r.lon == 0.0)
        val pos = records.filter(::valid)
        val routeStep = max(1, (pos.size + 1499) / 1500)
        val route = pos.filterIndexed { i, _ -> i % routeStep == 0 || i == pos.lastIndex }

        // Smooth speed over a minute of time (watches record every 1-10 s), leaving out
        // standing still, so the pace chart reads as a trend rather than GPS noise and stops.
        val smooth = smoothSpeed(records, 30)
        val foot = sport == Sport.RUN || sport == Sport.WALK
        val hilly = foot && records.mapNotNull { it.alt }.let { it.size > 10 && it.max() - it.min() > 30 }
        val gap = if (hilly) smoothValues(records, Analysis.gapSpeeds(records), 30) else null
        val step = max(1, (records.size + 599) / 600)
        val idx = records.indices.filter { it % step == 0 || it == records.lastIndex }

        return Track(
            v = VERSION,
            lat = route.map { it.lat!! },
            lon = route.map { it.lon!! },
            t = idx.map { (records[it].time - t0).toInt() },
            dist = idx.map { records[it].dist },
            hr = idx.map { records[it].hr },
            speed = idx.map { smooth[it] },
            alt = idx.map { records[it].alt },
            sLat = idx.map { records[it].takeIf(::valid)?.lat },
            sLon = idx.map { records[it].takeIf(::valid)?.lon },
            gap = gap?.let { g -> idx.map { g[it] } } ?: emptyList(),
            stats = if (sport == Sport.RUN) Analysis.runStats(date, records) else null,
            splits = splits(records, if (sport == Sport.RIDE) 5000.0 else 1000.0),
        )
    }

    /** Mean of [values] within [halfWindowS] seconds either side of each record; null where the value is. */
    fun smoothValues(records: List<Record>, values: List<Double?>, halfWindowS: Long): List<Double?> {
        var lo = 0; var hi = 0
        var sum = 0.0; var n = 0
        return records.indices.map { i ->
            val t = records[i].time
            while (hi < records.size && records[hi].time <= t + halfWindowS) { values[hi]?.let { sum += it; n++ }; hi++ }
            while (records[lo].time < t - halfWindowS) { values[lo]?.let { sum -= it; n-- }; lo++ }
            if (values[i] == null || n == 0) null else sum / n
        }
    }

    /** Mean moving speed within [halfWindowS] seconds either side of each record; null while stopped. */
    fun smoothSpeed(records: List<Record>, halfWindowS: Long): List<Double?> {
        var lo = 0; var hi = 0
        var sum = 0.0; var n = 0
        fun moving(r: Record) = r.speed?.takeIf { it >= MOVING }
        return records.indices.map { i ->
            val t = records[i].time
            while (hi < records.size && records[hi].time <= t + halfWindowS) { moving(records[hi])?.let { sum += it; n++ }; hi++ }
            while (records[lo].time < t - halfWindowS) { moving(records[lo])?.let { sum -= it; n-- }; lo++ }
            if (moving(records[i]) == null || n == 0) null else sum / n
        }
    }

    /** Below this (m/s, about 33 min/km) counts as standing still. */
    private const val MOVING = 0.5

    fun splits(records: List<Record>, every: Double): List<Split> {
        val withDist = records.filter { it.dist != null }
        if (withDist.size < 2 || withDist.last().dist!! < every / 2) return emptyList()
        val out = mutableListOf<Split>()
        var from = withDist.first()
        var hrSum = 0L; var hrN = 0
        var next = every
        for (r in withDist) {
            r.hr?.let { hrSum += it; hrN++ }
            if (r.dist!! >= next) {
                out += Split(r.dist - from.dist!!, (r.time - from.time).toDouble(), if (hrN > 0) (hrSum / hrN).toInt() else null, elev(from, r))
                from = r; hrSum = 0; hrN = 0
                next += every
            }
        }
        val last = withDist.last()
        val rest = last.dist!! - from.dist!!
        if (rest > every * 0.05) {
            out += Split(rest, (last.time - from.time).toDouble(), if (hrN > 0) (hrSum / hrN).toInt() else null, elev(from, last))
        }
        return out
    }

    private fun elev(a: Record, b: Record): Double? = if (a.alt != null && b.alt != null) b.alt - a.alt else null
}
