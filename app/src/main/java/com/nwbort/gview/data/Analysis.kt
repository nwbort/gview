package com.nwbort.gview.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a run's file says about the runner's aerobic engine, worked out once
 * per run and kept in the cache.
 */
@Serializable
data class RunStats(
    val date: String,
    /** Moving time, seconds. */
    val movingS: Double,
    /** Mean grade-adjusted speed while moving, m/s: the flat-ground equivalent. */
    val gapMps: Double?,
    /** Mean heart rate while moving. */
    val avgHr: Double?,
    /**
     * Heart-rate drift (aerobic decoupling): how much less ground each heartbeat
     * bought in the second half than the first, percent. Null for short runs.
     */
    val driftPct: Double? = null,
    /** False when pace varied too much (intervals, repeats) for drift to mean anything. */
    val steady: Boolean = true,
) {
    /** Grade-adjusted metres per minute per beat: higher is fitter. */
    val efficiency: Double? get() = if (gapMps != null && avgHr != null && avgHr > 60) gapMps * 60 / avgHr else null
}

object Analysis {
    // ---------------- Grade-adjusted pace ----------------

    /**
     * How much harder running on a slope is than on the flat, so a pace uphill
     * can be compared with one on level ground. [grade] is rise over run (0.1
     * for 10%). A gentle U fitted to real-world grade-adjusted pace: about
     * ×1.32 at +10%, ×0.92 at −10%, easiest near −8%, and steep descents cost
     * more again. (Lab energy-cost curves overstate both ends for running.)
     */
    fun gradeFactor(grade: Double): Double {
        val i = grade.coerceIn(-0.4, 0.4)
        return 1 + 2 * i + 12 * i * i
    }

    /** Slope at each record, over ~50 m of distance either side so altitude noise washes out. */
    fun grades(records: List<Fit.Record>, halfWindowM: Double = 25.0): List<Double?> {
        var lo = 0; var hi = 0
        return records.indices.map { i ->
            val d = records[i].dist ?: return@map null
            while (hi < records.lastIndex && (records[hi].dist ?: d) < d + halfWindowM) hi++
            while (lo < i && (records[lo + 1].dist ?: d) <= d - halfWindowM) lo++
            val a = records[lo]; val b = records[hi]
            val run = (b.dist ?: return@map null) - (a.dist ?: return@map null)
            if (run < halfWindowM || a.alt == null || b.alt == null) null else (b.alt - a.alt) / run
        }
    }

    /** Speed adjusted for slope at each record, m/s; null where not moving or unknown. */
    fun gapSpeeds(records: List<Fit.Record>): List<Double?> {
        val g = grades(records)
        return records.indices.map { i ->
            val s = records[i].speed?.takeIf { it >= MOVING } ?: return@map null
            s * gradeFactor(g[i] ?: 0.0)
        }
    }

    /** Grade-adjusted speed, efficiency and drift for one run. */
    fun runStats(date: String, records: List<Fit.Record>): RunStats? {
        if (records.size < 30) return null
        val gap = gapSpeeds(records)
        // Each record stands for the time until the next one (capped, so pauses don't count).
        val dt = records.indices.map { i -> if (i == records.lastIndex) 1.0 else (records[i + 1].time - records[i].time).coerceIn(0, 10).toDouble() }
        val moving = records.indices.filter { gap[it] != null }
        val movingS = moving.sumOf { dt[it] }
        if (movingS < 10 * 60) return null
        val gapMps = moving.sumOf { gap[it]!! * dt[it] } / movingS
        val withHr = moving.filter { records[it].hr != null }
        val hrS = withHr.sumOf { dt[it] }
        val avgHr = if (hrS > movingS * 0.8) withHr.sumOf { records[it].hr!! * dt[it] } / hrS else null

        // Drift: compare the two halves of moving time, after a 10-minute warm-up.
        var drift: Double? = null
        if (avgHr != null && movingS >= 45 * 60) {
            var elapsed = 0.0
            val afterWarmup = mutableListOf<Int>()
            for (i in moving) { elapsed += dt[i]; if (elapsed > 10 * 60) afterWarmup += i }
            val total = afterWarmup.sumOf { dt[it] }
            // Only a steady run says anything about drift: intervals, hill repeats and
            // tempo blocks change heart rate for reasons of their own. Steady here means
            // grade-adjusted pace within about 12% across 2-minute blocks.
            val blocks = mutableListOf<Double>()
            var bt = 0.0; var bd = 0.0
            for (i in afterWarmup) { bt += dt[i]; bd += gap[i]!! * dt[i]; if (bt >= 120) { blocks += bd / bt; bt = 0.0; bd = 0.0 } }
            val mean = blocks.average()
            val cv = if (blocks.size < 8) 1.0 else kotlin.math.sqrt(blocks.sumOf { (it - mean) * (it - mean) } / blocks.size) / mean
            if (cv > STEADY_CV) return RunStats(date, movingS, gapMps, avgHr, null, steady = false)
            var acc = 0.0
            val first = mutableListOf<Int>(); val second = mutableListOf<Int>()
            for (i in afterWarmup) { (if (acc < total / 2) first else second) += i; acc += dt[i] }
            fun ef(ix: List<Int>): Double? {
                val h = ix.filter { records[it].hr != null }
                val t = h.sumOf { dt[it] }
                if (t < 5 * 60) return null
                return (h.sumOf { gap[it]!! * dt[it] } / t) / (h.sumOf { records[it].hr!! * dt[it] } / t)
            }
            val a = ef(first); val b = ef(second)
            if (a != null && b != null && a > 0) drift = (a - b) / a * 100
        }
        return RunStats(date, movingS, gapMps, avgHr, drift)
    }

