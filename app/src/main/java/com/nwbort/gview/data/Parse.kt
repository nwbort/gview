package com.nwbort.gview.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * Turns Garmin Personal API responses into the app's models. Field names are
 * from Garmin's published OpenAPI spec. Anything missing or oddly shaped is
 * skipped rather than failing a sync.
 */
object Parse {
    private val json = Json { ignoreUnknownKeys = true }

    private fun root(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    private fun list(body: String, vararg keys: String): List<JsonObject> {
        val r = root(body) ?: return emptyList()
        val arr = when (r) {
            is JsonArray -> r
            is JsonObject -> keys.firstNotNullOfOrNull { r[it] as? JsonArray } ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { it as? JsonObject }
    }

    // --- Activities ---

    fun activities(body: String): List<Activity> = list(body, "activityList", "activities").mapNotNull { it.activity() }

    private fun JsonObject.activity(): Activity? {
        val id = num("activityId")?.toLong() ?: return null
        val start = str("startTimeLocal")?.takeIf { it.length >= 10 } ?: return null
        val type = obj("activityType")?.str("typeKey") ?: str("activityType") ?: "other"
        return Activity(
            id = id,
            name = str("activityName").orEmpty(),
            type = type,
            start = start,
            distanceM = num("distance"),
            durationS = num("duration"),
            movingS = num("movingDuration"),
            elevGainM = num("elevationGain"),
            elevLossM = num("elevationLoss"),
            avgSpeed = num("averageSpeed"),
            maxSpeed = num("maxSpeed"),
            avgHr = num("averageHR"),
            maxHr = num("maxHR"),
            calories = num("calories"),
            cadence = num("averageRunningCadenceInStepsPerMinute") ?: num("averageBikingCadenceInRevPerMinute"),
            avgPower = num("avgPower"),
            aerobicTe = num("aerobicTrainingEffect"),
            anaerobicTe = num("anaerobicTrainingEffect"),
            teLabel = str("trainingEffectLabel"),
            vo2Max = num("vO2MaxValue"),
            trainingLoad = num("activityTrainingLoad"),
            steps = num("steps")?.toLong(),
            location = str("locationName"),
            hasRoute = bool("hasPolyline") ?: (num("startLatitude") != null),
            pr = bool("isPR") ?: false,
            hrZones = (1..5).map { num("hrTimeInZone_$it") }.let { z -> if (z.all { it == null }) emptyList() else z.map { it ?: 0.0 } },
            fastest = keys.filter { it.startsWith("fastestSplit_") }
                .mapNotNull { k -> k.removePrefix("fastestSplit_").toIntOrNull()?.let { m -> num(k)?.takeIf { it > 0 }?.let { m to it } } }
                .toMap(),
            tempMin = num("minTemperature"),
            tempMax = num("maxTemperature"),
        )
    }

    // --- Daily summaries (v2) ---

    fun summaries(body: String): List<DaySummary> = list(body, "data").mapNotNull { d ->
        val date = d.str("calendarDate") ?: return@mapNotNull null
        val move = d.obj("movement")
        val steps = move?.obj("steps")
        val hr = d.obj("heartRate")
        val stress = d.obj("stress")
        val bb = d.obj("bodyBattery")
        val cal = d.obj("calories")
        val im = d.obj("intensityMinutes")
        DaySummary(
            date = date,
            steps = steps?.int("value"),
            stepGoal = steps?.int("goal"),
            distanceM = steps?.num("distanceInMeters"),
            floors = move?.obj("floorsAscended")?.num("value"),
            restingHr = hr?.int("restingValue"),
            minHr = hr?.int("minValue"),
            maxHr = hr?.int("maxValue"),
            stressAvg = stress?.int("avgLevel")?.takeIf { it >= 0 },
            stressMax = stress?.int("maxLevel")?.takeIf { it >= 0 },
            stressQualifier = stress?.str("qualifier"),
            restP = stress?.num("restProportion"),
            lowP = stress?.num("lowStressProportion"),
            mediumP = stress?.num("mediumStressProportion"),
            highP = stress?.num("highStressProportion"),
            bbMax = bb?.int("maxValue"),
            bbMin = bb?.int("minValue"),
            bbCharged = bb?.int("chargedValue"),
            bbDrained = bb?.int("drainedValue"),
            bbLatest = bb?.int("latestValue"),
            activeKcal = cal?.int("burnedActive"),
            totalKcal = cal?.int("burnedTotal"),
            moderateMin = im?.int("moderate"),
            vigorousMin = im?.int("vigorous"),
            intensityGoal = im?.int("goal"),
            spo2 = d.obj("pulseOx")?.let { it.num("avgValue") ?: it.num("averageValue") },
            respiration = d.obj("respiration")?.let { it.num("avgWakingValue") ?: it.num("averageWakingValue") },
            bbEvents = (bb?.get("activityEvents") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.bbEvent(date) }
                .sortedBy { it.startMin ?: 0 },
        )
    }

    private fun JsonObject.bbEvent(date: String): BbEvent? {
        val impact = int("bodyBatteryImpact") ?: return null
        val start = time("eventStartTimeLocal")?.let { ms ->
            val t = LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC)
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(date), t.toLocalDate())
            (days * 1440 + t.hour * 60 + t.minute).toInt()
        }
        return BbEvent(
            type = str("eventType") ?: "OTHER",
            name = str("activityName"),
            startMin = start,
            minutes = (num("durationInMillis") ?: num("durationInMilliseconds"))?.let { (it / 60000).toInt() },
            impact = impact,
        )
    }

