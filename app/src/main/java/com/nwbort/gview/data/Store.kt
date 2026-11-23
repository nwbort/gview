package com.nwbort.gview.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** The Personal API key and the token it was last swapped for. Never leaves the phone. */
@Serializable
data class Account(
    val apiKey: String? = null,
    /** When the key runs out, as chosen when it was made. */
    val keyExpires: String? = null,
    val token: String? = null,
    val tokenExpires: Long? = null,
    val keyRejected: Boolean = false,
) {
    val connected: Boolean get() = apiKey != null

    fun daysLeft(today: LocalDate): Long? = keyExpires?.let { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(it)) }
}

/**
 * Everything the app keeps: the synced data (one JSON file, loaded once and held
 * in memory), each activity's decoded route, and the account. The account lives
 * in no-backup storage so the key isn't copied into phone backups.
 */
class Store(context: Context) {
    private val dir = context.filesDir
    private val cacheFile = File(dir, "cache.json")
    private val accountFile = File(context.noBackupFilesDir, "account.json")
    private val tracksDir = File(dir, "tracks").apply { mkdirs() }
    private val summariesFile = File(dir, "summaries.json")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mutex = Mutex()

    private val _cache = MutableStateFlow(read(cacheFile) ?: Cache())
    val cache: StateFlow<Cache> = _cache.asStateFlow()

    private val _account = MutableStateFlow(read(accountFile) ?: Account())
    val account: StateFlow<Account> = _account.asStateFlow()

    private val _summaries = MutableStateFlow(read<Map<Long, String>>(summariesFile) ?: emptyMap())
    /** On-device AI summaries, by activity id: each is written once and kept. */
    val summaries: StateFlow<Map<Long, String>> = _summaries.asStateFlow()

    private inline fun <reified T> read(f: File): T? = runCatching { json.decodeFromString<T>(f.readText()) }.getOrNull()

    private inline fun <reified T> write(f: File, value: T) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(value))
        tmp.renameTo(f)
    }

    suspend fun updateCache(change: (Cache) -> Cache) = mutex.withLock {
        val next = change(_cache.value)
        _cache.value = next
        withContext(Dispatchers.IO) { write(cacheFile, next) }
    }

    suspend fun updateAccount(change: (Account) -> Account) = mutex.withLock {
        val next = change(_account.value)
        _account.value = next
        withContext(Dispatchers.IO) { write(accountFile, next) }
    }

    suspend fun track(id: Long): Track? = withContext(Dispatchers.IO) { read<Track>(File(tracksDir, "$id.json")) }

    fun hasTrack(id: Long): Boolean = File(tracksDir, "$id.json").exists()

    suspend fun saveTrack(id: Long, track: Track) = withContext(Dispatchers.IO) { write(File(tracksDir, "$id.json"), track) }

    suspend fun saveSummary(id: Long, text: String) = mutex.withLock {
        val next = _summaries.value + (id to text)
        _summaries.value = next
        withContext(Dispatchers.IO) { write(summariesFile, next) }
    }

    /** Forget everything: the key and all synced data. */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            cacheFile.delete()
            accountFile.delete()
            summariesFile.delete()
            tracksDir.listFiles()?.forEach { it.delete() }
        }
        _cache.value = Cache()
        _account.value = Account()
        _summaries.value = emptyMap()
    }
}
