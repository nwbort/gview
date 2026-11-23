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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nwbort.gview.ai.LocalAi
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Analysis
import com.nwbort.gview.data.RunStats
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun ActivityScreen(a: Activity, vm: AppViewModel, onBack: () -> Unit, onMap: () -> Unit, onRaw: () -> Unit) {
    val state = rememberTrack(a, vm)
    val cache by vm.cache.collectAsStateWithLifecycle()
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
                AiSummary(a, track, state is TrackState.Ready, vm)
                ActivityZones(a, cache.hrZones)
                ActivityBests(a, cache)
                track?.stats?.let { Effort(a, it, cache.runStats.values) }
                if (track != null) Charts(a, track)
                if (track != null && track.splits.size > 1) Splits(a, track)
                TextButton(onClick = { openLink(context, GarminApi.activityUrl(a.id)) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = G.ink2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open in Garmin Connect", color = G.ink2)
                }
                TextButton(onClick = onRaw) {
                    Icon(Icons.Outlined.DataObject, contentDescription = null, tint = G.ink2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Garmin's raw data", color = G.ink2)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        BackBubble(onBack, Modifier.statusBarsPadding().padding(8.dp))
    }
}

/** What the run says about fitness: flat-ground pace, efficiency, and how steady HR held. */
@Composable
private fun Effort(a: Activity, st: RunStats, all: Collection<RunStats>) {
    Panel {
        Label("Effort", G.fitness)
        Spacer(Modifier.height(10.dp))
        val ref = Analysis.referenceHr(all + st)
        val eff = st.efficiency
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Grade-adjusted pace", Fmt.pace(st.gapMps), "/km", Modifier.weight(1f))
            if (eff != null && ref != null) Stat("Pace at $ref bpm", Fmt.pace(Analysis.speedAt(eff, ref)), "/km", Modifier.weight(1f))
            st.driftPct?.let { Stat("HR drift", Fmt.one(it), "%", Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(10.dp))
        val drift = st.driftPct
        Text(
            buildString {
                append("Grade-adjusted pace is your pace as if the hills were flat. ")
                if (eff != null && ref != null) append("Pace at $ref bpm is how fast that effort would be at your typical running heart rate: faster over the months means fitter. ")
                if (drift == null && !st.steady) append("HR drift isn't shown: pace changed too much (intervals, repeats or a tempo block) for it to mean anything.")
                if (drift != null) append(
                    when {
                        drift >= 5 && (a.tempMax ?: 0.0) >= 25 -> "HR rose ${Fmt.one(drift)}% for the same pace in the second half, but it was ${a.tempMax!!.roundToInt()} °C: heat alone pushes heart rate up, so this says little about endurance."
                        drift < 5 -> "HR held steady through the second half (under 5% drift): well within your aerobic range."
                        drift < 10 -> "Some drift in the second half (5–10%): a solid aerobic workout near the edge of your endurance."
                        else -> "HR rose a lot for the same pace in the second half (over 10%): fatigue, heat or under-fuelling, or a long run beyond current endurance."
                    }
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = G.ink2,
        )
    }
}

/**
 * A few sentences about the workout, written on the phone by Gemini Nano.
 * Only shown on phones that have it; on others there's nothing to see.
 */
@Composable
private fun AiSummary(a: Activity, track: Track?, trackReady: Boolean, vm: AppViewModel) {
    val status by vm.aiStatus.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    if (status == null || status == LocalAi.Status.UNAVAILABLE) return
    val color = G.sport(a.sport)
    val summary = summaries[a.id]
    // Wait for the file so the splits and drift make it into the prompt.
    val write = { if (trackReady) vm.summarise(a, track) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Summary", color, Modifier.weight(1f))
            if (summary is Summary.Downloading || summary is Summary.Writing) {
                CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        when (summary) {
            null -> {
                Text(
                    if (status == LocalAi.Status.DOWNLOADABLE) "Written on this phone by Gemini Nano: nothing leaves the device. The first time downloads Google's on-device model."
                    else "Written on this phone by Gemini Nano: nothing leaves the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.ink2,
                )
                Spacer(Modifier.height(4.dp))
                AiButton("Summarise this workout", trackReady, write)
            }
            Summary.Downloading -> Text("Downloading the on-device model. This only happens once and can take a few minutes on Wi-Fi.", style = MaterialTheme.typography.bodyMedium, color = G.ink2)
            is Summary.Writing -> Text(summary.text.ifEmpty { "Thinking…" }, style = MaterialTheme.typography.bodyMedium, color = if (summary.text.isEmpty()) G.ink3 else G.ink)
            is Summary.Done -> {
                Text(summary.text, style = MaterialTheme.typography.bodyMedium, color = G.ink)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gemini Nano, on device. It can get things wrong.", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.weight(1f))
                    TextButton(onClick = write) { Text("Rewrite", color = G.ink2) }
                }
            }
            is Summary.Failed -> {
                Text(summary.message, style = MaterialTheme.typography.bodySmall, color = G.ink2)
                AiButton("Try again", trackReady, write)
            }
        }
    }
}

@Composable
private fun AiButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = if (enabled) G.ink else G.ink3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (enabled) G.ink else G.ink3)
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
        a.tempMax?.let { hi ->
            val lo = a.tempMin
            Triple("Temperature", if (lo != null && lo.roundToInt() != hi.roundToInt()) "${lo.roundToInt()}–${hi.roundToInt()}" else "${hi.roundToInt()}", "°C")
        },
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

/** One of an activity's streams, ready to chart: pace or speed, heart rate, elevation. */
private class Series(
    val name: String,
    val color: Color,
    val ys: List<Float?>,
    val yFmt: (Float) -> String,
    val tipFmt: (Float) -> String,
    /** Smaller is better and drawn higher (pace). */
    val invert: Boolean = false,
    val lowLabel: String? = null,
    val highLabel: String? = null,
    /** A companion line on the same scale, e.g. grade-adjusted pace. */
    val other: Pair<String, List<Float?>>? = null,
)

private val GapColor = Color(0xFF8A877E)

private val ElevationColor = Color(0xFF9C7A4A)

/** The x axis: distance where there is one, else time. */
private fun axis(a: Activity, t: Track): Pair<List<Float>, (Float) -> String> {
    val byDist = t.dist.count { it != null } > t.t.size / 2 && (a.distanceM ?: 0.0) > 200
    return if (byDist) t.dist.map { ((it ?: 0.0) / 1000).toFloat() } to { v -> "${Fmt.one(v.toDouble())} km" }
    else t.t.map { it / 60f } to { v -> "${v.roundToInt()} min" }
}

private fun series(a: Activity, t: Track): List<Series> = buildList {
    if (t.speed.any { it != null } && a.sport != Sport.STRENGTH && (a.distanceM ?: 0.0) > 0) {
        val pace = a.sport.usesPace
        val swim = a.sport == Sport.SWIM
        val minFmt: (Float) -> String = { v -> "%d:%02d".format(v.toInt(), ((v - v.toInt()) * 60).roundToInt().coerceAtMost(59)) }
        add(Series(
            name = if (pace || swim) "Pace" else "Speed",
            color = G.sport(a.sport),
            ys = t.speed.map { s ->
                when {
                    s == null -> null
                    pace -> if (s < 0.5) null else (1000 / s / 60).toFloat() // min/km
                    swim -> if (s < 0.2) null else (100 / s / 60).toFloat()
                    else -> (s * 3.6).toFloat()
                }
            },
            yFmt = if (pace || swim) minFmt else { v -> v.roundToInt().toString() },
            tipFmt = if (pace || swim) { v -> minFmt(v) + if (swim) " /100m" else " /km" } else { v -> "${Fmt.one(v.toDouble())} km/h" },
            invert = pace || swim,
            lowLabel = "Slower", highLabel = "Faster",
            other = t.gap.takeIf { pace && it.any { g -> g != null } }?.let { g ->
                "Grade-adjusted" to g.map { v -> if (v == null || v < 0.5) null else (1000 / v / 60).toFloat() }
            },
        ))
    }
    if (t.hr.any { it != null }) {
        add(Series("Heart rate", G.heart, t.hr.map { it?.toFloat() }, { it.roundToInt().toString() }, { "${it.roundToInt()} bpm" }))
    }
    if (t.alt.count { it != null } > 10 && (a.elevGainM ?: 0.0) > 5) {
        add(Series("Elevation", ElevationColor, t.alt.map { it?.toFloat() }, { it.roundToInt().toString() }, { "${it.roundToInt()} m" }))
    }
}

/** The 2nd and 98th percentiles: the range a colour scale spreads over. */
private fun spread(ys: List<Float?>): Pair<Float, Float>? {
    val v = ys.filterNotNull().sorted()
    if (v.size < 2) return null
    val lo = v[(v.size * 0.02).toInt()]; val hi = v[(v.size * 0.98).toInt().coerceAtMost(v.lastIndex)]
    return if (hi - lo < 1e-3f) null else lo to hi
}

/** Pale to deep in the series' own colour: deeper is higher (or faster, for pace). */
private fun ramp(s: Series): Pair<Color, Color> = lerp(s.color, Color.White, 0.55f) to lerp(s.color, Color.Black, 0.35f)

private fun colorsFor(s: Series): List<Color?> {
    val (lo, hi) = spread(s.ys) ?: return s.ys.map { v -> v?.let { s.color } }
    val (pale, deep) = ramp(s)
    return s.ys.map { v ->
        v ?: return@map null
        var f = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        if (s.invert) f = 1 - f
        lerp(pale, deep, f)
    }
}

@Composable
private fun Charts(a: Activity, t: Track) {
    val (xs, xFmt) = axis(a, t)
    series(a, t).forEach { s ->
        Panel {
            Label(s.name, s.color)
            Spacer(Modifier.height(4.dp))
            LineChart(
                xs, s.ys, s.color,
                height = if (s.color == ElevationColor) 120.dp else 160.dp,
                yFmt = s.yFmt, tipFmt = s.tipFmt, xFmt = xFmt,
                invert = s.invert, robust = s.invert,
                others = listOfNotNull(s.other?.let { it.second to GapColor }),
                tipAt = s.other?.let { (_, g) -> { i -> "${xFmt(xs[i])}  ·  ${s.ys[i]?.let(s.tipFmt) ?: "–"}" + (g.getOrNull(i)?.let { "  ·  GAP ${s.yFmt(it)}" } ?: "") } },
                description = "${s.name} chart",
            )
            s.other?.let { (name, _) ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Legend(s.color, s.name, "")
                    Legend(GapColor, name, "")
                }
            }
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

/**
 * The route full screen: pinch and drag to explore. A chart along the bottom
 * shows one stream at a time and colours the route by it; drag across the
 * chart and a dot follows along the route.
 */
@Composable
fun FullMapScreen(a: Activity, vm: AppViewModel, onBack: () -> Unit) {
    val state = rememberTrack(a, vm)
    val track = (state as? TrackState.Ready)?.track
    var chosen by rememberSaveable { mutableIntStateOf(0) }
    var marker by remember { mutableStateOf<Int?>(null) }
    val all = remember(track) { track?.let { series(a, it) }.orEmpty() }
    val s = all.getOrNull(chosen) ?: all.firstOrNull()
    val colors = remember(s) { s?.let(::colorsFor) }
    val chartHeight = 260.dp
    Box(Modifier.fillMaxSize().background(G.wash)) {
        if (track != null && track.hasRoute) {
            RouteMap(
                track, G.sport(a.sport), Modifier.fillMaxSize(), interactive = true,
                colors = colors, marker = marker, bottomInset = if (s != null) chartHeight else 0.dp,
            )
        }
        BackBubble(onBack, Modifier.systemBarsPadding().padding(8.dp))
        if (track != null && s != null) {
            val (xs, xFmt) = remember(track) { axis(a, track) }
            Panel(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                padding = 14.dp,
            ) {
                if (all.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        all.forEachIndexed { i, x ->
                            FilterChip(
                                selected = x === s,
                                onClick = { chosen = i; marker = null },
                                label = { Text(x.name) },
                                leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(x.color)) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = G.wash, selectedLabelColor = G.ink, containerColor = Color.Transparent, labelColor = G.ink3),
                                border = null,
                            )
                        }
                    }
                } else {
                    Label(s.name, s.color)
                }
                LineChart(
                    xs, s.ys, s.color,
                    height = 130.dp,
                    yFmt = s.yFmt, tipFmt = s.tipFmt, xFmt = xFmt,
                    invert = s.invert, robust = s.invert,
                    others = listOfNotNull(s.other?.let { it.second to GapColor }),
                    onScrub = { marker = it },
                    description = "${s.name} chart",
                )
                spread(s.ys)?.let { (lo, hi) ->
                    val (pale, deep) = ramp(s)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text(s.lowLabel ?: s.tipFmt(lo), style = MaterialTheme.typography.bodySmall, color = G.ink3)
                        Box(
                            Modifier.padding(horizontal = 8.dp).weight(1f).height(6.dp).clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(pale, deep)))
                        )
                        Text(s.highLabel ?: s.tipFmt(hi), style = MaterialTheme.typography.bodySmall, color = G.ink3)
                    }
                }
            }
        }
    }
}

/** Exactly what Garmin sent for this activity, to see which fields are there. Selectable and copyable. */
@Composable
fun RawDataScreen(a: Activity, vm: AppViewModel, onBack: () -> Unit) {
    val raw by produceState<Pair<String, String>?>(null, a.id) { value = vm.raw(a) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(G.paper).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = G.ink) }
            Text("Garmin's raw data", style = MaterialTheme.typography.headlineSmall, color = G.ink, modifier = Modifier.weight(1f))
            raw?.let { (entry, detail) ->
                TextButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Garmin activity", "LIST ENTRY\n$entry\n\nDETAIL\n$detail"))
                }) { Text("Copy", color = G.ink2) }
            }
        }
        val r = raw
        if (r == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = G.ink2, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            return@Column
        }
        androidx.compose.foundation.text.selection.SelectionContainer(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Column {
                Text("Activity list entry", style = MaterialTheme.typography.titleMedium, color = G.ink, modifier = Modifier.padding(vertical = 8.dp))
                Text(r.first, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = G.ink2)
                if (r.second.isNotEmpty()) {
                    Text("Activity detail", style = MaterialTheme.typography.titleMedium, color = G.ink, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                    Text(r.second, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = G.ink2)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
