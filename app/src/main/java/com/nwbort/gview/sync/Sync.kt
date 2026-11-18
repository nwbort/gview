package com.nwbort.gview.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nwbort.gview.Graph
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Fit
import com.nwbort.gview.data.GarminApi
import com.nwbort.gview.data.GarminAuthException
import com.nwbort.gview.data.Parse
import com.nwbort.gview.data.Store
import com.nwbort.gview.data.Track
import com.nwbort.gview.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Pulls everything from Garmin into the [Store]: a year on the first sync, then
 * the last week or so each time. Each kind of data is fetched on its own, so
 * one Garmin service being down doesn't stop the rest.
 */
class Sync(private val context: Context, private val store: Store, private val api: GarminApi = GarminApi()) {
    private val mutex = Mutex()
    private val trackMutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    sealed interface Result {
        data class Done(val newActivities: Int, val failed: List<String>) : Result
        data class Failed(val message: String, val keyRejected: Boolean = false) : Result
        data object NotConnected : Result
    }

    /** Syncs unless one ran in the last [minAgeMs]; null when skipped. */
    suspend fun syncIfStale(minAgeMs: Long = 15 * 60_000L): Result? {
        if (!store.account.value.connected) return null
        val last = store.cache.value.lastSync
        if (last != null && System.currentTimeMillis() - last < minAgeMs && store.cache.value.errors.isEmpty()) return null
        return sync()
    }

    suspend fun sync(): Result {
        val r = mutex.withLock {
            _running.value = true
            try {
                withContext(Dispatchers.IO) { syncLocked() }
            } finally {
                _running.value = false
            }
        }
        // Routes come after the spinner has gone: they're a nicety, not the sync.
        if (r is Result.Done) runCatching { prefetchRoutes() }
        return r
    }

