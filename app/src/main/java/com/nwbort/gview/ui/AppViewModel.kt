package com.nwbort.gview.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nwbort.gview.Graph
import com.nwbort.gview.data.Account
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Track
import com.nwbort.gview.sync.Sync
import com.nwbort.gview.widget.HealthWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {
    init {
        Graph.init(app)
    }

    val cache = Graph.store.cache
    val account = Graph.store.account
    val syncing = Graph.sync.running

    private val _message = MutableStateFlow<String?>(null)
    /** A one-off note for a snackbar, e.g. why a sync failed. */
    val message = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    fun onResume() {
        viewModelScope.launch { Graph.sync.syncIfStale()?.let(::report) }
    }

    fun sync() {
        viewModelScope.launch { report(Graph.sync.sync()) }
    }

    private fun report(r: Sync.Result) {
        _message.value = when (r) {
            is Sync.Result.Failed -> r.message
            is Sync.Result.Done -> if (r.failed.isNotEmpty()) "Synced, but Garmin didn't send: ${r.failed.joinToString()}" else null
            Sync.Result.NotConnected -> null
        }
    }

    /** How many years to keep; a longer history is fetched straight away. */
    fun setHistory(years: Int) {
        viewModelScope.launch {
            Graph.store.updateCache { it.copy(historyYears = years) }
            report(Graph.sync.sync())
        }
    }

    fun connect(key: String, expires: LocalDate) {
        viewModelScope.launch {
            Graph.store.updateAccount { Account(apiKey = key.trim(), keyExpires = expires.toString()) }
            Sync.schedule(getApplication(), connected = true)
            report(Graph.sync.sync())
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            Graph.store.clear()
            Sync.schedule(getApplication(), connected = false)
            HealthWidget.refresh(getApplication())
        }
    }

    /** An activity's route and streams, fetched from Garmin the first time. */
    suspend fun track(a: Activity): Track? = runCatching { Graph.sync.track(a) }.getOrNull()
}
