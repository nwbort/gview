package com.nwbort.gview.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate

/** Garmin refused the key or token: it has expired, been revoked, or was mistyped. */
class GarminAuthException(message: String) : IOException(message)

/**
 * The calls the app makes to Garmin Connect's Personal API: the same key the
 * Workouts app uses, read-only. A key is swapped for a bearer token (valid up
 * to a day) and the token is sent with every data request.
 */
open class GarminApi(private val baseUrl: String = BASE_URL) {

    /** Exchanges a Personal API key for an access token. */
    open fun token(apiKey: String): String {
        val body = String(request("POST", TOKEN_PATH, "ApiKey ${apiKey.trim()}", "*/*"))
        return body.trim().takeIf { it.isNotEmpty() } ?: throw IOException("Garmin sent back an empty token")
    }

    /** Every activity started between [from] and [to] inclusive. */
    open fun activities(token: String, from: LocalDate, to: LocalDate): List<Activity> {
        val all = mutableListOf<Activity>()
        var start = 0
        while (true) {
            val page = Parse.activities(get(token, "/activitylist-service/activities/search/activities",
                "start" to start, "limit" to PAGE, "startDate" to from, "endDate" to to))
            all += page
            if (page.size < PAGE || start >= MAX_ACTIVITIES) break
            start += PAGE
        }
        return all.distinctBy { it.id }
    }

    /**
     * Daily wellness summaries. Garmin allows up to a year per call, but a
     * year in one go was turned down in practice; a quarter at a time works.
     */
    open fun summaries(token: String, from: LocalDate, to: LocalDate): List<DaySummary> =
        chunked(from, to, 90) { a, b -> Parse.summaries(get(token, "/usersummary-service/v2/dailysummaries", "startDate" to a, "endDate" to b)) }

    /** Nightly sleep, up to 31 days per call. */
    open fun sleep(token: String, from: LocalDate, to: LocalDate): List<Sleep> =
        chunked(from, to, 31) { a, b -> Parse.sleeps(get(token, "/sleep-service/sleep/dailySleepSummariesByDate", "startDate" to a, "endDate" to b)) }

    /** Overnight HRV. Garmin doesn't state a range limit; four weeks a call keeps it modest. */
    open fun hrv(token: String, from: LocalDate, to: LocalDate): List<Hrv> =
        chunked(from, to, 28) { a, b -> Parse.hrv(get(token, "/hrv-service/hrv/daily/$a/$b")) }

    /** Body Battery through each day, up to 31 days per call. */
    open fun bodyBattery(token: String, from: LocalDate, to: LocalDate): List<BbCurve> =
        chunked(from, to, 31) { a, b -> Parse.bodyBattery(get(token, "/wellness-service/wellness/bodyBattery/reports/daily", "startDate" to a, "endDate" to b)) }

    open fun weight(token: String, from: LocalDate, to: LocalDate): List<Weight> =
        Parse.weights(get(token, "/weight-service/weight/range/$from/$to", "includeAll" to false))

    open fun heartRate(token: String, date: LocalDate): HrCurve? =
        Parse.heartRate(get(token, "/wellness-service/wellness/dailyHeartRate", "date" to date), date)

    open fun hrZones(token: String): HrZones? =
        Parse.hrZones(get(token, "/biometric-service/heartRateZones/v2", "generateDefaults" to true))

    open fun fitnessAge(token: String, date: LocalDate): Double? =
        Parse.fitnessAge(get(token, "/fitnessage-service/fitnessage/v2/$date"))

    /**
     * Exactly what Garmin sends for one activity, for checking what's there:
     * its entry in the activity list, and its detail record.
     */
    open fun rawActivity(token: String, id: Long, date: LocalDate): Pair<String, String> {
        val list = get(token, "/activitylist-service/activities/search/activities", "start" to 0, "limit" to 50, "startDate" to date, "endDate" to date)
        val entry = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(list) as kotlinx.serialization.json.JsonArray)
                .firstOrNull { (it as? kotlinx.serialization.json.JsonObject)?.get("activityId")?.toString() == id.toString() }?.toString()
        }.getOrNull() ?: list
        return entry to get(token, "/activity-service/activity/$id")
    }

    /** The activity's original file: a ZIP holding a FIT file. */
    open fun activityFile(token: String, id: Long): ByteArray =
        request("GET", "/download-service/files/activity/$id", "Bearer $token", "application/zip, application/x-zip-compressed, application/octet-stream")

    private fun <T> chunked(from: LocalDate, to: LocalDate, days: Long, call: (LocalDate, LocalDate) -> List<T>): List<T> {
        val out = mutableListOf<T>()
        var a = from
        while (!a.isAfter(to)) {
            val b = minOf(a.plusDays(days - 1), to)
            out += call(a, b)
            a = b.plusDays(1)
        }
        return out
    }

    private fun get(token: String, path: String, vararg query: Pair<String, Any>): String {
        val q = if (query.isEmpty()) "" else query.joinToString("&", "?") { (k, v) -> "$k=${URLEncoder.encode(v.toString(), Charsets.UTF_8)}" }
        return String(request("GET", path + q, "Bearer $token", "application/json"))
    }

    protected open fun request(method: String, path: String, auth: String, accept: String): ByteArray {
        val conn = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", auth)
            conn.setRequestProperty("Accept", accept)
            if (method == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) throw GarminAuthException("Garmin turned the key down ($code)")
            if (code == 204) return ByteArray(0)
            if (code !in 200..299) throw IOException("Garmin replied $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://personal.connectapi.garmin.com"
        const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        /** Where the user makes a key: Garmin Connect, Settings, Personal API token. */
        const val KEY_PAGE = "https://connect.garmin.com/app/settings/personalApiToken"
        fun activityUrl(id: Long) = "https://connect.garmin.com/modern/activity/$id"
        /** The expiry periods Garmin offers when making a key. */
        val KEY_LIFETIMES = listOf(30, 90, 180, 365)
        private const val PAGE = 100
        private const val MAX_ACTIVITIES = 2000
    }
}
