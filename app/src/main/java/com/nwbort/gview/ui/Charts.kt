package com.nwbort.gview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nwbort.gview.ui.theme.G
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private val axisText = TextStyle(color = G.ink3, fontSize = 10.sp, fontFeatureSettings = "tnum")
private val tipText = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

/** Round gridline values: 1, 2, 2.5 or 5 times a power of ten. */
private fun niceTicks(lo: Float, hi: Float, count: Int = 3): List<Float> {
    if (hi <= lo) return listOf(lo)
    val raw = (hi - lo) / count
    val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw }
    val out = mutableListOf<Float>()
    var v = ceil(lo / step) * step
    while (v <= hi + step * 0.001f) { out += v; v += step }
    return out
}

/** A pressed-and-dragged x position, for scrubbing a chart; null when not touched. */
private fun Modifier.scrub(onX: (Float?) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onX(down.position.x)
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull() ?: break
            if (!c.pressed) break
            // Mostly-horizontal drags scrub; let vertical ones scroll the page.
            val d = c.position - c.previousPosition
            if (abs(d.x) > abs(d.y)) c.consume()
            onX(c.position.x)
        }
        onX(null)
    }
}

private fun DrawScope.tooltip(tm: TextMeasurer, text: String, x: Float) {
    val layout = tm.measure(text, tipText)
    val padH = 8.dp.toPx(); val padV = 4.dp.toPx()
    val w = layout.size.width + 2 * padH
    val h = layout.size.height + 2 * padV
    val left = (x - w / 2).coerceIn(0f, size.width - w)
    drawRoundRect(G.ink, Offset(left, 0f), Size(w, h), CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(left + padH, padV))
}

/**
 * A line (optionally filled) over x, with nulls as gaps. Drag across it to read
 * values. [invert] puts smaller values at the top (pace: faster is higher).
 */
