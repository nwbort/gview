package com.nwbort.gview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.GarminApi
import com.nwbort.gview.data.Sport
import com.nwbort.gview.data.Track
import com.nwbort.gview.ui.theme.G
import kotlin.math.roundToInt

/** Loading, then the track (possibly empty when Garmin had no file or we're offline). */
private sealed interface TrackState {
    data object Loading : TrackState
    data class Ready(val track: Track?) : TrackState
}

@Composable
private fun rememberTrack(a: Activity, vm: AppViewModel): TrackState {
    val s by produceState<TrackState>(TrackState.Loading, a.id) { value = TrackState.Ready(vm.track(a)) }
    return s
}

@Composable
fun ActivityScreen(a: Activity, vm: AppViewModel, onBack: () -> Unit, onMap: () -> Unit) {
    val state = rememberTrack(a, vm)
    val track = (state as? TrackState.Ready)?.track
    val color = G.sport(a.sport)
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(G.paper)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when {
                a.hasRoute && state is TrackState.Loading -> Box(Modifier.fillMaxWidth().height(320.dp).background(G.wash), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                track != null && track.hasRoute -> RouteMap(track, color, Modifier.fillMaxWidth().height(320.dp).clickable(onClick = onMap))
                else -> Spacer(Modifier.statusBarsPadding().height(56.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SportBadge(a.sport, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name.ifBlank { a.sport.label }, style = MaterialTheme.typography.headlineSmall, color = G.ink)
                        Text(listOfNotNull(Fmt.when_(a), a.location).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = G.ink2)
                    }
                }
                Hero(a)
                Details(a)
                if (track != null) Charts(a, track)
                if (track != null && track.splits.size > 1) Splits(a, track)
                TextButton(onClick = { openLink(context, GarminApi.activityUrl(a.id)) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = G.ink2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open in Garmin Connect", color = G.ink2)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        BackBubble(onBack, Modifier.statusBarsPadding().padding(8.dp))
    }
}

@Composable
private fun BackBubble(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.shadow(4.dp, CircleShape).clip(CircleShape).background(G.card).size(44.dp)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = G.ink)
    }
}

@Composable
private fun Hero(a: Activity) {
    Panel {
        Row(Modifier.fillMaxWidth()) {
            if (a.km != null) {
                Column(Modifier.weight(1f)) {
                    Text("Distance", style = MaterialTheme.typography.bodySmall, color = G.ink3)
                    Value(Fmt.km(a.distanceM), "km", style = MaterialTheme.typography.displaySmall)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Time", style = MaterialTheme.typography.bodySmall, color = G.ink3)
                Value(Fmt.clock(a.timeS), style = MaterialTheme.typography.displaySmall)
            }
            if (a.km != null && a.sport != Sport.STRENGTH) {
                Column(Modifier.weight(1f)) {
                    Text(if (a.sport.usesPace || a.sport == Sport.SWIM) "Pace" else "Speed", style = MaterialTheme.typography.bodySmall, color = G.ink3)
                    val (v, u) = Fmt.speed(a)
                    Value(v, u, style = MaterialTheme.typography.displaySmall)
                }
            }
        }
    }
}

@Composable
private fun Details(a: Activity) {
    val stats = listOfNotNull(
        a.avgHr?.let { Triple("Avg heart rate", it.roundToInt().toString(), "bpm") },
        a.maxHr?.let { Triple("Max heart rate", it.roundToInt().toString(), "bpm") },
        a.elevGainM?.takeIf { it > 0 }?.let { Triple("Elevation gain", it.roundToInt().toString(), "m") },
        a.calories?.let { Triple("Calories", Fmt.int(it.roundToInt()), "kcal") },
        a.cadence?.let { Triple("Cadence", it.roundToInt().toString(), if (a.sport == Sport.RIDE) "rpm" else "spm") },
        a.avgPower?.let { Triple("Avg power", it.roundToInt().toString(), "W") },
        a.aerobicTe?.let { Triple("Aerobic effect", Fmt.one(it), null) },
        a.anaerobicTe?.takeIf { it > 0 }?.let { Triple("Anaerobic effect", Fmt.one(it), null) },
        a.trainingLoad?.let { Triple("Training load", it.roundToInt().toString(), null) },
        a.vo2Max?.let { Triple("VO₂ max", it.roundToInt().toString(), null) },
        a.maxSpeed?.takeIf { a.sport == Sport.RIDE }?.let { Triple("Max speed", Fmt.kmh(it), "km/h") },
        a.steps?.takeIf { it > 0 }?.let { Triple("Steps", Fmt.int(it), null) },
    )
    if (stats.isEmpty()) return
    Panel {
        a.teLabel?.let {
            Pill(Fmt.titleCase(it), G.sport(a.sport))
            Spacer(Modifier.height(12.dp))
        }
        stats.chunked(3).forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (l, v, u) -> Stat(l, v, u, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Charts(a: Activity, t: Track) {
    // Plot against distance where there is one, else time.
    val byDist = t.dist.count { it != null } > t.t.size / 2 && (a.distanceM ?: 0.0) > 200
    val xs = if (byDist) t.dist.map { ((it ?: 0.0) / 1000).toFloat() } else t.t.map { it / 60f }
    val xFmt: (Float) -> String = if (byDist) { v -> "${Fmt.one(v.toDouble())} km" } else { v -> "${v.roundToInt()} min" }
    val color = G.sport(a.sport)

    if (t.speed.any { it != null } && a.sport != Sport.STRENGTH && (a.distanceM ?: 0.0) > 0) {
        Panel {
            val pace = a.sport.usesPace
            Label(if (pace) "Pace" else if (a.sport == Sport.SWIM) "Pace /100m" else "Speed", color)
            Spacer(Modifier.height(4.dp))
            val ys = t.speed.map { s ->
                when {
                    s == null -> null
                    pace -> if (s < 0.5) null else (1000 / s / 60).toFloat() // min/km
                    a.sport == Sport.SWIM -> if (s < 0.2) null else (100 / s / 60).toFloat()
                    else -> (s * 3.6).toFloat()
                }
            }
            val minFmt: (Float) -> String = { v -> "%d:%02d".format(v.toInt(), ((v - v.toInt()) * 60).roundToInt().coerceAtMost(59)) }
            val inverted = pace || a.sport == Sport.SWIM
            LineChart(
                xs, ys, color,
                yFmt = if (inverted) minFmt else { v -> v.roundToInt().toString() },
                tipFmt = if (inverted) { v -> minFmt(v) + if (a.sport == Sport.SWIM) " /100m" else " /km" } else { v -> "${Fmt.one(v.toDouble())} km/h" },
                xFmt = xFmt,
                invert = inverted,
                robust = true,
                description = "Pace chart",
            )
        }
    }
    if (t.hr.any { it != null }) {
        Panel {
            Label("Heart rate", G.heart)
            Spacer(Modifier.height(4.dp))
            LineChart(xs, t.hr.map { it?.toFloat() }, G.heart, tipFmt = { "${it.roundToInt()} bpm" }, xFmt = xFmt, description = "Heart rate chart")
        }
    }
    if (t.alt.count { it != null } > 10 && (a.elevGainM ?: 0.0) > 5) {
        Panel {
            Label("Elevation", G.ink2)
            Spacer(Modifier.height(4.dp))
            LineChart(xs, t.alt.map { it?.toFloat() }, Color(0xFF8A877E), height = 120.dp, tipFmt = { "${it.roundToInt()} m" }, xFmt = xFmt, description = "Elevation chart")
        }
    }
}

@Composable
private fun Splits(a: Activity, t: Track) {
    val color = G.sport(a.sport)
    val every = if (a.sport == Sport.RIDE) 5 else 1
    val speeds = t.splits.map { it.distanceM / it.seconds.coerceAtLeast(1.0) }
    val fastest = speeds.max(); val slowest = speeds.min()
    Panel {
        Label("Splits", color)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("km", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.width(36.dp))
            Text(if (a.sport.usesPace) "Pace" else "Speed", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.weight(1f))
            Text("HR", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.width(44.dp))
            Text("Elev", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.width(48.dp))
        }
        t.splits.forEachIndexed { i, s ->
            val speed = speeds[i]
            val frac = if (fastest - slowest < 1e-6) 1f else (0.35f + 0.65f * ((speed - slowest) / (fastest - slowest)).toFloat())
            val label = if (s.distanceM < every * 1000 * 0.95) Fmt.one((i * every * 1000 + s.distanceM) / 1000) else ((i + 1) * every).toString()
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = G.ink2, modifier = Modifier.width(36.dp))
                Row(Modifier.weight(1f).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(frac).height(18.dp).clip(CircleShape).background(color.copy(alpha = 0.18f + 0.5f * frac)))
                    if (frac < 1f) Spacer(Modifier.weight(1f - frac))
                }
                Text(
                    Fmt.speed(a.sport, speed).first,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
                    color = G.ink,
                    modifier = Modifier.width(52.dp),
                )
                Text(s.avgHr?.toString() ?: "–", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = G.ink2, modifier = Modifier.width(44.dp))
                Text(
                    s.elevChangeM?.let { (if (it >= 0) "+" else "−") + kotlin.math.abs(it).roundToInt() } ?: "–",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = G.ink2,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
    }
}

/** The route full screen: pinch and drag to explore. */
@Composable
fun FullMapScreen(a: Activity, vm: AppViewModel, onBack: () -> Unit) {
    val state = rememberTrack(a, vm)
    val track = (state as? TrackState.Ready)?.track
    Box(Modifier.fillMaxSize().background(G.wash)) {
        if (track != null && track.hasRoute) RouteMap(track, G.sport(a.sport), Modifier.fillMaxSize(), interactive = true)
        BackBubble(onBack, Modifier.systemBarsPadding().padding(8.dp))
    }
}
