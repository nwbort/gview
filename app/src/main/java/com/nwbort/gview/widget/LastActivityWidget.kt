package com.nwbort.gview.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import com.nwbort.gview.ui.Fmt
import com.nwbort.gview.ui.theme.G

/** The latest activity on the home screen. Tap to open its page. */
class LastActivityWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        provideContent {
            val cache by Graph.store.cache.collectAsState()
            Body(cache)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        Graph.init(context)
        val cache = Graph.store.cache.value
        provideContent { Body(cache) }
    }

    @Composable
    internal fun Body(cache: Cache) {
        val a = cache.sortedActivities.firstOrNull()
        val context = LocalContext.current
        Column(
            GlanceModifier.fillMaxSize().background(ColorProvider(G.card)).cornerRadius(24.dp).padding(16.dp)
                .clickable(actionStartActivity(if (a != null) MainActivity.openActivity(context, a.id) else MainActivity.home(context))),
        ) {
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
