package com.nwbort.gview.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.DayView
import com.nwbort.gview.data.Sleep
import com.nwbort.gview.ui.theme.G
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val DAYS_BACK = 120

/** One day at a glance; swipe right for earlier days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    cache: Cache,
    syncing: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onActivity: (Long) -> Unit,
    onMetric: (Metric) -> Unit,
) {
    val today = LocalDate.now()
    val pager = rememberPagerState(pageCount = { DAYS_BACK })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        val date = today.minusDays(pager.currentPage.toLong())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Header(Fmt.dayTitle(date, today), Fmt.longDate(date).let { if (date == today || date == today.minusDays(1)) it else it.substringAfter(' ') }, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < DAYS_BACK - 1) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous day", tint = G.ink2)
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next day", tint = G.ink2)
            }
            HeaderSettings(onSettings)
        }
        PullToRefreshBox(isRefreshing = syncing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            HorizontalPager(pager, reverseLayout = true, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                DayPage(cache, cache.day(today.minusDays(page.toLong())), syncing, onActivity, onMetric)
            }
        }
    }
}

@Composable
private fun HeaderSettings(onSettings: () -> Unit) {
    IconButton(onClick = onSettings, modifier = Modifier.padding(end = 8.dp)) {
        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = G.ink2)
    }
}

@Composable
private fun DayPage(cache: Cache, day: DayView, syncing: Boolean, onActivity: (Long) -> Unit, onMetric: (Metric) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (day.isEmpty) {
            Panel {
                Text(
                    if (syncing) "Fetching from Garmin…" else "Nothing from Garmin for this day.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = G.ink2,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            return@Column
        }
        Tiles(cache, day, onMetric)
        day.sleep?.let { SleepCard(it) { onMetric(Metric.SLEEP) } }
        day.summary?.let { s ->
            if (day.bb != null || s.bbMax != null) BatteryCard(day) { onMetric(Metric.BODY_BATTERY) }
            if (s.steps != null) MovementCard(day) { onMetric(Metric.STEPS) }
            if (s.stressAvg != null) StressCard(day) { onMetric(Metric.STRESS) }
        }
        if (day.activities.isNotEmpty()) {
            Label("Activities", modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            day.activities.forEach { ActivityRow(it, onClick = { onActivity(it.id) }) }
        }
    }
}

/** The four numbers people look at first, each with its last two weeks. */
@Composable
private fun Tiles(cache: Cache, day: DayView, onMetric: (Metric) -> Unit) {
    val s = day.summary
    val tiles = listOf(
        Triple(Metric.BODY_BATTERY, (s?.bbLatest?.takeIf { day.date == LocalDate.now() } ?: s?.bbMax)?.toString(), if (day.date == LocalDate.now() && s?.bbLatest != null) "now" else "peak"),
        Triple(Metric.SLEEP, day.sleep?.score?.toString(), "score"),
        Triple(Metric.HRV, day.hrv?.lastNight?.toString(), "ms"),
        Triple(Metric.RESTING_HR, s?.restingHr?.takeIf { it > 0 }?.toString(), "bpm"),
    )
    tiles.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (m, v, unit) ->
                Panel(Modifier.weight(1f), onClick = { onMetric(m) }, padding = 16.dp) {
                    Label(if (m == Metric.RESTING_HR) "Resting HR" else m.title, m.color)
                    Spacer(Modifier.height(4.dp))
                    Value(v ?: "–", if (v != null) unit else null, style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(6.dp))
                    val spark = (13 downTo 0).map { m.value(cache, day.date.minusDays(it.toLong())) }
                    Sparkline(spark, m.color, Modifier.fillMaxWidth().height(28.dp))
                }
            }
        }
    }
}

private val clock = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun SleepCard(sleep: Sleep, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Sleep", G.sleep, Modifier.weight(1f))
            sleep.qualifier?.let { Pill(Fmt.titleCase(it), G.sleep) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Value(Fmt.hm(sleep.seconds.toDouble()), style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.weight(1f))
            if (sleep.startLocalMs != null && sleep.endLocalMs != null) {
                fun t(ms: Long) = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).format(clock).lowercase()
                Text("${t(sleep.startLocalMs)} – ${t(sleep.endLocalMs)}", style = MaterialTheme.typography.bodyMedium, color = G.ink2, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Segments(listOf(sleep.deepS.toFloat() to G.deep, sleep.lightS.toFloat() to G.light, sleep.remS.toFloat() to G.rem, sleep.awakeS.toFloat() to G.awake))
        Spacer(Modifier.height(10.dp))
        LegendGrid(listOf(
            Triple(G.deep, "Deep", Fmt.hm(sleep.deepS.toDouble())),
            Triple(G.light, "Light", Fmt.hm(sleep.lightS.toDouble())),
            Triple(G.rem, "REM", Fmt.hm(sleep.remS.toDouble())),
            Triple(G.awake, "Awake", Fmt.hm(sleep.awakeS.toDouble())),
        ))
        val extras = listOfNotNull(
            sleep.avgHr?.let { "Avg HR ${it.toInt()}" },
            sleep.respiration?.let { "Breathing ${Fmt.one(it)}/min" },
            sleep.needMin?.let { "Need ${Fmt.hm(it * 60.0)}" },
        )
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(extras.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = G.ink3)
        }
    }
}

