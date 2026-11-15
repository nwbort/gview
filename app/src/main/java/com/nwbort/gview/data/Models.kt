package com.nwbort.gview.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One activity from Garmin Connect's activity search, cut down to what the app shows. */
@Serializable
data class Activity(
    val id: Long,
    val name: String = "",
    /** Garmin's activity type key, e.g. "running", "trail_running", "road_biking". */
    val type: String = "other",
    /** Local start, "yyyy-MM-dd HH:mm:ss". */
    val start: String,
    val distanceM: Double? = null,
    val durationS: Double? = null,
    val movingS: Double? = null,
    val elevGainM: Double? = null,
    val elevLossM: Double? = null,
    val avgSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val avgHr: Double? = null,
    val maxHr: Double? = null,
    val calories: Double? = null,
    val cadence: Double? = null,
    val avgPower: Double? = null,
    val aerobicTe: Double? = null,
    val anaerobicTe: Double? = null,
    val teLabel: String? = null,
    val vo2Max: Double? = null,
    val trainingLoad: Double? = null,
    val steps: Long? = null,
    val location: String? = null,
    val hasRoute: Boolean = false,
    val pr: Boolean = false,
) {
    val date: LocalDate get() = LocalDate.parse(start.take(10))
    val startTime: LocalDateTime get() = LocalDateTime.parse(start.replace(' ', 'T').take(19), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val sport: Sport get() = Sport.of(type)
    val km: Double? get() = distanceM?.takeIf { it > 0 }?.div(1000)
    /** Moving time where Garmin has it (what the watch shows), else the timer time. */
    val timeS: Double? get() = durationS ?: movingS
}

enum class Sport(val label: String) {
    RUN("Run"), RIDE("Ride"), SWIM("Swim"), WALK("Walk"), STRENGTH("Strength"), OTHER("Other");

    /** Runs and walks read in pace per km; rides in km/h; swims in pace per 100 m. */
    val usesPace: Boolean get() = this == RUN || this == WALK

    companion object {
        fun of(type: String): Sport = when {
            "swim" in type -> SWIM
            "run" in type -> RUN
            "cycl" in type || "bik" in type || "ride" in type || "gravel" in type -> RIDE
            "walk" in type || "hik" in type || "mountaineering" in type -> WALK
            "strength" in type || "hiit" in type || "yoga" in type || "pilates" in type || "cardio" in type -> STRENGTH
            else -> OTHER
        }
    }
}

/** One day's wellness summary (Garmin's v2 daily summary). */
@Serializable
data class DaySummary(
    val date: String,
    val steps: Int? = null,
    val stepGoal: Int? = null,
    val distanceM: Double? = null,
    val floors: Double? = null,
    val restingHr: Int? = null,
    val minHr: Int? = null,
    val maxHr: Int? = null,
    val stressAvg: Int? = null,
    val stressMax: Int? = null,
    val stressQualifier: String? = null,
    val restP: Double? = null,
    val lowP: Double? = null,
    val mediumP: Double? = null,
    val highP: Double? = null,
    val bbMax: Int? = null,
    val bbMin: Int? = null,
    val bbCharged: Int? = null,
    val bbDrained: Int? = null,
    val bbLatest: Int? = null,
    val activeKcal: Int? = null,
    val totalKcal: Int? = null,
    val moderateMin: Int? = null,
    val vigorousMin: Int? = null,
    val intensityGoal: Int? = null,
    val spo2: Double? = null,
    val respiration: Double? = null,
) {
    /** Garmin counts vigorous minutes double towards the weekly goal. */
    val intensityMin: Int? get() = if (moderateMin == null && vigorousMin == null) null else (moderateMin ?: 0) + 2 * (vigorousMin ?: 0)
}

@Serializable
data class Sleep(
    val date: String,
    val seconds: Int,
    val deepS: Int = 0,
    val lightS: Int = 0,
    val remS: Int = 0,
    val awakeS: Int = 0,
    val score: Int? = null,
    /** EXCELLENT, GOOD, FAIR or POOR. */
    val qualifier: String? = null,
    /** Sleep window in local time, epoch ms read as local wall time. */
    val startLocalMs: Long? = null,
    val endLocalMs: Long? = null,
    val avgHr: Double? = null,
    val respiration: Double? = null,
    val awakeCount: Int? = null,
    val needMin: Int? = null,
)

@Serializable
data class Hrv(
    val date: String,
    val lastNight: Int? = null,
    val weeklyAvg: Int? = null,
    val high5: Int? = null,
    /** BALANCED, UNBALANCED, LOW, POOR, NONE. */
    val status: String? = null,
    val balancedLow: Int? = null,
    val balancedUpper: Int? = null,
    val lowUpper: Int? = null,
)

/** Body Battery through one day: minutes since midnight and level 0-100. */
@Serializable
data class BbCurve(val date: String, val minutes: List<Int>, val levels: List<Int>)

@Serializable
data class Weight(val date: String, val kg: Double, val bodyFat: Double? = null)

/** Everything synced, kept on the phone so the app opens instantly and works offline. */
@Serializable
data class Cache(
    val activities: List<Activity> = emptyList(),
    val summaries: Map<String, DaySummary> = emptyMap(),
    val sleep: Map<String, Sleep> = emptyMap(),
    val hrv: Map<String, Hrv> = emptyMap(),
    val bodyBattery: Map<String, BbCurve> = emptyMap(),
    val weight: Map<String, Weight> = emptyMap(),
    val fitnessAge: Double? = null,
    val lastSync: Long? = null,
    /** The day the last full sync covered up to; the next one starts a few days before it. */
    val syncedTo: String? = null,
    /** The day each kind of data was last fetched up to, by section name. */
    val covered: Map<String, String> = emptyMap(),
    /** The earliest day each kind of data has been fetched from. */
    val earliest: Map<String, String> = emptyMap(),
    /** Per-run efficiency and drift, by activity id, worked out from each run's file. */
    val runStats: Map<Long, RunStats> = emptyMap(),
    /** Runs whose files have been looked at (some have nothing to say: too short, no HR). */
    val analysed: Set<Long> = emptySet(),
    /** The [Fit.VERSION] those runs were analysed with; a newer one analyses them again. */
    val analysedWith: Int = 0,
    /** How many years back to sync, from Settings. */
    val historyYears: Int = 1,
    /** What failed on the last sync, by section ("Sleep", "HRV" ...). */
    val errors: Map<String, String> = emptyMap(),
) {
    /** Newest first. */
    val sortedActivities: List<Activity> by lazy { activities.sortedByDescending { it.start } }

    fun day(date: LocalDate) = DayView(
        date,
        summaries[date.toString()],
        sleep[date.toString()],
        hrv[date.toString()],
        bodyBattery[date.toString()],
        sortedActivities.filter { it.start.startsWith(date.toString()) }.reversed(),
    )

    val isEmpty: Boolean get() = activities.isEmpty() && summaries.isEmpty() && sleep.isEmpty()
}

data class DayView(
    val date: LocalDate,
    val summary: DaySummary?,
    val sleep: Sleep?,
    val hrv: Hrv?,
    val bb: BbCurve?,
    val activities: List<Activity>,
) {
    val isEmpty: Boolean get() = summary == null && sleep == null && hrv == null && activities.isEmpty()
}

/** The route and recorded streams of one activity, decoded from its FIT file. */
@Serializable
data class Track(
    /** How it was decoded; older versions are fetched again. See [Fit.VERSION]. */
    val v: Int = 0,
    val lat: List<Double> = emptyList(),
    val lon: List<Double> = emptyList(),
    /** Seconds since the start, for each sample below. */
    val t: List<Int> = emptyList(),
    /** Metres travelled. */
    val dist: List<Double?> = emptyList(),
    val hr: List<Int?> = emptyList(),
    /** Smoothed speed, m/s. */
    val speed: List<Double?> = emptyList(),
    val alt: List<Double?> = emptyList(),
    /** Where each sample above was recorded, for marking a chart point on the map. */
    val sLat: List<Double?> = emptyList(),
    val sLon: List<Double?> = emptyList(),
    /** Smoothed grade-adjusted speed, m/s (runs and walks with elevation). */
    val gap: List<Double?> = emptyList(),
    val stats: RunStats? = null,
    val splits: List<Split> = emptyList(),
) {
    val hasRoute: Boolean get() = lat.size >= 2
}

@Serializable
data class Split(
    /** Distance covered by this split, metres (the last one is usually short). */
    val distanceM: Double,
    val seconds: Double,
    val avgHr: Int? = null,
    val elevChangeM: Double? = null,
)