    // --- Heart rate through the day ---

    /** All-day heart rate, in the phone's time zone, as about one point per 5 minutes. */
    fun heartRate(body: String, date: LocalDate, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): HrCurve? {
        val r = root(body) as? JsonObject ?: return null
        val cols = (r["heartRateValueDescriptors"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val ti = cols.firstOrNull { it.str("key") == "timestamp" }?.int("index") ?: 0
        val hi = cols.firstOrNull { it.str("key") == "heartrate" }?.int("index") ?: 1
        val rows = (r["heartRateValues"] as? JsonArray).orEmpty()
        val buckets = sortedMapOf<Int, MutableList<Int>>()
        rows.forEach { row ->
            val a = row as? JsonArray ?: return@forEach
            val ts = (a.getOrNull(ti) as? JsonPrimitive)?.longOrNull ?: return@forEach
            val bpm = (a.getOrNull(hi) as? JsonPrimitive)?.doubleOrNull?.toInt()?.takeIf { it > 0 } ?: return@forEach
            val t = java.time.Instant.ofEpochMilli(ts).atZone(zone)
            if (t.toLocalDate() != date) return@forEach
            buckets.getOrPut((t.hour * 60 + t.minute) / 5 * 5) { mutableListOf() } += bpm
        }
        if (buckets.size < 2) return null
        return HrCurve(date.toString(), buckets.keys.toList(), buckets.values.map { it.average().toInt() })
    }

    // --- Heart-rate zones ---

    fun hrZones(body: String): HrZones? {
        val all = list(body).mapNotNull { z ->
            val floors = (1..5).map { z.int("zone${it}Floor") ?: return@mapNotNull null }
            (z.str("sport") ?: "DEFAULT") to HrZones(
                floors = floors,
                max = z.int("maxHeartRateUsed"),
                resting = z.int("restingHeartRateUsed"),
                threshold = z.int("lactateThresholdHeartRateUsed"),
                method = z.str("trainingMethod"),
            )
        }.toMap()
        // Running's own zones if set, else the default ones.
        return all["RUNNING"] ?: all["DEFAULT"] ?: all.values.firstOrNull()
    }

    // --- Sleep ---

    fun sleeps(body: String): List<Sleep> = list(body, "dailySleepDTOList", "data").mapNotNull { it.sleep() } +
        // The single-day endpoint wraps the DTO; accept that too.
        listOfNotNull((root(body) as? JsonObject)?.obj("dailySleepDTO")?.sleep())

    private fun JsonObject.sleep(): Sleep? {
        val date = str("calendarDate") ?: return null
        val seconds = int("sleepTimeSeconds")?.takeIf { it > 0 } ?: return null
        val overall = obj("sleepScores")?.obj("overall")
        return Sleep(
            date = date,
            seconds = seconds,
            deepS = int("deepSleepSeconds") ?: 0,
            lightS = int("lightSleepSeconds") ?: 0,
            remS = int("remSleepSeconds") ?: 0,
            awakeS = int("awakeSleepSeconds") ?: 0,
            score = overall?.int("value"),
            qualifier = overall?.str("qualifierKey"),
            startLocalMs = time("sleepStartTimestampLocal"),
            endLocalMs = time("sleepEndTimestampLocal"),
            avgHr = num("avgHeartRate"),
            respiration = num("averageRespirationValue"),
            awakeCount = int("awakeCount"),
            needMin = obj("sleepNeed")?.int("actual"),
        )
    }

    // --- HRV ---

    fun hrv(body: String): List<Hrv> = list(body, "hrvSummaries").mapNotNull { it.hrvSummary() } +
        listOfNotNull((root(body) as? JsonObject)?.obj("hrvSummary")?.hrvSummary())

    private fun JsonObject.hrvSummary(): Hrv? {
        val date = str("calendarDate") ?: return null
        val base = obj("baseline")
        return Hrv(
            date = date,
            lastNight = int("lastNightAvg"),
            weeklyAvg = int("weeklyAvg"),
            high5 = int("lastNight5MinHigh"),
            status = str("status"),
            balancedLow = base?.int("balancedLow"),
            balancedUpper = base?.int("balancedUpper"),
            lowUpper = base?.int("lowUpper"),
        ).takeIf { it.lastNight != null || it.weeklyAvg != null }
    }

    // --- Body Battery through the day ---

    fun bodyBattery(body: String): List<BbCurve> = list(body).mapNotNull { r ->
        val date = r.str("date") ?: return@mapNotNull null
        val cols = (r["bodyBatteryValueDescriptorDTOList"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun col(key: String) = cols.firstOrNull { it.str("bodyBatteryValueDescriptorKey") == key }?.int("bodyBatteryValueDescriptorIndex")
        val ti = col("timestamp") ?: 0
        val li = col("bodyBatteryLevel") ?: 1
        val rows = (r["bodyBatteryValuesArray"] as? JsonArray).orEmpty()
        val day = LocalDate.parse(date)
        // Timestamps are GMT epoch ms; the window's local start tells us the offset.
        val offsetMs = run {
            val gmt = r.time("startTimestampGMT")
            val local = r.time("startTimestampLocal")
            if (gmt != null && local != null) local - gmt else 0L
        }
        val pts = rows.mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            val ts = (a.getOrNull(ti) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val level = (a.getOrNull(li) as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return@mapNotNull null
            val local = LocalDateTime.ofEpochSecond((ts + offsetMs) / 1000, 0, ZoneOffset.UTC)
            if (local.toLocalDate() != day) return@mapNotNull null
            (local.hour * 60 + local.minute) to level
        }.sortedBy { it.first }
        if (pts.size < 2) return@mapNotNull null
        // About one point per 10 minutes is plenty for a curve.
        val step = maxOf(1, pts.size / 150)
        val kept = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }
        BbCurve(date, kept.map { it.first }, kept.map { it.second })
    }

    // --- Weight ---

    fun weights(body: String): List<Weight> = list(body, "dailyWeightSummaries").mapNotNull { d ->
        val date = d.str("summaryDate") ?: return@mapNotNull null
        val w = d.obj("latestWeight") ?: return@mapNotNull null
        val raw = w.num("weight") ?: return@mapNotNull null
        // The spec says kilograms; older Garmin services use grams.
        Weight(date, if (raw > 1000) raw / 1000 else raw, w.num("bodyFat"))
    }

    fun fitnessAge(body: String): Double? = list(body).firstNotNullOfOrNull { it.num("fitnessAge") }
        ?: (root(body) as? JsonObject)?.num("fitnessAge")

    /** Seconds since the epoch at which a JWT access token expires, or null when it can't be read. */
    fun tokenExpiry(token: String): Long? = runCatching {
        val payload = token.split('.')[1]
        val claims = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload.trimEnd('='))))
        (claims.jsonObject["exp"] as JsonPrimitive).longOrNull
    }.getOrNull()

    // --- JSON helpers ---

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content.toDoubleOrNull() else it.doubleOrNull }
    private fun JsonObject.int(key: String): Int? = num(key)?.toInt()
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** A timestamp given either as epoch ms or as an ISO string (read as UTC wall time). */
    private fun JsonObject.time(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (!p.isString) return p.longOrNull
        val s = p.content.removeSuffix("Z").take(23)
        return runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: s.toLongOrNull()
    }
}