@Composable
private fun BatteryCard(day: DayView, onClick: () -> Unit) {
    val s = day.summary
    Panel(onClick = onClick) {
        Label("Body Battery", G.battery)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Value("${s?.bbMin ?: "–"}–${s?.bbMax ?: "–"}", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.weight(1f))
            Text("+${s?.bbCharged ?: 0}  /  −${s?.bbDrained ?: 0}", style = MaterialTheme.typography.bodyMedium, color = G.ink2, modifier = Modifier.padding(bottom = 4.dp))
        }
        day.bb?.let { bb ->
            Spacer(Modifier.height(8.dp))
            LineChart(
                xs = bb.minutes.map { it.toFloat() },
                ys = bb.levels.map { it.toFloat() },
                color = G.battery,
                height = 140.dp,
                yMin = 0f,
                yMax = 100f,
                xFmt = { m -> "%d:%02d".format(m.toInt() / 60, m.toInt() % 60) },
                xLabels = listOf(0f to "12am", 360f to "6am", 720f to "12pm", 1080f to "6pm", 1439f to "12am"),
                description = "Body Battery through the day",
            )
        }
    }
}

@Composable
private fun MovementCard(day: DayView, onClick: () -> Unit) {
    val s = day.summary ?: return
    Panel(onClick = onClick) {
        Label("Movement", G.steps)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Ring((s.steps ?: 0) / (s.stepGoal ?: 10000).coerceAtLeast(1).toFloat(), G.steps, Modifier.size(96.dp), stroke = 9.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.int(s.steps), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"), color = G.ink)
                    Text("steps", style = MaterialTheme.typography.bodySmall, color = G.ink3)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatRow(
                    Triple("Distance", Fmt.km(s.distanceM, 1), "km"),
                    Triple("Floors", s.floors?.toInt()?.toString() ?: "–", null),
                )
                StatRow(
                    Triple("Intensity", s.intensityMin?.toString() ?: "–", "min"),
                    Triple("Active", Fmt.int(s.activeKcal), "kcal"),
                )
            }
        }
        s.stepGoal?.let {
            Spacer(Modifier.height(8.dp))
            Text("Goal ${Fmt.int(it)} steps", style = MaterialTheme.typography.bodySmall, color = G.ink3)
        }
    }
}

@Composable
private fun StressCard(day: DayView, onClick: () -> Unit) {
    val s = day.summary ?: return
    val rest = androidx.compose.ui.graphics.Color(0xFF86B6EF)
    val low = androidx.compose.ui.graphics.Color(0xFFF6CF7A)
    val high = androidx.compose.ui.graphics.Color(0xFFB87800)
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Stress", G.stress, Modifier.weight(1f))
            s.stressQualifier?.let { Pill(Fmt.titleCase(it), G.ink2) }
        }
        Spacer(Modifier.height(6.dp))
        Value(s.stressAvg.toString(), "avg", style = MaterialTheme.typography.displaySmall)
        if (s.restP != null) {
            Spacer(Modifier.height(12.dp))
            Segments(listOf((s.restP).toFloat() to rest, (s.lowP ?: 0.0).toFloat() to low, (s.mediumP ?: 0.0).toFloat() to G.stress, (s.highP ?: 0.0).toFloat() to high))
            Spacer(Modifier.height(10.dp))
            fun pct(p: Double?) = "${((p ?: 0.0).let { if (it > 1.0) it else it * 100 }).toInt()}%"
            LegendGrid(listOf(
                Triple(rest, "Rest", pct(s.restP)),
                Triple(low, "Low", pct(s.lowP)),
                Triple(G.stress, "Medium", pct(s.mediumP)),
                Triple(high, "High", pct(s.highP)),
            ))
        }
    }
}
