package com.nwbort.gview.ai

import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.Sport
import com.nwbort.gview.data.Track
import com.nwbort.gview.ui.Fmt
import kotlin.math.roundToInt

/**
 * The prompt for an activity's summary: the numbers the page shows, set out
 * plainly, plus how similar recent sessions went for context. Kept well under
 * the on-device model's 4,000-token input.
 */
object Brief {
    fun prompt(a: Activity, track: Track?, cache: Cache): String = buildString {
        appendLine("You are a friendly, knowledgeable endurance coach. Write a short summary of this workout for the athlete who did it, in 3 to 5 sentences.")
        appendLine("Say what kind of session it was, how it went, and one thing worth noticing (for example pacing, heart rate, the hills or the heat), comparing it with their recent sessions where that helps.")
        appendLine("Use only the figures below and never make up numbers. Write plain sentences with no headings, lists or markdown. Speak to the athlete as \"you\". Metric units.")
        appendLine()
        appendLine("WORKOUT")
        facts(a, track, cache).forEach { appendLine("- $it") }
        val recent = recent(a, cache)
        if (recent.isNotEmpty()) {
            appendLine()
            appendLine("THEIR OTHER ${a.sport.label.uppercase()}S IN THE 6 WEEKS BEFORE")
            recent.forEach { appendLine("- $it") }
        }
    }.trimEnd()

    fun facts(a: Activity, track: Track?, cache: Cache): List<String> = buildList {
        add("Sport: ${Fmt.titleCase(a.type)}" + (a.name.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: ""))
        add("When: ${Fmt.when_(a)}" + (a.location?.let { ", $it" } ?: ""))
        a.km?.let { add("Distance: ${Fmt.km(a.distanceM)} km") }
        a.timeS?.let { add("Moving time: ${Fmt.clock(it)}") }
        if (a.km != null && a.sport != Sport.STRENGTH) {
            val (v, u) = Fmt.speed(a)
            add("${if (a.sport.usesPace || a.sport == Sport.SWIM) "Average pace" else "Average speed"}: $v $u")
        }
        a.avgHr?.let { add("Average heart rate: ${it.roundToInt()} bpm") }
        a.maxHr?.let { add("Max heart rate: ${it.roundToInt()} bpm") }
        a.elevGainM?.takeIf { it > 0 }?.let { add("Elevation gain: ${it.roundToInt()} m") }
        a.tempMax?.let { add("Temperature: ${it.roundToInt()} °C") }
        a.teLabel?.let { add("Garmin's training effect label: ${Fmt.titleCase(it)}") }
        a.aerobicTe?.let { add("Aerobic training effect: ${Fmt.one(it)} out of 5") }
        a.anaerobicTe?.takeIf { it > 0 }?.let { add("Anaerobic training effect: ${Fmt.one(it)} out of 5") }
        a.trainingLoad?.let { add("Training load: ${it.roundToInt()}") }
        a.avgPower?.let { add("Average power: ${it.roundToInt()} W") }
        a.cadence?.let { add("Cadence: ${it.roundToInt()} ${if (a.sport == Sport.RIDE) "rpm" else "steps per minute"}") }
        a.calories?.let { add("Calories: ${it.roundToInt()} kcal") }
        if (a.pr) add("Garmin marked a personal record in this activity")
        zones(a, cache)?.let { add("Time in heart-rate zones: $it") }
        track?.stats?.let { st ->
            st.gapMps?.let { add("Grade-adjusted pace (as if flat): ${Fmt.pace(it)} /km") }
            st.driftPct?.let { add("Heart-rate drift, second half vs first: ${Fmt.one(it)}% (under 5% is steady, over 10% is a lot)") }
            if (!st.steady) add("Pace varied a lot: likely intervals, repeats or a tempo block")
        }
        splits(a, track)?.let { add("Splits: $it") }
    }

    private fun zones(a: Activity, cache: Cache): String? {
        if (a.hrZones.size < 5 || a.hrZones.sum() < 60) return null
        val floors = cache.hrZones?.floors
        return a.hrZones.mapIndexed { i, s ->
            val range = floors?.getOrNull(i)?.let { lo -> floors.getOrNull(i + 1)?.let { " ($lo–${it - 1} bpm)" } ?: " ($lo+ bpm)" }.orEmpty()
            "zone ${i + 1}$range ${Fmt.hm(s)}"
        }.joinToString(", ")
    }

    private fun splits(a: Activity, track: Track?): String? {
        val splits = track?.splits?.takeIf { it.size > 1 } ?: return null
        val every = if (a.sport == Sport.RIDE) 5 else 1
        // Long rides would swamp the prompt; every split of a long run still fits.
        val step = ((splits.size + 29) / 30).coerceAtLeast(1)
        return splits.withIndex().filter { it.index % step == 0 || it.index == splits.lastIndex }.joinToString("; ") { (i, s) ->
            val speed = s.distanceM / s.seconds.coerceAtLeast(1.0)
            val (v, u) = Fmt.speed(a.sport, speed)
            buildString {
                append("${if (s.distanceM < every * 950) "last ${Fmt.km(s.distanceM)} km" else "km ${(i + 1) * every}"} $v$u")
                s.avgHr?.let { append(" at $it bpm") }
                s.elevChangeM?.roundToInt()?.takeIf { kotlin.math.abs(it) >= 5 }?.let { append(if (it > 0) ", +$it m" else ", −${-it} m") }
            }
        }
    }

    /** Up to 8 earlier sessions of the same sport from the six weeks before, newest first. */
    private fun recent(a: Activity, cache: Cache): List<String> {
        val from = a.date.minusWeeks(6)
        return cache.sortedActivities.asSequence()
            .filter { it.id != a.id && it.sport == a.sport && it.start < a.start && !it.date.isBefore(from) }
            .take(8)
            .map { o ->
                listOfNotNull(
                    o.date.toString(),
                    o.km?.let { "${Fmt.km(o.distanceM)} km" },
                    o.timeS?.let { Fmt.clock(it) },
                    o.km?.takeIf { o.sport != Sport.STRENGTH }?.let { Fmt.speed(o).let { (v, u) -> "$v $u" } },
                    o.avgHr?.let { "avg HR ${it.roundToInt()}" },
                    o.teLabel?.let { Fmt.titleCase(it) },
                ).joinToString(", ")
            }.toList()
    }
}
