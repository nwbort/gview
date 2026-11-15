package com.nwbort.gview

import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Analysis
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.Fit
import com.nwbort.gview.data.Hrv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnalysisTest {
    @Test
    fun grade_factor_is_a_gentle_u() {
        assertEquals(1.0, Analysis.gradeFactor(0.0), 1e-9)
        assertEquals(1.32, Analysis.gradeFactor(0.10), 1e-9)
        assertEquals(0.92, Analysis.gradeFactor(-0.10), 1e-9)
        assertTrue("steep descents cost more than gentle ones", Analysis.gradeFactor(-0.3) > Analysis.gradeFactor(-0.1))
    }

    /** An hour at 3 m/s, 1 Hz; [hr] and [alt] by second. */
    private fun run(hr: (Int) -> Int?, alt: (Int) -> Double = { 100.0 }, secs: Int = 3600) =
        (0..secs).map { s -> Fit.Record(s.toLong(), null, null, s * 3.0, 3.0, hr(s), alt(s)) }

    @Test
    fun steady_run_has_no_drift_and_a_rising_one_does() {
        val steady = Analysis.runStats("2026-11-01", run({ 150 }))!!
        assertEquals(0.0, steady.driftPct!!, 0.01)
        assertEquals(3.0, steady.gapMps!!, 1e-6)
        assertEquals(3.0 * 60 / 150, steady.efficiency!!, 1e-6)

        // HR climbs from 140 to 160 at the same pace: about 9% less speed per beat late on.
        val drifting = Analysis.runStats("2026-11-01", run({ 140 + it / 180 }))!!
        assertTrue("drift ${drifting.driftPct}", drifting.driftPct!! in 5.0..12.0)
    }

    @Test
    fun climbing_counts_for_more_than_its_pace() {
        // A steady 5% climb: 3 m/s up it is worth more on the flat.
        val climb = Analysis.runStats("2026-11-01", run({ 150 }, alt = { it * 3.0 * 0.05 }))!!
        assertEquals(3.0 * Analysis.gradeFactor(0.05), climb.gapMps!!, 0.02)
    }

    @Test
    fun short_runs_and_runs_without_hr_say_less() {
        assertNull(Analysis.runStats("2026-11-01", run({ 150 }, secs = 300)))
        val noHr = Analysis.runStats("2026-11-01", run({ null }))!!
        assertNull(noHr.avgHr); assertNull(noHr.efficiency); assertNull(noHr.driftPct)
    }

    @Test
    fun fitness_and_fatigue_follow_the_load() {
        val today = LocalDate.of(2026, 11, 15)
        val c = Cache(activities = listOf(Activity(1, start = "$today 07:00:00", trainingLoad = 140.0, durationS = 3600.0)))
        val day = Analysis.loadCurve(c, today).single()
        assertEquals(140.0 / 42, day.fitness, 1e-9)
        assertEquals(140.0 / 7, day.fatigue, 1e-9)
        assertTrue(day.form < 0)
    }

    @Test
    fun recovery_notices_hrv_dropping_after_hard_days() {
        val today = LocalDate.of(2026, 11, 15)
        val days = (0L until 120L).map { today.minusDays(it) }
        // Hard sessions every Saturday, easy ones Tuesday and Thursday.
        val acts = days.mapNotNull { d ->
            when (d.dayOfWeek.value) {
                6 -> Activity(d.toEpochDay(), start = "$d 07:00:00", trainingLoad = 300.0)
                2, 4 -> Activity(d.toEpochDay(), start = "$d 07:00:00", trainingLoad = 80.0)
                else -> null
            }
        }
        // HRV 60 normally, 51 the night after a Saturday (filed on Sunday).
        val hrv = days.associate { d -> d.toString() to Hrv(d.toString(), lastNight = if (d.dayOfWeek.value == 7) 51 else 60) }
        val found = Analysis.recovery(Cache(activities = acts, hrv = hrv), today)
        val dip = found.firstOrNull { it.headline.startsWith("HRV dips") }
        assertNotNull("findings: $found", dip)
        assertTrue(dip!!.headline, dip.headline.contains("15%"))
    }
}