@Composable
fun LineChart(
    xs: List<Float>,
    ys: List<Float?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    yFmt: (Float) -> String = { it.toInt().toString() },
    tipFmt: (Float) -> String = yFmt,
    xFmt: (Float) -> String = { it.toInt().toString() },
    xLabels: List<Pair<Float, String>>? = null,
    yMin: Float? = null,
    yMax: Float? = null,
    band: Pair<Float, Float>? = null,
    fill: Boolean = true,
    invert: Boolean = false,
    dots: Boolean = false,
    /** Fit the scale to the middle 96% of values and pin outliers to the edge. */
    robust: Boolean = false,
    /** Called with the index (into [xs]) being read as a finger drags across, and null on release. */
    onScrub: ((Int?) -> Unit)? = null,
    description: String = "",
) {
    val tm = rememberTextMeasurer()
    var touch by remember { mutableStateOf<Float?>(null) }
    val clipped = remember(ys, robust) {
        if (!robust) ys else {
            val sorted = ys.filterNotNull().sorted()
            if (sorted.size < 20) ys else {
                val lo = sorted[(sorted.size * 0.02).toInt()]; val hi = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.lastIndex)]
                ys.map { it?.coerceIn(lo, hi) }
            }
        }
    }
    @Suppress("NAME_SHADOWING") val ys = clipped
    val pts = xs.indices.mapNotNull { i -> ys.getOrNull(i)?.let { xs[i] to it } }
    // Where the plot sits, from the last draw: lets a touch be turned into a data index.
    val geometry = remember { FloatArray(4) }
    val latest = rememberUpdatedState(Triple(xs, ys, onScrub))
    Canvas(
        modifier.fillMaxWidth().height(height).scrub { x ->
            touch = x
            // The gesture handler outlives recompositions: read the current data, not the first.
            val (cx, cy, cb) = latest.value
            cb?.invoke(x?.let { nearestIndex(cx, cy, it, geometry) })
        }.semantics { contentDescription = description }
    ) {
        if (pts.isEmpty()) return@Canvas
        val topPad = 26.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val leftPad = 30.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - topPad - bottomPad
        var lo = yMin ?: pts.minOf { it.second }
        var hi = yMax ?: pts.maxOf { it.second }
        band?.let { lo = min(lo, it.first); hi = max(hi, it.second) }
        if (hi - lo < 1e-3f) { lo -= 1; hi += 1 }
        if (yMin == null || yMax == null) {
            val pad = (hi - lo) * 0.08f
            if (yMin == null) lo -= pad
            if (yMax == null) hi += pad
        }
        val x0 = xs.first(); val x1 = max(xs.last(), x0 + 1e-3f)
        geometry[0] = leftPad; geometry[1] = w; geometry[2] = x0; geometry[3] = x1
        fun px(x: Float) = leftPad + (x - x0) / (x1 - x0) * w
        fun py(y: Float): Float { val f = (y - lo) / (hi - lo); return topPad + (if (invert) f else 1 - f) * h }

        // Recessive grid with labels at the left.
        niceTicks(lo, hi).forEach { v ->
            val y = py(v)
            drawLine(G.line, Offset(leftPad, y), Offset(size.width, y), 1.dp.toPx())
            val l = tm.measure(yFmt(v), axisText)
            drawText(l, topLeft = Offset(0f, y - l.size.height / 2))
        }
        (xLabels ?: listOf(x0 to xFmt(x0), x1 to xFmt(x1))).forEach { (x, s) ->
            val l = tm.measure(s, axisText)
            val cx = (px(x) - l.size.width / 2).coerceIn(leftPad, size.width - l.size.width)
            drawText(l, topLeft = Offset(cx, size.height - l.size.height))
        }
        band?.let { (a, b) ->
            val top = min(py(a), py(b)); val bot = max(py(a), py(b))
            drawRect(color.copy(alpha = 0.08f), Offset(leftPad, top), Size(w, bot - top))
        }

        // The line, broken at gaps; gently curved between points.
        val segments = mutableListOf<MutableList<Offset>>()
        var current: MutableList<Offset>? = null
        xs.indices.forEach { i ->
            val y = ys.getOrNull(i)
            if (y == null) { current = null } else {
                if (current == null) { current = mutableListOf<Offset>().also { segments += it } }
                current!!.add(Offset(px(xs[i]), py(y)))
            }
        }
        val baseY = topPad + h
        segments.forEach { seg ->
            val path = Path()
            path.moveTo(seg[0].x, seg[0].y)
            for (k in 1 until seg.size) {
                val a = seg[k - 1]; val b = seg[k]
                val mx = (a.x + b.x) / 2
                path.cubicTo(mx, a.y, mx, b.y, b.x, b.y)
            }
            if (fill && seg.size > 1) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(seg.last().x, baseY)
                    lineTo(seg.first().x, baseY)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)), startY = topPad, endY = topPad + h))
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (dots || seg.size == 1) seg.forEach {
                drawCircle(Color.White, 4.5.dp.toPx(), it)
                drawCircle(color, 3.dp.toPx(), it)
            }
        }

        val t = touch
        if (t != null) {
            val near = pts.minBy { abs(px(it.first) - t) }
            val p = Offset(px(near.first), py(near.second))
            drawLine(G.ink3, Offset(p.x, topPad), Offset(p.x, topPad + h), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(Color.White, 6.dp.toPx(), p)
            drawCircle(color, 4.dp.toPx(), p)
            tooltip(tm, "${xFmt(near.first)}  ·  ${tipFmt(near.second)}", p.x)
        } else {
            // Mark the most recent value.
            val last = pts.last()
            val p = Offset(px(last.first), py(last.second))
            drawCircle(Color.White, 5.dp.toPx(), p)
            drawCircle(color, 3.5.dp.toPx(), p)
        }
    }
}

/** The index of the non-null value nearest to screen x [px], given the plot's [geometry]. */
private fun nearestIndex(xs: List<Float>, ys: List<Float?>, px: Float, geometry: FloatArray): Int? {
    val (left, w, x0, x1) = geometry.toList()
    if (w <= 0f) return null
    val x = x0 + (px - left) / w * (x1 - x0)
    return xs.indices.filter { ys.getOrNull(it) != null }.minByOrNull { abs(xs[it] - x) }
}

