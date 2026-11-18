package com.nwbort.gview.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
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
import com.nwbort.gview.data.Cache
import com.nwbort.gview.ui.Fmt
import com.nwbort.gview.ui.theme.G
import java.time.LocalDate

/**
 * This morning's numbers on the home screen: Body Battery, sleep, HRV and
 * steps. Light, like the app. Tap to open it.
 */
class HealthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        provideContent {
            val cache by Graph.store.cache.collectAsState()
            Body(cache, LocalDate.now())
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        Graph.init(context)
        val cache = Graph.store.cache.value
        provideContent { Body(cache, LocalDate.now()) }
    }

    @Composable
    internal fun Body(cache: Cache, today: LocalDate) {
        val day = cache.day(today)
        val sleep = day.sleep
        val s = day.summary
        Column(
            GlanceModifier.fillMaxSize().background(ColorProvider(G.card)).cornerRadius(24.dp).padding(16.dp)
                .clickable(actionStartActivity(MainActivity.home(LocalContext.current))),
        ) {
            Text(
                "gview · ${Fmt.weekday(today)} ${Fmt.shortDate(today)}",
                style = TextStyle(color = ColorProvider(G.ink3), fontSize = 12.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                WidgetCell("Body Battery", (s?.bbLatest ?: s?.bbMax)?.toString() ?: "–", "", G.battery, GlanceModifier.defaultWeight())
                WidgetCell("Sleep", sleep?.let { Fmt.hm(it.seconds.toDouble()) } ?: "–", sleep?.score?.let { "score $it" } ?: "", G.sleep, GlanceModifier.defaultWeight())
            }
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                WidgetCell("HRV", day.hrv?.lastNight?.toString() ?: "–", if (day.hrv?.lastNight != null) "ms" else "", G.hrv, GlanceModifier.defaultWeight())
                WidgetCell("Steps", Fmt.int(s?.steps), "", G.steps, GlanceModifier.defaultWeight())
            }
        }
    }
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}

/** A coloured dot and label over a big value: one number on a widget. */
@Composable
internal fun WidgetCell(label: String, value: String, unit: String, color: Color, modifier: GlanceModifier, valueSize: TextUnit = 24.sp) {
    Column(modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(7.dp).cornerRadius(4.dp).background(ColorProvider(color))) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(label.uppercase(), style = TextStyle(color = ColorProvider(G.ink2), fontSize = 10.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = TextStyle(color = ColorProvider(G.ink), fontSize = valueSize), maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(GlanceModifier.width(4.dp))
                Text(unit, style = TextStyle(color = ColorProvider(G.ink3), fontSize = 11.sp), maxLines = 1)
            }
        }
    }
}
