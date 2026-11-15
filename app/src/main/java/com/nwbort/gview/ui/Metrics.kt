package com.nwbort.gview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nwbort.gview.data.Cache
import com.nwbort.gview.ui.theme.G
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class Range(val label: String, val days: Long) {
    MONTH("4W", 28), QUARTER("3M", 91), YEAR("1Y", 365), ALL("All", 0);

    /** Long enough that daily points are shown as weekly averages. */
    val weekly: Boolean get() = this == YEAR || this == ALL

    /** The first day shown: for All, the first day there's anything synced. */
    fun start(c: Cache, today: LocalDate): LocalDate = if (this == ALL) {
        (c.summaries.keys + c.sleep.keys + c.activities.map { it.start.take(10) }).minOrNull()
            ?.let(LocalDate::parse)?.coerceAtMost(today.minusDays(27)) ?: today.minusDays(364)
    } else today.minusDays(days - 1)
}

/** A daily health figure the app can chart over time. */
enum class Metric(val title: String, val unit: String, val color: Color, val bars: Boolean = false, val sparse: Boolean = false) {
    SLEEP("Sleep", "h", G.sleep, bars = true),
    HRV("HRV", "ms", G.hrv),
    BODY_BATTERY("Body Battery", "", G.battery),
    RESTING_HR("Resting heart rate", "bpm", G.heart),
    STEPS("Steps", "", G.steps, bars = true),
    STRESS("Stress", "", G.stress),
    VO2MAX("VO₂ max", "", G.fitness, sparse = true),
    WEIGHT("Weight", "kg", G.weight, sparse = true);

    fun value(c: Cache, d: LocalDate): Float? {
        val k = d.toString()
        return when (this) {
            SLEEP -> c.sleep[k]?.seconds?.let { it / 3600f }
            HRV -> c.hrv[k]?.lastNight?.toFloat()
            // The day's peak is the morning charge: the number people check.
            BODY_BATTERY -> c.summaries[k]?.bbMax?.toFloat()
            RESTING_HR -> c.summaries[k]?.restingHr?.takeIf { it > 0 }?.toFloat()
            STEPS -> c.summaries[k]?.steps?.toFloat()
            STRESS -> c.summaries[k]?.stressAvg?.toFloat()
            VO2MAX -> c.sortedActivities.firstOrNull { it.start.startsWith(k) && it.vo2Max != null }?.vo2Max?.toFloat()
            WEIGHT -> c.weight[k]?.kg?.toFloat()
        }
    }

    fun fmt(v: Float): String = when (this) {
        SLEEP -> Fmt.hm(v * 3600.0)
        STEPS -> Fmt.int(v.roundToInt())
        WEIGHT -> Fmt.one(v.toDouble())
        VO2MAX -> v.roundToInt().toString()
        else -> v.roundToInt().toString()
    }

    /** Axis labels: shorter than [fmt]. */
    fun axis(v: Float): String = when (this) {
        SLEEP -> "${v.roundToInt()}h"
        STEPS -> if (v >= 1000) "${(v / 1000).roundToInt()}k" else v.roundToInt().toString()
        else -> v.roundToInt().toString()
    }

    /** One value per day over [range], or per week for long ranges of bars. Dates are the day (or Monday). */
    fun series(c: Cache, range: Range, today: LocalDate = LocalDate.now()): List<Pair<LocalDate, Float?>> {
        val start = range.start(c, today)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val daily = days.map { it to value(c, it) }
        if (sparse) return daily.filter { it.second != null }
        // Weekly averages for bars over a year, and for everything over All.
        if (!(bars && range.weekly) && range != Range.ALL) return daily
        return daily.groupBy { it.first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .map { (monday, vs) -> monday to vs.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.average()?.toFloat() }
    }
}

@Composable
fun RangePicker(range: Range, onRange: (Range) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Range.entries.forEach { r ->
            FilterChip(
                selected = r == range,
                onClick = { onRange(r) },
                label = { Text(r.label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = G.ink, selectedLabelColor = Color.White, containerColor = G.card, labelColor = G.ink2),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = r == range, borderColor = G.line),
            )
        }
    }
}

/** A metric over a range: its latest value and average, and the chart. */
@Composable
fun TrendCard(metric: Metric, cache: Cache, range: Range, modifier: Modifier = Modifier, height: Dp = 150.dp, onClick: (() -> Unit)? = null) {
    val s = metric.series(cache, range)
    val vals = s.mapNotNull { it.second }
    Panel(modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Label(metric.title, metric.color)
                Spacer(Modifier.height(6.dp))
                val latest = vals.lastOrNull()
                Value(latest?.let(metric::fmt) ?: "–", if (metric == Metric.SLEEP) null else metric.unit)
            }
            if (vals.size > 1) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("${range.label} average", style = MaterialTheme.typography.bodySmall, color = G.ink3)
                    Text(metric.fmt(vals.average().toFloat()), style = MaterialTheme.typography.titleMedium, color = G.ink2)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vals.isEmpty()) {
            Text("No ${metric.title.lowercase()} in this range.", style = MaterialTheme.typography.bodyMedium, color = G.ink3, modifier = Modifier.padding(vertical = 24.dp))
            return@Panel
        }
        val weekly = (metric.bars && range.weekly) || (range == Range.ALL && !metric.sparse)
        if (metric.bars) {
            BarChart(
                s.map { it.second },
                metric.color,
                height = height,
                labels = s.map { (d, _) -> when (range) { Range.MONTH -> d.dayOfMonth.toString(); Range.ALL -> Fmt.monthYear(d); else -> Fmt.shortDate(d) } },
                tipLabels = s.map { (d, _) -> if (weekly) "Week of ${Fmt.shortDate(d)}" else "${Fmt.weekday(d)} ${Fmt.shortDate(d)}" },
                yFmt = metric::axis,
                tipFmt = metric::fmt,
                goal = if (metric == Metric.STEPS) cache.summaries.values.maxByOrNull { it.date }?.stepGoal?.toFloat() else null,
                description = "${metric.title} chart",
            )
        } else {
            val first = s.first().first
            val hrvBand = if (metric == Metric.HRV) cache.hrv.values.maxByOrNull { it.date }?.let { h ->
                if (h.balancedLow != null && h.balancedUpper != null) h.balancedLow.toFloat() to h.balancedUpper.toFloat() else null
            } else null
            LineChart(
                xs = s.map { (d, _) -> (d.toEpochDay() - first.toEpochDay()).toFloat() },
                ys = s.map { it.second },
                color = metric.color,
                height = height,
                yFmt = metric::axis,
                tipFmt = metric::fmt,
                xFmt = { first.plusDays(it.toLong()).let { d -> if (range == Range.ALL) "${Fmt.shortDate(d)} ${d.year}" else Fmt.shortDate(d) } },
                band = hrvBand,
                dots = metric.sparse && s.size < 40,
                fill = !metric.sparse,
                description = "${metric.title} chart",
            )
            if (hrvBand != null) {
                Text("Shaded: your balanced range", style = MaterialTheme.typography.bodySmall, color = G.ink3)
            }
        }
    }
}
