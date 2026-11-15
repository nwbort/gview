package com.nwbort.gview.ui

import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Sport
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Formatting for numbers and dates. Metric throughout. */
object Fmt {
    private val loc = Locale.getDefault()

    fun km(m: Double?, digits: Int = 2): String = if (m == null) "–" else String.format(loc, "%.${digits}f", m / 1000)

    /** "1:02:03" or "42:10". */
    fun clock(s: Double?): String {
        if (s == null) return "–"
        val t = s.roundToLong()
        val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) String.format(loc, "%d:%02d:%02d", h, m, sec) else String.format(loc, "%d:%02d", m, sec)
    }

    /** "7h 32m", "45m". */
    fun hm(s: Double?): String {
        if (s == null) return "–"
        val m = (s / 60).roundToInt()
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    /** Pace per km from m/s: "5:12". */
    fun pace(mps: Double?): String {
        if (mps == null || mps < 0.3) return "–"
        val s = (1000 / mps).roundToInt()
        return String.format(loc, "%d:%02d", s / 60, s % 60)
    }

    fun pace100(mps: Double?): String {
        if (mps == null || mps < 0.1) return "–"
        val s = (100 / mps).roundToInt()
        return String.format(loc, "%d:%02d", s / 60, s % 60)
    }

    fun kmh(mps: Double?): String = if (mps == null) "–" else String.format(loc, "%.1f", mps * 3.6)

    /** The speed figure an athlete expects for the sport, with its unit. */
    fun speed(a: Activity): Pair<String, String> = speed(a.sport, a.avgSpeed)

    fun speed(sport: Sport, mps: Double?): Pair<String, String> = when (sport) {
        Sport.RUN, Sport.WALK -> pace(mps) to "/km"
        Sport.SWIM -> pace100(mps) to "/100m"
        else -> kmh(mps) to "km/h"
    }

    fun int(v: Number?): String = if (v == null) "–" else String.format(loc, "%,d", v.toLong())
    fun one(v: Double?): String = if (v == null) "–" else String.format(loc, "%.1f", v)

    fun dayTitle(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE", loc))
    }

    fun longDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", loc))
    fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM", loc))
    fun weekday(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE", loc))
    fun month(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("MMM", loc))

    /** "Sat 15 Nov · 6:42 am" */
    fun when_(a: Activity): String = a.startTime.format(DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", loc))
    fun time(a: Activity): String = a.startTime.format(DateTimeFormatter.ofPattern("h:mm a", loc))

    fun titleCase(key: String): String = key.lowercase().split('_').joinToString(" ").replaceFirstChar { it.titlecase(loc) }
}
