package com.nwbort.gview.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nwbort.gview.Graph
import com.nwbort.gview.ai.Brief
import com.nwbort.gview.ai.LocalAi
import com.nwbort.gview.data.Account
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Track
import com.nwbort.gview.sync.Sync
import com.nwbort.gview.widget.Widgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
            Widgets.refresh(getApplication())
        }
    }

    /** Garmin's own JSON for an activity, or the error that stopped it. */
    suspend fun raw(a: Activity): Pair<String, String> = try { Graph.sync.raw(a) } catch (e: Exception) {
        val msg = "Couldn't fetch it: ${e.message ?: e.javaClass.simpleName}"
        msg to ""
    }

    /** An activity's route and streams, fetched from Garmin the first time. */
    suspend fun track(a: Activity): Track? = runCatching { Graph.sync.track(a) }.getOrNull()

    private val _aiStatus = MutableStateFlow<LocalAi.Status?>(null)
    /** Whether this phone can write summaries on device; null until checked. */
    val aiStatus: StateFlow<LocalAi.Status?> = _aiStatus.asStateFlow()

    init {
        viewModelScope.launch { _aiStatus.value = Graph.ai.status() }
    }

    // Saved summaries come back as Done; ones being written live only here.
    private val _summaries = MutableStateFlow<Map<Long, Summary>>(Graph.store.summaries.value.mapValues { Summary.Done(it.value) })
    /** Summaries by activity id: saved ones, and any being written now. */
    val summaries = _summaries.asStateFlow()

    /**
     * Writes an on-device summary of [a] and saves it. It carries on if the page is left,
     * but only while the app is open: AICore refuses to run for apps in the background.
     */
    fun summarise(a: Activity, track: Track?) {
        if (_summaries.value[a.id].let { it is Summary.Downloading || it is Summary.Writing }) return
        val set = { s: Summary -> _summaries.update { it + (a.id to s) } }
        set(Summary.Writing(""))
        viewModelScope.launch {
            var text = ""
            try {
                Graph.ai.write(Brief.prompt(a, track, cache.value)).collect { step ->
                    when (step) {
                        LocalAi.Step.Downloading -> set(Summary.Downloading)
                        is LocalAi.Step.Text -> { text = step.text; set(Summary.Writing(text.trim())) }
                    }
                }
                if (text.isBlank()) set(Summary.Failed("The model didn't write anything. Try again."))
                else {
                    set(Summary.Done(text.trim()))
                    Graph.store.saveSummary(a.id, text.trim())
                }
                _aiStatus.value = LocalAi.Status.AVAILABLE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                set(Summary.Failed("Couldn't write a summary: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }
}

sealed interface Summary {
    data object Downloading : Summary
    data class Writing(val text: String) : Summary
    data class Done(val text: String) : Summary
    data class Failed(val message: String) : Summary
}
