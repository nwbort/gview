package com.nwbort.gview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.Sport
import com.nwbort.gview.ui.theme.G
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

@Composable
fun TrendsScreen(cache: Cache, onSettings: () -> Unit, onMetric: (Metric) -> Unit) {
    var range by rememberSaveable { mutableStateOf(Range.QUARTER) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Trends", null, onSettings)
        RangePicker(range, { range = it }, Modifier.padding(horizontal = 16.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VolumeCard(cache, range)
            Metric.entries.forEach { m ->
                // Weight and VO₂ max only show up for people who have them.
                if (m.sparse && m.series(cache, Range.YEAR).isEmpty()) return@forEach
                TrendCard(m, cache, range, onClick = { onMetric(m) })
            }
        }
    }
}

/** Distance (or time, for sports without distance) per day or week, by sport. */
@Composable
private fun VolumeCard(cache: Cache, range: Range) {
    var sport by rememberSaveable { mutableStateOf<Sport?>(null) }
    val today = LocalDate.now()
    val start = today.minusDays(range.days - 1)
    val inRange = cache.activities.filter { it.date >= start && (sport == null || it.sport == sport) }
    val sports = cache.activities.filter { it.date >= start }.map { it.sport }.distinct().sortedBy { it.ordinal }
    val useTime = inRange.sumOf { it.distanceM ?: 0.0 } < 1.0
    fun amount(list: List<com.nwbort.gview.data.Activity>) = if (useTime) list.sumOf { it.timeS ?: 0.0 } / 3600 else list.sumOf { it.distanceM ?: 0.0 } / 1000
    val weekly = range != Range.MONTH
    val buckets: List<Pair<LocalDate, Float>> = if (weekly) {
        val firstMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        generateSequence(firstMonday) { it.plusWeeks(1) }.takeWhile { !it.isAfter(today) }.map { m ->
            m to amount(inRange.filter { it.date >= m && it.date < m.plusWeeks(1) }).toFloat()
        }.toList()
    } else {
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d -> d to amount(inRange.filter { it.date == d }).toFloat() }.toList()
    }
    val color = sport?.let { G.sport(it) } ?: G.ink2
    Panel {
        Label("Training volume", color)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Value(if (useTime) Fmt.hm(amount(inRange) * 3600) else Fmt.km(amount(inRange) * 1000, 0), if (useTime) null else "km", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${inRange.size} activities", style = MaterialTheme.typography.bodyMedium, color = G.ink2)
                Text(Fmt.hm(inRange.sumOf { it.timeS ?: 0.0 }), style = MaterialTheme.typography.bodyMedium, color = G.ink2)
            }
        }
        if (sports.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<Sport?>(null) + sports).take(5).forEach { s ->
                    FilterChip(
                        selected = s == sport,
                        onClick = { sport = s },
                        label = { Text(s?.label ?: "All") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = G.wash, selectedLabelColor = G.ink, containerColor = Color.Transparent, labelColor = G.ink3),
                        border = null,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val unit = if (useTime) "h" else "km"
        BarChart(
            buckets.map { it.second },
            color,
            labels = buckets.map { (d, _) -> if (weekly) Fmt.shortDate(d) else d.dayOfMonth.toString() },
            tipLabels = buckets.map { (d, _) -> if (weekly) "Week of ${Fmt.shortDate(d)}" else "${Fmt.weekday(d)} ${Fmt.shortDate(d)}" },
            yFmt = { "${it.roundToInt()}" },
            tipFmt = { "${Fmt.one(it.toDouble())} $unit" },
            description = "Training volume chart",
        )
    }
}

/** One metric up close: bigger chart, range picker, and its spread. */
@Composable
fun MetricScreen(metric: Metric, cache: Cache, onBack: () -> Unit) {
    var range by rememberSaveable { mutableStateOf(Range.MONTH) }
    val vals = metric.series(cache, range).mapNotNull { it.second }
    Column(Modifier.fillMaxSize().background(G.paper).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = G.ink) }
            Text(metric.title, style = MaterialTheme.typography.headlineMedium, color = G.ink)
        }
        RangePicker(range, { range = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TrendCard(metric, cache, range, height = 240.dp)
            if (vals.size > 1) {
                Panel {
                    StatRow(
                        Triple("Lowest", metric.fmt(vals.min()), metric.unit.takeIf { metric != Metric.SLEEP }),
                        Triple("Average", metric.fmt(vals.average().toFloat()), metric.unit.takeIf { metric != Metric.SLEEP }),
                        Triple("Highest", metric.fmt(vals.max()), metric.unit.takeIf { metric != Metric.SLEEP }),
                    )
                }
            }
            if (metric == Metric.SLEEP) SleepScoreCard(cache, range)
            Text(about(metric), style = MaterialTheme.typography.bodyMedium, color = G.ink2, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** Sleep score isn't a [Metric] of its own; it's charted on the sleep page. */
@Composable
private fun SleepScoreCard(cache: Cache, range: Range) {
    val today = LocalDate.now()
    val start = today.minusDays(range.days - 1)
    val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
    val ys = days.map { cache.sleep[it.toString()]?.score?.toFloat() }
    if (ys.all { it == null }) return
    Panel {
        Label("Sleep score", G.sleep)
        Spacer(Modifier.height(6.dp))
        LineChart(
            days.indices.map { it.toFloat() }, ys, G.sleep,
            xFmt = { Fmt.shortDate(start.plusDays(it.toLong())) },
            yMin = 0f, yMax = 100f,
            description = "Sleep score chart",
        )
    }
}

private fun about(m: Metric): String = when (m) {
    Metric.SLEEP -> "Time asleep each night, not counting time awake in bed or naps."
    Metric.HRV -> "Average heart rate variability overnight. Garmin compares it with your own baseline: the shaded band is your balanced range."
    Metric.BODY_BATTERY -> "Each day's highest Body Battery: usually how charged you woke up."
    Metric.RESTING_HR -> "Your lowest steady heart rate of the day. A rise of a few beats can mean fatigue, illness or stress."
    Metric.STEPS -> "Steps each day; the dashed line is your step goal. Over a year, each bar is a week's daily average."
    Metric.STRESS -> "Average stress level through the day, from 0 to 100, measured from heart rate variability while still."
    Metric.VO2MAX -> "Garmin's estimate of your aerobic fitness, updated after outdoor runs and rides with heart rate."
    Metric.WEIGHT -> "Weigh-ins from a Garmin scale or entered in Garmin Connect."
}
