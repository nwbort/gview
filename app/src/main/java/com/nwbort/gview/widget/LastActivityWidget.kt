package com.nwbort.gview.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nwbort.gview.Graph
import com.nwbort.gview.MainActivity
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.Sport
import com.nwbort.gview.data.Track
import com.nwbort.gview.ui.Fmt
import com.nwbort.gview.ui.Mercator
import com.nwbort.gview.ui.theme.G

/**
 * The latest activity on the home screen, with its route's outline faint behind
 * the figures. Tap to open its page.
 */
class LastActivityWidget : GlanceAppWidget() {
    /** Drawn at the widget's real size, so the route fills it whatever its shape. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        provideContent {
            val cache by Graph.store.cache.collectAsState()
            val a = cache.sortedActivities.firstOrNull()
            // The route is saved before the activity is marked analysed, so that's the cue to look again.
            val track by produceState<Track?>(null, a?.id, a?.id in cache.analysed) {
                value = a?.takeIf { it.hasRoute }?.let { Graph.store.track(it.id) }
            }
            Body(cache, track)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        Graph.init(context)
        val cache = Graph.store.cache.value
        val track = cache.sortedActivities.firstOrNull()?.takeIf { it.hasRoute }?.let { Graph.store.track(it.id) }
        provideContent { Body(cache, track) }
    }

    @Composable
    internal fun Body(cache: Cache, track: Track?) {
        val a = cache.sortedActivities.firstOrNull()
        val context = LocalContext.current
        val size = LocalSize.current
        val route = remember(track, size, a?.sport) {
            if (a != null && track != null && track.hasRoute) routeBitmap(track, size, G.sport(a.sport).toArgb(), context.resources.displayMetrics.density) else null
        }
        Box(
            GlanceModifier.fillMaxSize().background(ColorProvider(G.card)).cornerRadius(24.dp)
                .clickable(actionStartActivity(if (a != null) MainActivity.openActivity(context, a.id) else MainActivity.home(context))),
        ) {
            if (route != null) {
                Image(ImageProvider(route), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Content(a)
        }
    }

    @Composable
    private fun Content(a: Activity?) {
        Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
            if (a == null) {
                Text("gview · last activity", style = TextStyle(color = ColorProvider(G.ink3), fontSize = 12.sp), maxLines = 1)
                Spacer(GlanceModifier.height(8.dp))
                Text("No activities yet", style = TextStyle(color = ColorProvider(G.ink2), fontSize = 16.sp), maxLines = 1)
                return@Column
            }
            val color = G.sport(a.sport)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(7.dp).cornerRadius(4.dp).background(ColorProvider(color))) {}
                Spacer(GlanceModifier.width(6.dp))
                Text(Fmt.when_(a), style = TextStyle(color = ColorProvider(G.ink3), fontSize = 12.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(2.dp))
            Text(
                a.name.ifBlank { a.sport.label },
                style = TextStyle(color = ColorProvider(G.ink), fontSize = 16.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth()) {
                stats(a).forEach { (label, value, unit) ->
                    WidgetCell(label, value, unit, color, GlanceModifier.defaultWeight(), valueSize = 20.sp)
                }
            }
        }
    }

    /** The same three figures as the activity list: distance, time and pace, or HR when there's no distance. */
    private fun stats(a: Activity): List<Triple<String, String, String>> = buildList {
        if (a.km != null) add(Triple("Distance", Fmt.km(a.distanceM), "km"))
        add(Triple("Time", Fmt.clock(a.timeS), ""))
        if (a.km != null && a.sport != Sport.STRENGTH) {
            val (v, u) = Fmt.speed(a)
            add(Triple(if (a.sport.usesPace || a.sport == Sport.SWIM) "Pace" else "Speed", v, u))
        } else {
            a.avgHr?.let { add(Triple("Avg HR", it.toInt().toString(), "bpm")) }
        }
    }
}

class LastActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LastActivityWidget()
}

/**
 * The route's outline in the sport's colour, faded so the figures read over it,
 * fitted to a [size] widget. Kept to about 2x so the bitmap stays well within
 * what the launcher accepts.
 */
internal fun routeBitmap(track: Track, size: DpSize, color: Int, density: Float): Bitmap? {
    val scale = density.coerceAtMost(2f)
    val w = (size.width.value * scale).toInt()
    val h = (size.height.value * scale).toInt()
    if (w <= 0 || h <= 0) return null
    val xs = track.lon.map { Mercator.x(it) }
    val ys = track.lat.map { Mercator.y(it) }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val pad = 14 * scale
    val s = minOf((w - 2 * pad) / maxOf(maxX - minX, 1e-9), (h - 2 * pad) / maxOf(maxY - minY, 1e-9))
    val ox = (w - (maxX - minX) * s) / 2
    val oy = (h - (maxY - minY) * s) / 2
    val path = Path()
    xs.indices.forEach { i ->
        val px = (ox + (xs[i] - minX) * s).toFloat(); val py = (oy + (ys[i] - minY) * s).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        alpha = 56
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawPath(path, paint) }
}
