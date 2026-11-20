package com.nwbort.gview

import com.nwbort.gview.data.Fit
import com.nwbort.gview.data.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitTest {
    /** 5.5 km at 5:00/km heading north from Sydney, HR rising, one point a second. */
    private val points = (0..1650).map { s ->
        FitWriter.Point(
            t = 1_000_000_000L + s,
            lat = -33.86 + s * 3.333 / 111_320.0,
            lon = 151.21,
            distM = s * 3.3333,
            speed = 3.3333,
            hr = if (s == 100) null else 130 + s / 60,
            altM = 20.0 + s / 100.0,
        )
    }

    @Test
    fun decodes_records_from_a_zipped_fit_file() {
        val fit = Fit.unzip(FitWriter.zip(FitWriter.fit(points)))
        assertNotNull(fit)
        val records = Fit.records(fit!!)
        assertEquals(points.size, records.size)
        val r = records[10]
        assertEquals(-33.86 + 10 * 3.333 / 111_320.0, r.lat!!, 1e-6)
        assertEquals(151.21, r.lon!!, 1e-6)
        assertEquals(33.33, r.dist!!, 0.01)
        assertEquals(3.333, r.speed!!, 0.001)
        assertEquals(130, r.hr)
        assertEquals(20.1, r.alt!!, 0.2)
        assertNull("0xFF heart rate is FIT's invalid value", records[100].hr)
    }

    @Test
    fun builds_a_track_with_kilometre_splits() {
        val track = Fit.track(Fit.records(FitWriter.fit(points)), Sport.RUN)
        assertTrue(track.hasRoute)
        assertTrue(track.t.size <= 601)
        // Five whole kilometres and a 0.5 km remainder, each at 5:00/km.
        assertEquals(6, track.splits.size)
        assertEquals(1000.0, track.splits[0].distanceM, 4.0)
        assertEquals(300.0, track.splits[0].seconds, 2.0)
        assertEquals(500.0, track.splits.last().distanceM, 5.0)
        assertEquals(3.0, track.splits[0].elevChangeM!!, 0.5)
    }

    @Test
    fun rides_split_every_five_kilometres() {
        val ride = (0..900).map { s -> FitWriter.Point(2_000L + s, null, null, s * 12.0, 12.0, null, 0.0) }
        val track = Fit.track(Fit.records(FitWriter.fit(ride)), Sport.RIDE)
        assertEquals(3, track.splits.size) // 5 km, 5 km, 0.8 km
        assertTrue("indoor rides have no route", !track.hasRoute)
    }

    @Test
    fun smoothing_ignores_stops_and_follows_time_not_samples() {
        // 3 m/s, a two-minute stop, then 3 m/s again, recorded every 5 s.
        val recs = (0..120).map { i ->
            val t = i * 5L
            val stopped = t in 300..420
            Fit.Record(t, null, null, null, if (stopped) 0.0 else 3.0, null, null)
        }
        val s = Fit.smoothSpeed(recs, 30)
        assertNull("standing still has no pace", s[70])
        assertEquals("moving either side of the stop isn't dragged down", 3.0, s[58]!!, 1e-9)
        assertEquals(3.0, s[86]!!, 1e-9)
    }

    @Test
    fun trimmed_activities_leave_out_what_was_cut() {
        // A run of 9456 s from 22:07:17 UTC, then the watch left going for a drive at 25 m/s.
        val startMs = 1_783_721_237_000L
        val t0 = startMs / 1000 - 631_065_600L
        val recs = (0..12_000L).map { s ->
            val d = if (s <= 9456) s * 1.7 else 9456 * 1.7 + (s - 9456) * 25.0
            Fit.Record(t0 + s, null, null, d, if (s <= 9456) 1.7 else 25.0, null, null)
        }
        val kept = Fit.trim(recs, startMs, 9456.0)
        assertEquals(9457, kept.size)
        assertTrue(kept.all { it.speed == 1.7 })
        // A trimmed start restarts distance from zero.
        val late = Fit.trim(recs, startMs + 1_000_000, 1000.0)
        assertEquals(0.0, late.first().dist!!, 1e-9)
        assertEquals(1000 * 1.7, late.last().dist!!, 1e-6)
        // A window that misses the file, or no window at all, keeps everything.
        assertEquals(recs.size, Fit.trim(recs, 0L, 9456.0).size)
        assertEquals(recs.size, Fit.trim(recs, null, null).size)
    }

    @Test
    fun garbage_is_not_a_fit_file() {
        assertTrue(Fit.records(ByteArray(40) { 7 }).isEmpty())
        assertNull(Fit.unzip(FitWriter.zip("hello".toByteArray(), "readme.txt")))
    }
}
