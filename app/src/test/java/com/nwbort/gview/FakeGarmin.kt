package com.nwbort.gview

import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.BbCurve
import com.nwbort.gview.data.DaySummary
import com.nwbort.gview.data.GarminApi
import com.nwbort.gview.data.Hrv
import com.nwbort.gview.data.Sleep
import com.nwbort.gview.data.Weight
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A Garmin that answers from made-up but plausible data, the same every run:
 * a runner who also rides and walks, sleeping 6-8 hours.
 */
class FakeGarmin(private val today: LocalDate = LocalDate.now()) : GarminApi("http://fake.invalid") {
    var calls = 0
        private set

    private fun wave(d: LocalDate, period: Int) = sin(d.toEpochDay() * 2 * PI / period)
    private fun noise(d: LocalDate, salt: Int) = ((d.toEpochDay() * 7919 + salt * 104729) % 1000) / 1000.0

    override fun token(apiKey: String): String { calls++; return "fake-token" }

    override fun activities(token: String, from: LocalDate, to: LocalDate): List<Activity> {
        calls++
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.mapNotNull { d -> activityOn(d)?.let { extras(d, it) } }.toList()
    }

    /** Zones, fastest stretches and temperature, as Garmin's activity list gives them. */
    private fun extras(d: LocalDate, a: Activity): Activity {
        val t = a.timeS ?: return a
        val hard = a.name == "Tempo Run"
        val zones = if (a.avgHr == null) emptyList() else if (hard) listOf(0.05, 0.25, 0.3, 0.35, 0.05).map { it * t } else listOf(0.15, 0.6, 0.2, 0.05, 0.0).map { it * t }
        val km = a.km ?: 0.0
        val pace = if (km > 0) t / km else 0.0 // s/km
        val fastest = if (a.sport != com.nwbort.gview.data.Sport.RUN) emptyMap() else
            listOf(1000, 1609, 5000, 10000, 21097).filter { it <= km * 1000 }
                .associateWith { m -> m / 1000.0 * pace * (0.93 + 0.05 * (m / 21097.0) + 0.03 * noise(d, m)) }
        val temp = 18 + 8 * sin((d.dayOfYear + 10) * 2 * PI / 365)
        return a.copy(hrZones = zones, fastest = fastest, tempMin = temp - 2, tempMax = temp + 2)
    }

    private fun activityOn(d: LocalDate): Activity? {
        val id = d.toEpochDay() * 10
        val start = "$d 06:${(10 + d.dayOfMonth).toString().padStart(2, '0')}:00"
        return when (d.dayOfWeek) {
            DayOfWeek.TUESDAY, DayOfWeek.THURSDAY -> {
                val km = 8.0 + 3 * noise(d, 1)
                val pace = 300 + 25 * noise(d, 2) // s/km
                Activity(id, if (d.dayOfWeek == DayOfWeek.TUESDAY) "Harbour Loop" else "Tempo Run", "running", start,
                    distanceM = km * 1000, durationS = km * pace, elevGainM = 60 + 80 * noise(d, 3), avgSpeed = 1000 / pace, maxSpeed = 1000 / (pace - 40),
                    avgHr = 146 + 8 * noise(d, 4), maxHr = 171.0, calories = km * 68, cadence = 172.0, aerobicTe = 3.2, anaerobicTe = 0.8,
                    teLabel = "TEMPO", vo2Max = (53 + 2 * wave(d, 120)).roundToInt().toDouble(), trainingLoad = 140.0, location = "Sydney", hasRoute = true)
            }
            DayOfWeek.SATURDAY -> {
                val km = 16.0 + 6 * noise(d, 5)
                Activity(id, "Long Run", "trail_running", start, distanceM = km * 1000, durationS = km * 345, elevGainM = 340.0,
                    avgSpeed = 1000 / 345.0, avgHr = 142.0, maxHr = 165.0, calories = km * 70, cadence = 168.0, aerobicTe = 3.8,
                    teLabel = "BASE", vo2Max = 54.0, location = "Manly", hasRoute = true, trainingLoad = 260.0)
            }
            DayOfWeek.SUNDAY -> {
                val km = 40.0 + 25 * noise(d, 6)
                Activity(id, "Sunday Ride", "road_biking", start, distanceM = km * 1000, durationS = km * 1000 / 7.6, elevGainM = 420.0,
                    avgSpeed = 7.6, maxSpeed = 15.2, avgHr = 128.0, maxHr = 158.0, calories = km * 24, cadence = 86.0, avgPower = 182.0,
                    aerobicTe = 2.9, location = "Royal National Park", hasRoute = true)
            }
            DayOfWeek.WEDNESDAY -> Activity(id, "Strength", "strength_training", "$d 18:05:00", durationS = 2400.0, avgHr = 112.0, maxHr = 150.0, calories = 260.0)
            DayOfWeek.MONDAY -> Activity(id, "Evening Walk", "walking", "$d 19:30:00", distanceM = 4200.0, durationS = 2900.0, avgSpeed = 1.45, avgHr = 96.0, hasRoute = true)
            else -> null
        }
    }

