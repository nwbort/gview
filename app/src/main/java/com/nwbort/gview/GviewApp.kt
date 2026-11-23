package com.nwbort.gview

import android.app.Application
import android.content.Context
import com.nwbort.gview.ai.GeminiNano
import com.nwbort.gview.ai.LocalAi
import com.nwbort.gview.data.GarminApi
import com.nwbort.gview.data.Store
import com.nwbort.gview.sync.Sync
import com.nwbort.gview.ui.TileCache
import com.nwbort.gview.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GviewApp : Application() {
    // Before onCreate, so it also catches crashes in library start-up code.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReport.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // A failure here would kill every launch before anything could show
        // it, so record it instead and let MainActivity put it on screen.
        try {
            Graph.init(this)
            Graph.scope.launch {
                Widgets.publishPreviews(this@GviewApp)
                if (Graph.store.account.value.connected) Sync.schedule(this@GviewApp, connected = true)
            }
        } catch (e: Throwable) {
            CrashReport.save(this, e)
        }
    }
}

/** The app's few singletons. The widget and the background sync reach them here too. */
object Graph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: Store
        private set
    lateinit var sync: Sync
        private set
    lateinit var tiles: TileCache
        private set

    /** Tests swap in a fake Garmin before the first [init]. */
    var api: GarminApi = GarminApi()

    /** The on-device model for activity summaries; tests swap in a fake. */
    var ai: LocalAi = GeminiNano()

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = Store(context.applicationContext)
        sync = Sync(context.applicationContext, store, api)
        tiles = TileCache(context.applicationContext)
    }
}
