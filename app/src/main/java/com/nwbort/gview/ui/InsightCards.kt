package com.nwbort.gview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.nwbort.gview.data.Analysis
import com.nwbort.gview.data.Cache
import com.nwbort.gview.ui.theme.G
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private val FatigueColor = G.steps

/** Long-term load (fitness) against the last week's (fatigue); the gap between is form. */
@Composable
fun FitnessCard(cache: Cache, range: Range) {
    val today = LocalDate.now()
    val curve = remember(cache.activities, cache.summaries.size) { Analysis.loadCurve(cache, today) }
    if (curve.size < 14) return
    val start = range.start(cache, today)
    val shown = curve.filter { !it.date.isBefore(start) }
    if (shown.isEmpty()) return
    val now = shown.last()
    Panel {
        Label("Fitness & fatigue", G.battery)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Value("${if (now.form >= 0) "+" else "−"}${abs(now.form).roundToInt()}", "form", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(10.dp))
            Pill(Analysis.formLabel(now.form), if (now.form < -30) G.heart else G.battery, Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("Fitness ${now.fitness.roundToInt()}", style = MaterialTheme.typography.bodyMedium, color = G.ink2)
                Text("Fatigue ${now.fatigue.roundToInt()}", style = MaterialTheme.typography.bodyMedium, color = G.ink2)
            }
        }
        Spacer(Modifier.height(8.dp))
        val first = shown.first().date
        val xs = shown.map { (it.date.toEpochDay() - first.toEpochDay()).toFloat() }
        LineChart(
            xs, shown.map { it.fitness.toFloat() }, G.battery,
            xFmt = { Fmt.shortDate(first.plusDays(it.toLong())) },
            others = listOf(shown.map { it.fatigue.toFloat() } to FatigueColor),
            tipAt = { i ->
                val d = shown[i]
                "${Fmt.shortDate(d.date)}  ·  fitness ${d.fitness.roundToInt()}  ·  fatigue ${d.fatigue.roundToInt()}"
            },
            description = "Fitness and fatigue chart",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(G.battery, "Fitness", "6-week load")
            Legend(FatigueColor, "Fatigue", "last week")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Form is fitness minus fatigue. Building blocks sit around −10 to −30; well below that is overreaching. " +
                "Taper so it's positive on race day.",
            style = MaterialTheme.typography.bodySmall,
            color = G.ink3,
        )
    }
}

/** Grade-adjusted pace at a fixed heart rate, run by run, with its trend; and long-run drift. */
@Composable
fun EfficiencyCard(cache: Cache, range: Range) {
    val today = LocalDate.now()
    val start = range.start(cache, today)
    val all = cache.runStats.values
    val ref = Analysis.referenceHr(all) ?: return
    val runs = all.filter { it.efficiency != null && it.movingS >= 20 * 60 }.sortedBy { it.date }
    val shown = runs.filter { LocalDate.parse(it.date) >= start }
    if (shown.size < 3) return
    // Minutes per km at the reference heart rate.
    fun paceAt(e: Double) = (1000 / Analysis.speedAt(e, ref) / 60).toFloat()
    val raw = shown.map { paceAt(it.efficiency!!) }
    val trend = Analysis.rolling(runs.map { paceAt(it.efficiency!!).toDouble() }, 6).takeLast(shown.size).map { it.toFloat() }
    val first = LocalDate.parse(shown.first().date)
    val xs = shown.map { (LocalDate.parse(it.date).toEpochDay() - first.toEpochDay()).toFloat() }
    val minFmt: (Float) -> String = { v -> "%d:%02d".format(v.toInt(), ((v - v.toInt()) * 60).roundToInt().coerceAtMost(59)) }
    // Seconds per km, negative is faster; the start is steadied over its first few points.
    val change = (trend.last() - trend.take(3).average().toFloat()) * 60
    Panel {
        Label("Aerobic efficiency", G.fitness)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Value(minFmt(trend.last()), "/km at $ref bpm", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.weight(1f))
            if (abs(change) >= 1) Text(
                "${abs(change).roundToInt()} s/km ${if (change < 0) "faster" else "slower"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (change < 0) G.fitness else G.ink2,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        LineChart(
            xs, trend, G.fitness,
            yFmt = minFmt,
            xFmt = { Fmt.shortDate(first.plusDays(it.toLong())) },
            invert = true, fill = false,
            others = listOf(raw to lerp(G.fitness, G.card, 0.55f)),
            othersAsDots = true,
            tipAt = { i -> "${Fmt.shortDate(LocalDate.parse(shown[i].date))}  ·  ${minFmt(raw[i])} this run  ·  ${minFmt(trend[i])} trend" },
            description = "Aerobic efficiency chart",
        )
        Text(
            "Each dot is a run's grade-adjusted pace scaled to $ref bpm; the line is the average of the last six. " +
                "Higher on the chart is faster: fitter.",
            style = MaterialTheme.typography.bodySmall,
            color = G.ink3,
        )

        val long = shown.filter { it.driftPct != null && it.movingS >= 75 * 60 }
        if (long.size >= 2) {
            Spacer(Modifier.height(16.dp))
            Label("Long-run HR drift", G.heart)
            Spacer(Modifier.height(4.dp))
            val lf = LocalDate.parse(long.first().date)
            LineChart(
                long.map { (LocalDate.parse(it.date).toEpochDay() - lf.toEpochDay()).toFloat() },
                long.map { it.driftPct!!.toFloat() },
                G.heart,
                height = 120.dp,
                yFmt = { "${it.roundToInt()}%" },
                tipFmt = { "${Fmt.one(it.toDouble())}% drift" },
                xFmt = { Fmt.shortDate(lf.plusDays(it.toLong())) },
                band = 0f to 5f,
                fill = false, dots = true,
                description = "Long-run drift chart",
            )
            Text(
                "Runs over 75 minutes: how much heart rate rose for the same grade-adjusted pace in the second half. " +
                    "Under 5% (shaded) means the distance is within your endurance.",
                style = MaterialTheme.typography.bodySmall,
                color = G.ink3,
            )
        }
    }
}

/** What the data says about recovering: plain sentences, each with a dot for good or not. */
@Composable
fun RecoveryCard(cache: Cache) {
    val findings = remember(cache.activities, cache.hrv.size, cache.sleep.size, cache.summaries.size) { Analysis.recovery(cache) }
    Panel {
        Label("Recovery", G.hrv)
        Spacer(Modifier.height(8.dp))
        if (findings.isEmpty()) {
            Text(
                "Needs a few weeks of training alongside HRV and sleep before there's anything clear to say.",
                style = MaterialTheme.typography.bodyMedium,
                color = G.ink3,
            )
            return@Panel
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            findings.forEach { f ->
                Row {
                    Box(
                        Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(
                            when (f.good) { true -> G.hrv; false -> G.heart; null -> G.ink3 }
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(f.headline, style = MaterialTheme.typography.titleSmall, color = G.ink)
                        Text(f.detail, style = MaterialTheme.typography.bodySmall, color = G.ink2)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("From everything synced. Hard days are your top quarter by training load.", style = MaterialTheme.typography.bodySmall, color = G.ink3)
    }
}