    private suspend fun syncLocked(): Result {
        val acct = store.account.value
        val key = acct.apiKey ?: return Result.NotConnected
        val cache = store.cache.value
        val today = LocalDate.now()
        // How far back to keep, as chosen in Settings.
        val want = today.minusYears(cache.historyYears.toLong()).plusDays(1)
        // Each kind of data keeps its own high-water mark, so one that failed
        // (or is new) catches up on everything next time.
        fun since(section: String): LocalDate =
            // Activities parsed before newer fields were read are fetched again, all of them, once.
            if (section == ACTIVITIES && cache.activityFields < ACTIVITY_FIELDS) want
            else cache.covered[section]?.let { minOf(LocalDate.parse(it).minusDays(3), today.minusDays(7)) }
            ?: want
        // And its low-water mark: when the history setting grows, fetch just the older stretch.
        fun earliest(section: String): LocalDate? = cache.earliest[section]?.let(LocalDate::parse)
            ?: if (cache.covered[section] != null) today.minusDays(HISTORY_DAYS) else null
        fun backfill(section: String): Pair<LocalDate, LocalDate>? =
            earliest(section)?.takeIf { want < it }?.let { want to it.minusDays(1) }
        fun <T> both(section: String, token: String, call: (String, LocalDate, LocalDate) -> List<T>): List<T> =
            call(token, since(section), today) + (backfill(section)?.let { (a, b) -> call(token, a, b) } ?: emptyList())
        val from = since(ACTIVITIES)

        // Activities first: it also proves the key works.
        val activities = try {
            withToken(key) { both(ACTIVITIES, it, api::activities) }
        } catch (e: GarminAuthException) {
            store.updateAccount { it.copy(token = null, tokenExpires = null, keyRejected = true) }
            val msg = "Garmin turned the key down. It may have expired or been deleted: make a new one."
            store.updateCache { it.copy(errors = mapOf("Garmin" to msg)) }
            return Result.Failed(msg, keyRejected = true)
        } catch (e: IOException) {
            val msg = "Couldn't reach Garmin: ${e.message ?: e.javaClass.simpleName}"
            store.updateCache { it.copy(errors = mapOf("Garmin" to msg)) }
            return Result.Failed(msg)
        }
        if (acct.keyRejected) store.updateAccount { it.copy(keyRejected = false) }

        val errors = linkedMapOf<String, String>()
        suspend fun <T> section(name: String, call: (String) -> T): T? = try {
            withToken(key, call)
        } catch (e: Exception) {
            errors[name] = e.message ?: e.javaClass.simpleName
            null
        }
        val summaries = section(SUMMARY) { both(SUMMARY, it, api::summaries) }
        val sleep = section(SLEEP) { both(SLEEP, it, api::sleep) }
        val hrv = section(HRV) { both(HRV, it, api::hrv) }
        // The through-the-day curve only matters for the last few days.
        val bb = section("Body Battery") { api.bodyBattery(it, today.minusDays(BB_DAYS), today) }
        val weight = section(WEIGHT) { both(WEIGHT, it, api::weight) }
        val fitnessAge = section("Fitness age") { api.fitnessAge(it, today) }
        val zones = section("Heart-rate zones") { api.hrZones(it) }
        // Heart rate through the day: today and yesterday always (still filling in), and any
        // other day of the last week that's missing. One call per day.
        val hrDays = (0L until HR_DAYS).map { today.minusDays(it) }
            .filter { it >= today.minusDays(1) || cache.heartRate[it.toString()] == null }
        val hr = section("Heart rate") { t -> hrDays.mapNotNull { api.heartRate(t, it) } }
        val synced = mapOf(ACTIVITIES to activities, SUMMARY to summaries, SLEEP to sleep, HRV to hrv, WEIGHT to weight)
            .filterValues { it != null }.keys
        val done = synced.associateWith { today.toString() }
        val reached = synced.associateWith { sec -> listOfNotNull(earliest(sec), since(sec), backfill(sec)?.first).min().toString() }

        val known = cache.activities.mapTo(HashSet()) { it.id }
        store.updateCache { c ->
            // Activities in the synced window that Garmin no longer lists were deleted there.
            val window = from.toString()
            val kept = c.activities.filter { it.start < window }
            // Anything older than the history setting is let go (it was made shorter).
            val keep = want.toString()
            fun <V> Map<String, V>.recent() = filterKeys { it >= keep }
            c.copy(
                activities = (kept + activities).distinctBy { it.id }.filter { it.start >= keep },
                summaries = (c.summaries + summaries.orEmpty().associateBy { it.date }).recent(),
                sleep = (c.sleep + sleep.orEmpty().associateBy { it.date }).recent(),
                hrv = (c.hrv + hrv.orEmpty().associateBy { it.date }).recent(),
                bodyBattery = (c.bodyBattery + bb.orEmpty().associateBy { it.date })
                    .filterKeys { it >= today.minusDays(BB_KEEP_DAYS).toString() },
                weight = (c.weight + weight.orEmpty().associateBy { it.date }).recent(),
                heartRate = (c.heartRate + hr.orEmpty().associateBy { it.date })
                    .filterKeys { it >= today.minusDays(BB_KEEP_DAYS).toString() },
                hrZones = zones ?: c.hrZones,
                fitnessAge = fitnessAge ?: c.fitnessAge,
                lastSync = System.currentTimeMillis(),
                syncedTo = today.toString(),
                covered = c.covered + done,
                activityFields = if (ACTIVITIES in synced) ACTIVITY_FIELDS else c.activityFields,
                earliest = (c.earliest + reached).mapValues { maxOf(it.value, keep) },
                errors = errors,
            )
        }
        Widgets.refresh(context)
        return Result.Done(activities.count { it.id !in known }, errors.keys.toList())
    }

    /** Fetches recent routes ahead of time, so the activity list has its shapes and works offline. */
    private suspend fun prefetchRoutes() {
        val since = LocalDate.now().minusDays(PREFETCH_DAYS).toString()
        store.cache.value.sortedActivities
            .filter { it.hasRoute && it.start >= since && !store.hasTrack(it.id) }
            .take(PREFETCH_MAX)
            .forEach { a -> runCatching { track(a) } }
        // Then work through older runs' files for efficiency and drift, a batch per sync.
        // When the analysis itself has changed, every run is gone over again.
        if (store.cache.value.analysedWith != Fit.VERSION) store.updateCache { it.copy(analysed = emptySet(), analysedWith = Fit.VERSION) }
        val c = store.cache.value
        c.sortedActivities
            .filter { it.sport == com.nwbort.gview.data.Sport.RUN && it.id !in c.analysed }
            .take(ANALYSE_MAX)
            .forEach { a ->
                // A run with no file to fetch is still done with, so it doesn't hold up the rest.
                if (runCatching { track(a, refresh = true) }.isFailure) store.updateCache { it.copy(analysed = it.analysed + a.id) }
            }
    }

