package com.nwbort.gview.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.Sport
import com.nwbort.gview.ui.theme.G
import java.time.LocalDate
import kotlin.math.roundToInt

/** "1 km", "Mile", "Half marathon"… for Garmin's fastest-split distances. */
fun distanceName(m: Int): String = when (m) {
    1000 -> "1 km"
    1609 -> "Mile"
    5000 -> "5 km"
    10000 -> "10 km"
    in 21090..21100 -> "Half marathon"
    in 42190..42200 -> "Marathon"
    else -> if (m % 1000 == 0) "${m / 1000} km" else "${Fmt.one(m / 1000.0)} km"
}

/** Time in each heart-rate zone as one bar, with each zone's share. */
@Composable
fun ZonesBar(seconds: List<Double>, zones: com.nwbort.gview.data.HrZones? = null) {
    val total = seconds.sum()
    if (total <= 0) return
    Segments(seconds.mapIndexed { i, s -> s.toFloat() to G.zones[i] }, height = 14.dp)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth()) {
        seconds.forEachIndexed { i, s ->
            Column(Modifier.weight(1f)) {
                Legend(G.zones[i], "Z${i + 1}", "")
                Text("${(s / total * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = G.ink)
                Text(Fmt.hm(s), style = MaterialTheme.typography.bodySmall, color = G.ink3)
                zones?.range(i)?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = G.ink3) }
            }
        }
    }
}

/** An activity's heart-rate zones. */
@Composable
fun ActivityZones(a: Activity, zones: com.nwbort.gview.data.HrZones?) {
    if (a.hrZones.sum() <= 0) return
    Panel {
        Label("Heart-rate zones", G.heart)
        Spacer(Modifier.height(10.dp))
        ZonesBar(a.hrZones, zones)
        zones?.let { ZonesNote(it) }
    }
}

/** How the zones are set, so the bpm figures make sense. */
@Composable
private fun ZonesNote(z: com.nwbort.gview.data.HrZones) {
    val how = when (z.method) {
        "LACTATE_THRESHOLD" -> "from your lactate threshold" + (z.threshold?.let { " ($it bpm)" } ?: "")
        "HR_RESERVE" -> "from your heart-rate reserve" + (if (z.resting != null && z.max != null) " (${z.resting}–${z.max} bpm)" else "")
        "HR_MAX" -> "from your max heart rate" + (z.max?.let { " ($it bpm)" } ?: "")
        else -> "as you set them in Garmin Connect"
    }
    Spacer(Modifier.height(8.dp))
    Text("Zones in bpm, $how.", style = MaterialTheme.typography.bodySmall, color = G.ink3)
}

/** Garmin's fastest stretches within this run, marked where they're your best ever. */
@Composable
fun ActivityBests(a: Activity, cache: Cache) {
    if (a.sport != Sport.RUN || a.fastest.isEmpty()) return
    val allTime = bests(cache.activities)
    Panel {
        Label("Fastest in this run", G.sport(a.sport))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            a.fastest.entries.sortedBy { it.key }.take(4).forEach { (m, s) ->
                Column(Modifier.weight(1f)) {
                    Text(distanceName(m), style = MaterialTheme.typography.bodySmall, color = G.ink3)
                    Text(Fmt.clock(s), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"), color = G.ink)
                    if (allTime[m]?.first?.id == a.id) Pill("PB", G.sport(a.sport))
                }
            }
        }
    }
}

/** The fastest run at each distance: distance to (activity, seconds). */
fun bests(activities: List<Activity>): Map<Int, Pair<Activity, Double>> =
    activities.filter { it.sport == Sport.RUN }
        .flatMap { a -> a.fastest.map { (m, s) -> m to (a to s) } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, l) -> l.minBy { it.second } }

/** Best times at each distance: all time, and within the range shown. Tap one to open that run. */
@Composable
fun BestsCard(cache: Cache, range: Range, onActivity: (Long) -> Unit) {
    val allTime = bests(cache.activities)
    if (allTime.isEmpty()) return
    val start = range.start(cache, LocalDate.now())
    val inRange = bests(cache.activities.filter { it.date >= start })
    Panel {
        Label("Personal bests", G.steps)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.2f))
            Text("All time", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.weight(1.4f))
            Text("Best in ${range.label}", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.weight(1f))
        }
        allTime.entries.sortedBy { it.key }.forEach { (m, best) ->
            val (a, s) = best
            Row(
                Modifier.fillMaxWidth().clickable { onActivity(a.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(distanceName(m), style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1.2f))
                Column(Modifier.weight(1.4f)) {
                    Text(Fmt.clock(s), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = G.ink)
                    Text("${Fmt.pace(m / s)} /km · ${Fmt.shortDate(a.date)} ${a.date.year}", style = MaterialTheme.typography.bodySmall, color = G.ink3)
                }
                Text(
                    inRange[m]?.second?.let(Fmt::clock) ?: "–",
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (inRange[m]?.first?.id == a.id) G.steps else G.ink2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text("Garmin's fastest stretch of each distance inside any run, not just races.", style = MaterialTheme.typography.bodySmall, color = G.ink3)
    }
}

/** Time in heart-rate zones across all activities in the range: how much is easy and how much hard. */
@Composable
fun ZonesCard(cache: Cache, range: Range) {
    val start = range.start(cache, LocalDate.now())
    val zones = cache.activities.filter { it.date >= start && it.hrZones.size == 5 }
        .fold(DoubleArray(5)) { acc, a -> a.hrZones.forEachIndexed { i, s -> acc[i] += s }; acc }.toList()
    val total = zones.sum()
    if (total <= 0) return
    val easy = (zones[0] + zones[1]) / total * 100
    Panel {
        Label("Time in heart-rate zones", G.heart)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Value("${easy.roundToInt()}%", "easy", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(10.dp))
            Text("· ${(100 - easy).roundToInt()}% moderate to hard", style = MaterialTheme.typography.bodyMedium, color = G.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        ZonesBar(zones, cache.hrZones)
        Spacer(Modifier.height(10.dp))
        Text(
            "Easy is zones 1–2. Endurance plans usually aim for about 80% easy; much less means the easy days aren't easy enough.",
            style = MaterialTheme.typography.bodySmall,
            color = G.ink3,
        )
    }
}