/** Bars from zero, rounded at the top, 2 dp apart. Tap or drag to read one. */
@Composable
fun BarChart(
    values: List<Float?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    labels: List<String> = emptyList(),
    tipLabels: List<String> = labels,
    yFmt: (Float) -> String = { it.toInt().toString() },
    tipFmt: (Float) -> String = yFmt,
    goal: Float? = null,
    description: String = "",
) {
    val tm = rememberTextMeasurer()
    var touch by remember { mutableStateOf<Float?>(null) }
    Canvas(modifier.fillMaxWidth().height(height).scrub { touch = it }.semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val topPad = 26.dp.toPx(); val bottomPad = 18.dp.toPx(); val leftPad = 30.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - topPad - bottomPad
        val hi = max(values.filterNotNull().maxOrNull() ?: 1f, goal ?: 0f).let { if (it <= 0f) 1f else it * 1.08f }
        fun py(v: Float) = topPad + h - v / hi * h
        niceTicks(0f, hi).forEach { v ->
            val y = py(v)
            drawLine(G.line, Offset(leftPad, y), Offset(size.width, y), 1.dp.toPx())
            val l = tm.measure(yFmt(v), axisText)
            drawText(l, topLeft = Offset(0f, y - l.size.height / 2))
        }
        goal?.let {
            val y = py(it)
            drawLine(G.ink3, Offset(leftPad, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        val gap = 2.dp.toPx()
        val slot = w / values.size
        val bw = max(1f, slot - gap)
        val r = min(4.dp.toPx(), bw / 2)
        val sel = touch?.let { ((it - leftPad) / slot).toInt().coerceIn(0, values.lastIndex) }
        values.forEachIndexed { i, v ->
            if (v == null || v <= 0f) return@forEachIndexed
            val x = leftPad + i * slot + gap / 2
            val top = py(v)
            val c = if (sel == null || sel == i) color else color.copy(alpha = 0.35f)
            // Round the top only: a rounded rect, with its bottom corners squared off.
            drawRoundRect(c, Offset(x, top), Size(bw, topPad + h - top), CornerRadius(r))
            if (topPad + h - top > r) drawRect(c, Offset(x, topPad + h - r), Size(bw, r))
        }
        // Sparse x labels so they never collide.
        if (labels.isNotEmpty()) {
            val every = max(1, ceil(labels.size / 6f).toInt())
            labels.forEachIndexed { i, s ->
                if ((labels.lastIndex - i) % every != 0) return@forEachIndexed
                val l = tm.measure(s, axisText)
                val cx = (leftPad + i * slot + slot / 2 - l.size.width / 2).coerceIn(leftPad, size.width - l.size.width)
                drawText(l, topLeft = Offset(cx, size.height - l.size.height))
            }
        }
        if (sel != null) {
            val v = values[sel]
            val label = tipLabels.getOrNull(sel)
            tooltip(tm, listOfNotNull(label, v?.let(tipFmt) ?: "–").joinToString("  ·  "), leftPad + sel * slot + slot / 2)
        }
    }
}

/** A tiny trend line for a tile. */
@Composable
fun Sparkline(values: List<Float?>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pts = values.withIndex().filter { it.value != null }
        if (pts.size < 2) return@Canvas
        val lo = pts.minOf { it.value!! }; val hi = pts.maxOf { it.value!! }.let { if (it - lo < 1e-3f) lo + 1 else it }
        val r = 3.dp.toPx()
        fun p(i: Int, v: Float) = Offset(r + i / (values.size - 1f) * (size.width - 2 * r), r + (1 - (v - lo) / (hi - lo)) * (size.height - 2 * r))
        val path = Path()
        pts.forEachIndexed { k, iv -> val o = p(iv.index, iv.value!!); if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, color.copy(alpha = 0.9f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = pts.last()
        drawCircle(color, r, p(last.index, last.value!!))
    }
}

/** A progress ring; [fraction] past 1 shows a full ring. */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 8.dp) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val d = min(size.width, size.height) - s
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(color.copy(alpha = 0.12f), 0f, 360f, false, tl, Size(d, d), style = Stroke(s))
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0) drawArc(color, -90f, 360f * f, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** A horizontal bar split into parts (e.g. sleep stages), with 2 dp gaps. */
@Composable
fun Segments(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        val gap = 2.dp.toPx()
        val shown = parts.filter { it.first > 0f }
        val avail = size.width - gap * (shown.size - 1)
        var x = 0f
        val r = CornerRadius(size.height / 2)
        shown.forEach { (v, c) ->
            val w = max(size.height / 2, v / total * avail)
            drawRoundRect(c, Offset(x, 0f), Size(min(w, size.width - x), size.height), r)
            x += w + gap
        }
    }
}