    /** The activity's route and streams: from the phone if fetched before, else from Garmin. */
    suspend fun track(activity: Activity, refresh: Boolean = false): Track? = trackMutex.withLock {
        store.track(activity.id)?.takeIf { it.v >= Fit.VERSION }?.let { t ->
            // Decoded before this run was analysed: record its stats now, no download needed.
            if (refresh || activity.id !in store.cache.value.analysed) store.updateCache { c ->
                c.copy(analysed = c.analysed + activity.id, runStats = t.stats?.let { c.runStats + (activity.id to it) } ?: c.runStats)
            }
            return t
        }
        val key = store.account.value.apiKey ?: return null
        withContext(Dispatchers.IO) {
            val bytes = withToken(key) { api.activityFile(it, activity.id) }
            val fit = Fit.unzip(bytes) ?: return@withContext Track(v = Fit.VERSION)
            Fit.track(Fit.records(fit), activity.sport, activity.date.toString())
        }.also { t ->
            store.saveTrack(activity.id, t)
            store.updateCache { c ->
                c.copy(
                    analysed = c.analysed + activity.id,
                    runStats = t.stats?.let { c.runStats + (activity.id to it) } ?: c.runStats,
                )
            }
        }
    }

    /** Garmin's own JSON for an activity: (list entry, detail), pretty-printed. */
    suspend fun raw(activity: Activity): Pair<String, String> {
        val key = store.account.value.apiKey ?: throw IOException("Not connected")
        val (entry, detail) = withContext(Dispatchers.IO) { withToken(key) { api.rawActivity(it, activity.id, activity.date) } }
        return pretty(entry) to pretty(detail)
    }

    private fun pretty(json: String): String = runCatching {
        val pj = kotlinx.serialization.json.Json { prettyPrint = true }
        pj.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), pj.parseToJsonElement(json))
    }.getOrDefault(json)

    /** Runs [call] with a cached token, getting a fresh one when it's near expiry or turned down. */
    private suspend fun <T> withToken(key: String, call: (String) -> T): T = try {
        call(token(key, fresh = false))
    } catch (e: GarminAuthException) {
        // Tokens can stop working early; one retry with a new one before blaming the key.
        call(token(key, fresh = true))
    }

    private suspend fun token(key: String, fresh: Boolean): String {
        val now = System.currentTimeMillis() / 1000
        val acct = store.account.value
        if (!fresh && acct.token != null && (acct.tokenExpires ?: 0) - now > 3600) return acct.token
        val t = api.token(key)
        store.updateAccount { it.copy(token = t, tokenExpires = Parse.tokenExpiry(t) ?: (now + 2 * 3600)) }
        return t
    }

    companion object {
        private const val HISTORY_DAYS = 365L
        private const val ACTIVITIES = "Activities"
        /** Bump when [com.nwbort.gview.data.Parse] reads new activity fields. */
        private const val ACTIVITY_FIELDS = 2
        private const val SUMMARY = "Daily summary"
        private const val SLEEP = "Sleep"
        private const val HRV = "HRV"
        private const val WEIGHT = "Weight"
        private const val BB_DAYS = 6L
        private const val HR_DAYS = 7L
        private const val BB_KEEP_DAYS = 14L
        private const val PREFETCH_DAYS = 45L
        private const val PREFETCH_MAX = 25
        private const val ANALYSE_MAX = 40
        private const val WORK = "garmin-sync"

        /** Background sync every few hours while connected; cancelled when not. */
        fun schedule(context: Context, connected: Boolean) = runCatching {
            val wm = WorkManager.getInstance(context)
            if (!connected) {
                wm.cancelUniqueWork(WORK)
                return@runCatching
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val r = Graph.sync.sync()
        // A network hiccup is worth retrying; a turned-down key isn't until it's replaced.
        return if (r is Sync.Result.Failed && !r.keyRejected) Result.retry() else Result.success()
    }
}
