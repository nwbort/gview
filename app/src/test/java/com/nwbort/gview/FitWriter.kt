package com.nwbort.gview

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes minimal FIT activity files (record messages only), for tests and the fake Garmin. */
object FitWriter {
    class Point(val t: Long, val lat: Double?, val lon: Double?, val distM: Double, val speed: Double, val hr: Int?, val altM: Double)

    fun fit(points: List<Point>): ByteArray {
        val body = ByteArrayOutputStream()
        // Definition for local type 0 = record (global 20), little-endian.
        body.write(0x40); body.write(0); body.write(0)
        u16(body, 20)
        val fields = listOf(253 to (4 to 0x86), 0 to (4 to 0x85), 1 to (4 to 0x85), 3 to (1 to 0x02), 5 to (4 to 0x86), 73 to (4 to 0x86), 78 to (4 to 0x86))
        body.write(fields.size)
        fields.forEach { (num, st) -> body.write(num); body.write(st.first); body.write(st.second) }
        points.forEach { p ->
            body.write(0x00)
            u32(body, p.t)
            u32(body, p.lat?.let { (it / 180.0 * 2147483648.0).toLong() } ?: 0x7FFFFFFF)
            u32(body, p.lon?.let { (it / 180.0 * 2147483648.0).toLong() } ?: 0x7FFFFFFF)
            body.write(p.hr ?: 0xFF)
            u32(body, (p.distM * 100).toLong())
            u32(body, (p.speed * 1000).toLong())
            u32(body, ((p.altM + 500) * 5).toLong())
        }
        val data = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(14); out.write(0x20); u16(out, 2132); u32(out, data.size.toLong())
        out.write(".FIT".toByteArray()); u16(out, 0)
        out.write(data)
        u16(out, 0) // CRC, not checked
        return out.toByteArray()
    }

    fun zip(fit: ByteArray, name: String = "activity.fit"): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z -> z.putNextEntry(ZipEntry(name)); z.write(fit); z.closeEntry() }
        return bytes.toByteArray()
    }

    private fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    private fun u32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
}