    override fun summaries(token: String, from: LocalDate, to: LocalDate): List<DaySummary> {
        calls++
        return days(from, to).map { d ->
            val rest = (49 + 3 * wave(d, 30) + 2 * noise(d, 7)).roundToInt()
            val bbMax = (78 + 14 * wave(d, 9) + 6 * noise(d, 8)).roundToInt().coerceAtMost(100)
            val steps = (8000 + 5000 * noise(d, 9) + 3000 * wave(d, 7)).roundToInt()
            val stress = (24 + 10 * wave(d, 11) + 6 * noise(d, 10)).roundToInt()
            DaySummary(
                d.toString(), steps = steps, stepGoal = 10000, distanceM = steps * 0.78, floors = 6 + 10 * noise(d, 11),
                restingHr = rest, minHr = rest - 4, maxHr = 165, stressAvg = stress, stressMax = 88, stressQualifier = if (stress < 26) "calm" else "balanced",
                restP = 0.42, lowP = 0.31, mediumP = 0.18, highP = 0.09,
                bbMax = bbMax, bbMin = (bbMax - 60).coerceAtLeast(8), bbCharged = 66, bbDrained = 61, bbLatest = if (d == today) 54 else bbMax - 58,
                activeKcal = 520 + (300 * noise(d, 12)).roundToInt(), totalKcal = 2400, moderateMin = 22, vigorousMin = 31, intensityGoal = 150,
            )
        }
    }

    override fun sleep(token: String, from: LocalDate, to: LocalDate): List<Sleep> {
        calls++
        return days(from, to).map { d ->
            val secs = (7 * 3600 + 3000 * wave(d, 6) + 1800 * noise(d, 13)).roundToInt()
            val bed = d.minusDays(1).atTime(22, 30 + (20 * noise(d, 14)).roundToInt()).toInstant(ZoneOffset.UTC).toEpochMilli()
            Sleep(d.toString(), secs, deepS = secs / 5, lightS = secs / 2, remS = secs - secs / 5 - secs / 2, awakeS = 1500,
                // Worse after Wednesday's evening strength session.
                score = (78 + 12 * wave(d, 6) + 4 * noise(d, 15) - if (d.dayOfWeek == DayOfWeek.THURSDAY) 9 else 0).roundToInt().coerceAtMost(100), qualifier = "GOOD",
                startLocalMs = bed, endLocalMs = bed + (secs + 1500) * 1000L, avgHr = 52.0, respiration = 14.2, awakeCount = 2, needMin = 480)
        }
    }

    override fun hrv(token: String, from: LocalDate, to: LocalDate): List<Hrv> {
        calls++
        return days(from, to).map { d ->
            // Lower the night after the weekend's long run and ride.
            val after = when (d.dayOfWeek) { DayOfWeek.SUNDAY -> 0.86; DayOfWeek.MONDAY -> 0.92; DayOfWeek.WEDNESDAY -> 1.04; else -> 1.0 }
            Hrv(d.toString(), lastNight = ((62 + 8 * wave(d, 13) + 6 * noise(d, 16)) * after).roundToInt(), weeklyAvg = 63, high5 = 88,
                status = "BALANCED", balancedLow = 55, balancedUpper = 72, lowUpper = 50)
        }
    }

    override fun bodyBattery(token: String, from: LocalDate, to: LocalDate): List<BbCurve> {
        calls++
        return days(from, to).map { d ->
            val mins = (0 until 1440 step 10).toList().let { if (d == today) it.take(100) else it }
            BbCurve(d.toString(), mins, mins.map { m ->
                val h = m / 60.0
                when {
                    h < 6.5 -> 30 + (h / 6.5 * 55)
                    else -> 85 - (h - 6.5) * 4.2 - if (h in 6.5..7.5) 0.0 else 6.0
                }.roundToInt().coerceIn(5, 100)
            })
        }
    }

    override fun weight(token: String, from: LocalDate, to: LocalDate): List<Weight> {
        calls++
        return days(from, to).filter { it.dayOfWeek == DayOfWeek.MONDAY }.map { d -> Weight(d.toString(), 71.5 + 0.8 * wave(d, 90) + 0.3 * noise(d, 17)) }
    }

    override fun fitnessAge(token: String, date: LocalDate): Double = 31.0

    /** A loop around a park, sized to the activity, as a zipped FIT file. */
    override fun activityFile(token: String, id: Long): ByteArray {
        calls++
        val d = LocalDate.ofEpochDay(id / 10)
        val a = activityOn(d) ?: return ByteArray(0)
        val dist = a.distanceM ?: 0.0
        val secs = (a.durationS ?: 0.0).toInt()
        val speed = dist / secs.coerceAtLeast(1)
        val r = dist / (2 * PI) / 111_320.0 * 0.8
        val points = (0..secs step 2).map { s ->
            val f = s.toDouble() / secs
            val ang = 2 * PI * f
            // A wobbly loop so it looks like a real route.
            val wob = 1 + 0.18 * sin(ang * 5) + 0.07 * cos(ang * 13)
            FitWriter.Point(
                t = 1_100_000_000L + s,
                lat = -33.87 + r * wob * sin(ang),
                lon = 151.215 + r * wob * cos(ang) * 1.2 - r,
                distM = dist * f,
                speed = speed * (1 + 0.06 * sin(ang * 9)),
                hr = (a.avgHr ?: 140.0).toInt() - 12 + (24 * f).toInt() + (4 * sin(ang * 17)).toInt(),
                altM = 30 + 40 * sin(ang * 2) + 15 * sin(ang * 7),
            )
        }
        return FitWriter.zip(FitWriter.fit(points))
    }

    private fun days(from: LocalDate, to: LocalDate) = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}