    private const val MOVING = 0.5
    private const val STEADY_CV = 0.12

    // ---------------- Training load: fitness, fatigue, form ----------------

    data class LoadDay(val date: LocalDate, val load: Double, val fitness: Double, val fatigue: Double) {
        /** Fitness minus fatigue: positive is fresh, well negative is digging in. */
        val form: Double get() = fitness - fatigue
    }

    /**
     * A single activity's load: Garmin's own figure when it has one, else a
     * heart-rate estimate (Banister TRIMP) on roughly the same scale.
     */
    fun load(a: Activity, restingHr: Double, maxHr: Double): Double {
        a.trainingLoad?.let { return it }
        val minutes = (a.timeS ?: return 0.0) / 60
        val hr = a.avgHr ?: return minutes * 0.5 // no HR: count it as very easy
        val hrr = ((hr - restingHr) / (maxHr - restingHr)).coerceIn(0.0, 1.0)
        return minutes * hrr * 0.64 * exp(1.92 * hrr)
    }

    /**
     * Daily load with its 42-day (fitness) and 7-day (fatigue) exponentially
     * weighted averages, the usual impulse-response model. From the first
     * activity to [today].
     */
    fun loadCurve(c: Cache, today: LocalDate = LocalDate.now()): List<LoadDay> {
        val acts = c.activities
        if (acts.isEmpty()) return emptyList()
        // The max and resting HR your zones are set from, where Garmin has them.
        val rest = c.hrZones?.resting?.toDouble()
            ?: c.summaries.values.mapNotNull { it.restingHr?.takeIf { r -> r > 0 } }.let { if (it.isEmpty()) 50.0 else it.average() }
        val maxHr = (c.hrZones?.max?.toDouble() ?: acts.mapNotNull { it.maxHr }.maxOrNull() ?: 185.0).coerceAtLeast(rest + 40)
        val byDay = acts.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { load(it, rest, maxHr) } }
        var fit = 0.0; var fat = 0.0
        val out = mutableListOf<LoadDay>()
        var d = acts.minOf { it.date }
        while (!d.isAfter(today)) {
            val l = byDay[d] ?: 0.0
            fit += (l - fit) / 42
            fat += (l - fat) / 7
            out += LoadDay(d, l, fit, fat)
            d = d.plusDays(1)
        }
        return out
    }

    fun formLabel(form: Double): String = when {
        form > 5 -> "Fresh"
        form > -10 -> "Steady"
        form > -30 -> "Building"
        else -> "Overreaching"
    }

    // ---------------- Recovery ----------------

    data class Finding(val headline: String, val detail: String, val good: Boolean? = null)

    /** Median of the 28 days before [d] (not counting [d]), needing at least 10 of them. */
    private fun baseline(series: Map<LocalDate, Double>, d: LocalDate): Double? {
        val v = (1..28).mapNotNull { series[d.minusDays(it.toLong())] }.sorted()
        return if (v.size < 10) null else v[v.size / 2]
    }

    /**
     * Plain-language findings about how HRV, resting heart rate and sleep respond
     * to training and to the day. Only what the data clearly shows: each needs
     * enough days on both sides of the comparison.
     */
    fun recovery(c: Cache, today: LocalDate = LocalDate.now()): List<Finding> {
        val curve = loadCurve(c, today)
        if (curve.isEmpty()) return emptyList()
        val loads = curve.associate { it.date to it.load }
        val hrv = c.hrv.values.mapNotNull { h -> h.lastNight?.let { LocalDate.parse(h.date) to it.toDouble() } }.toMap()
        val rhr = c.summaries.values.mapNotNull { s -> s.restingHr?.takeIf { it > 0 }?.let { LocalDate.parse(s.date) to it.toDouble() } }.toMap()
        val sleepScore = c.sleep.values.mapNotNull { s -> s.score?.let { LocalDate.parse(s.date) to it.toDouble() } }.toMap()
        val out = mutableListOf<Finding>()

        // What counts as a hard day: the top quarter of training days.
        val trainingLoads = loads.values.filter { it > 0 }.sorted()
        if (trainingLoads.size < 12) return emptyList()
        val hardFrom = trainingLoads[(trainingLoads.size * 0.75).toInt()]
        val hardDays = loads.filter { it.value >= hardFrom }.keys
        val restDays = loads.filter { it.value == 0.0 }.keys

        // HRV the night after (Garmin files a night's HRV under the morning's date).
        fun hrvDev(day: LocalDate, after: Long): Double? {
            val night = day.plusDays(after)
            val v = hrv[night] ?: return null
            val b = baseline(hrv, day) ?: return null
            return (v - b) / b * 100
        }
        val afterHard = hardDays.mapNotNull { hrvDev(it, 1) }
        val afterRest = restDays.mapNotNull { hrvDev(it, 1) }
        if (afterHard.size >= 5) {
            val drop = afterHard.average()
            // Nights until HRV is back within 3% of normal, up to 5.
            val back = hardDays.mapNotNull { d ->
                (1L..5L).firstOrNull { n -> hrvDev(d, n)?.let { it > -3 } == true } ?: if (hrvDev(d, 1) != null) 6L else null
            }
            val nights = back.average()
            out += Finding(
                headline = if (drop < -2) "HRV dips ${abs(drop).roundToInt()}% after hard days" else "Hard days barely dent your HRV",
                detail = "The night after a day of ${hardFrom.roundToInt()}+ load, compared with your previous four weeks. " +
                    if (nights <= 5.5) "It's usually back to normal within ${plural(nights.roundToInt().coerceAtLeast(1), "night")}." else "It often takes more than five nights to recover.",
                good = drop > -5,
            )
            if (afterRest.size >= 5) {
                val r = afterRest.average()
                out += Finding(
                    headline = "Rest days lift HRV ${if (r >= 0) "+" else "−"}${abs(r).roundToInt()}%",
                    detail = "The night after a day with no training, against the same baseline.",
                    good = r >= 0,
                )
            }
        }

        // Resting heart rate the morning after.
        fun rhrDelta(day: LocalDate): Double? {
            val v = rhr[day.plusDays(1)] ?: return null
            val b = baseline(rhr, day) ?: return null
            return v - b
        }
        val rhrHard = hardDays.mapNotNull(::rhrDelta)
        if (rhrHard.size >= 5) {
            val up = rhrHard.average()
            if (abs(up) >= 1) out += Finding(
                headline = "Resting HR ${if (up > 0) "up" else "down"} ${abs(up).roundToInt()} bpm the day after",
                detail = "After hard days, compared with your usual resting heart rate.",
                good = up < 2,
            )
        }

        // What goes with better or worse sleep.
        fun sleepDiff(name: String, flag: (LocalDate) -> Boolean?, betterText: String, worseText: String) {
            val yes = mutableListOf<Double>(); val no = mutableListOf<Double>()
            sleepScore.forEach { (night, score) ->
                // The night of day D is filed under D+1.
                when (flag(night.minusDays(1))) { true -> yes += score; false -> no += score; null -> {} }
            }
            if (yes.size < 6 || no.size < 6) return
            val diff = yes.average() - no.average()
            if (abs(diff) < 3) return
            out += Finding(
                headline = "Sleep scores ${abs(diff).roundToInt()} ${if (diff > 0) "higher" else "lower"} $name",
                detail = if (diff > 0) betterText else worseText,
                good = diff > 0,
            )
        }
        val eveningDays = c.activities.filter { it.startTime.hour >= 18 }.mapTo(HashSet()) { it.date }
        sleepDiff(
            "after evening workouts",
            { d -> if (loads[d]?.let { it > 0 } == true) d in eveningDays else null },
            "Compared with days you trained earlier.",
            "Compared with days you trained earlier: late sessions may be keeping you up.",
        )
        val stressByDay = c.summaries.values.mapNotNull { s -> s.stressAvg?.let { LocalDate.parse(s.date) to it } }.toMap()
        if (stressByDay.size >= 20) {
            val high = stressByDay.values.sorted().let { it[(it.size * 0.75).toInt()] }
            sleepDiff(
                "on stressful days",
                { d -> stressByDay[d]?.let { it >= high } },
                "Days in your top quarter for stress, against the rest.",
                "Days in your top quarter for stress (average $high+), against the rest.",
            )
        }
        sleepDiff(
            "after hard days",
            { d -> when { d in hardDays -> true; d in restDays -> false; else -> null } },
            "Hard training days against rest days.",
            "Hard training days against rest days.",
        )
        return out
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    // ---------------- Efficiency over time ----------------

    /** A heart rate to quote efficiency at: the typical run's, to the nearest 5. */
    fun referenceHr(stats: Collection<RunStats>): Int? {
        val hrs = stats.mapNotNull { it.avgHr }.sorted()
        if (hrs.isEmpty()) return null
        return (hrs[hrs.size / 2] / 5).roundToInt() * 5
    }

    /** Speed at [hr] beats a minute implied by an efficiency, m/s. */
    fun speedAt(efficiency: Double, hr: Int): Double = efficiency * hr / 60

    /** A rolling mean over the previous [n] values (fewer at the start). */
    fun rolling(values: List<Double>, n: Int): List<Double> = values.indices.map { i ->
        val from = max(0, i - n + 1)
        values.subList(from, i + 1).average()
    }
}
