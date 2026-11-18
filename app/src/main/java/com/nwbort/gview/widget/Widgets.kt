package com.nwbort.gview.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll

/** Both home-screen widgets, kept in step with the cache. */
object Widgets {
    suspend fun refresh(context: Context) {
        runCatching { HealthWidget().updateAll(context) }
        runCatching { LastActivityWidget().updateAll(context) }
    }

    /** Re-render the picker previews. The platform rate-limits this, so failures are fine. */
    suspend fun publishPreviews(context: Context) {
        val m = GlanceAppWidgetManager(context)
        runCatching { m.setWidgetPreviews(HealthWidgetReceiver::class) }
        runCatching { m.setWidgetPreviews(LastActivityWidgetReceiver::class) }
    }
}
