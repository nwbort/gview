package com.nwbort.gview

import com.nwbort.gview.data.Parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses shaped like Garmin's Personal API OpenAPI spec. */
class ParseTest {
    @Test
    fun activities() {
        val a = Parse.activities(
            """[{"activityId":123,"activityName":"Morning Run","startTimeLocal":"2026-11-14 06:42:10",
               "activityType":{"typeId":1,"typeKey":"trail_running","parentTypeId":17},
               "distance":10234.5,"duration":3120.2,"movingDuration":3050,"elevationGain":212,
               "averageSpeed":3.28,"averageHR":148,"maxHR":171,"calories":712,
               "averageRunningCadenceInStepsPerMinute":172.4,"aerobicTrainingEffect":3.4,
               "trainingEffectLabel":"TEMPO","vO2MaxValue":54,"hasPolyline":true,"locationName":"Manly"},
              {"activityId":124,"activityName":"Sydney Running","startTimeLocal":"2026-11-12 05:54:55",
               "activityType":{"typeId":1,"typeKey":"running","parentTypeId":17},"distance":15015.7,"duration":5571.0,
               "minTemperature":26.0,"maxTemperature":30.0,"fastestSplit_1000":291.86,"fastestSplit_1609":469.85,
               "fastestSplit_5000":1497.85,"fastestSplit_10000":3615.73,"hrTimeInZone_1":1253.0,"hrTimeInZone_2":2341.98,
               "hrTimeInZone_3":1012.32,"hrTimeInZone_4":834.61,"hrTimeInZone_5":0.0,"avgGradeAdjustedSpeed":2.731},
              {"activityName":"no id"}]"""
        )
        assertEquals(2, a.size)
        with(a[1]) {
            assertEquals(listOf(1253.0, 2341.98, 1012.32, 834.61, 0.0), hrZones)
            assertEquals(mapOf(1000 to 291.86, 1609 to 469.85, 5000 to 1497.85, 10000 to 3615.73), fastest)
            assertEquals(26.0, tempMin!!, 0.0)
            assertEquals(30.0, tempMax!!, 0.0)
        }
        assertTrue(a[0].hrZones.isEmpty())
        with(a[0]) {
            assertEquals(123L, id)
            assertEquals("trail_running", type)
            assertEquals(com.nwbort.gview.data.Sport.RUN, sport)
            assertEquals(10.2345, km!!, 1e-9)
            assertEquals(172.4, cadence!!, 1e-9)
            assertEquals(54.0, vo2Max!!, 1e-9)
            assertTrue(hasRoute)
            assertEquals("2026-11-14", date.toString())
        }
    }

    @Test
    fun daily_summaries_v2() {
        val s = Parse.summaries(
            """{"data":[{"calendarDate":"2026-11-14",
               "movement":{"steps":{"goal":9000,"value":11234,"distanceInMeters":8432.1},"floorsAscended":{"value":12.4}},
               "calories":{"burnedActive":640,"burnedTotal":2510},
               "heartRate":{"minValue":44,"maxValue":171,"restingValue":48},
               "intensityMinutes":{"goal":150,"moderate":20,"vigorous":35},
               "stress":{"avgLevel":27,"maxLevel":88,"restProportion":0.41,"lowStressProportion":0.3,"mediumStressProportion":0.2,"highStressProportion":0.09,"qualifier":"balanced"},
               "bodyBattery":{"minValue":18,"maxValue":92,"chargedValue":64,"drainedValue":70,"latestValue":31}}]}"""
        ).single()
        assertEquals(11234, s.steps)
        assertEquals(9000, s.stepGoal)
        assertEquals(48, s.restingHr)
        assertEquals(27, s.stressAvg)
        assertEquals(92, s.bbMax)
        assertEquals(31, s.bbLatest)
        assertEquals(90, s.intensityMin)
        assertEquals(12.4, s.floors!!, 1e-9)
    }

    @Test
    fun sleep_summaries() {
        val s = Parse.sleeps(
            """[{"calendarDate":"2026-11-14","sleepTimeSeconds":27000,"deepSleepSeconds":5400,"lightSleepSeconds":14400,
                "remSleepSeconds":6000,"awakeSleepSeconds":1200,"sleepStartTimestampLocal":1794955200000,
                "sleepEndTimestampLocal":1794983400000,"avgHeartRate":51.2,
                "sleepScores":{"overall":{"value":84,"qualifierKey":"GOOD"},"remPercentage":{"value":22}},
                "sleepNeed":{"actual":480}},
               {"calendarDate":"2026-11-13","sleepTimeSeconds":0}]"""
        )
        assertEquals(1, s.size)
        assertEquals(84, s[0].score)
        assertEquals("GOOD", s[0].qualifier)
        assertEquals(5400, s[0].deepS)
        assertEquals(480, s[0].needMin)
        assertEquals(1794955200000L, s[0].startLocalMs)
    }

    @Test
    fun hrv_daily() {
        val h = Parse.hrv(
            """{"hrvSummaries":[{"calendarDate":"2026-11-14","weeklyAvg":61,"lastNightAvg":58,"lastNight5MinHigh":80,
               "baseline":{"lowUpper":50,"balancedLow":54,"balancedUpper":70,"markerValue":0.4},"status":"BALANCED"}],"userProfilePk":1}"""
        ).single()
        assertEquals(58, h.lastNight)
        assertEquals(54, h.balancedLow)
        assertEquals("BALANCED", h.status)
    }

    @Test
    fun body_battery_through_the_day_in_local_time() {
        // Local is UTC+11: a reading at 21:00 UTC on the 13th is 8:00 on the 14th.
        val c = Parse.bodyBattery(
            """[{"date":"2026-11-14","charged":60,"drained":55,
                "startTimestampGMT":"2026-11-13T13:00:00.0","startTimestampLocal":"2026-11-14T00:00:00.0",
                "bodyBatteryValueDescriptorDTOList":[{"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"timestamp"},
                  {"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"bodyBatteryStatus"},
                  {"bodyBatteryValueDescriptorIndex":2,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"}],
                "bodyBatteryValuesArray":[[1794603600000,"MEASURED",80],[1794607200000,"MEASURED",76],[null,"x",1]]}]"""
        ).single()
        assertEquals(listOf(480, 540), c.minutes)
        assertEquals(listOf(80, 76), c.levels)
    }

    @Test
    fun weight_in_kilograms_or_grams() {
        val w = Parse.weights(
            """{"dailyWeightSummaries":[{"summaryDate":"2026-11-14","latestWeight":{"weight":72.4,"bodyFat":14.2}},
               {"summaryDate":"2026-11-13","latestWeight":{"weight":72600}}]}"""
        )
        assertEquals(72.4, w[0].kg, 1e-9)
        assertEquals(72.6, w[1].kg, 1e-9)
    }

    @Test
    fun heart_rate_zones_prefer_running() {
        val z = Parse.hrZones(
            """[{"trainingMethod":"HR_MAX","zone1Floor":95,"zone2Floor":114,"zone3Floor":133,"zone4Floor":152,"zone5Floor":171,"maxHeartRateUsed":190,"sport":"DEFAULT"},
               {"trainingMethod":"LACTATE_THRESHOLD","zone1Floor":97,"zone2Floor":116,"zone3Floor":136,"zone4Floor":155,"zone5Floor":174,
                "maxHeartRateUsed":192,"lactateThresholdHeartRateUsed":172,"restingHeartRateUsed":48,"sport":"RUNNING"}]"""
        )!!
        assertEquals(listOf(97, 116, 136, 155, 174), z.floors)
        assertEquals("116–135", z.range(1))
        assertEquals("174–192", z.range(4))
        assertEquals(172, z.threshold)
    }

    @Test
    fun heart_rate_through_the_day_in_local_time() {
        // 21:00 and 21:02 UTC on the 13th are 8:00 and 8:02 on the 14th in Sydney: one 5-minute point.
        val c = Parse.heartRate(
            """{"heartRateValueDescriptors":[{"index":0,"key":"timestamp"},{"index":1,"key":"heartrate"}],
               "heartRateValues":[[1794603600000,60],[1794603720000,64],[1794607200000,null],[1794607200000,72]]}""",
            java.time.LocalDate.of(2026, 11, 14), java.time.ZoneId.of("Australia/Sydney"),
        )!!
        assertEquals(listOf(480, 540), c.minutes)
        assertEquals(listOf(62, 72), c.bpm)
    }

    @Test
    fun body_battery_events_from_the_daily_summary() {
        val s = Parse.summaries(
            """{"data":[{"calendarDate":"2026-11-12","bodyBattery":{"maxValue":80,"activityEvents":[
               {"eventType":"ACTIVITY","activityName":"Sydney Running","eventStartTimeLocal":"2026-11-12T05:54:55.0","bodyBatteryImpact":-21,"durationInMillis":5571000},
               {"eventType":"SLEEP","eventStartTimeLocal":"2026-11-11T22:10:00.0","bodyBatteryImpact":54,"durationInMillis":26400000}]}}]}"""
        ).single()
        assertEquals(listOf("SLEEP", "ACTIVITY"), s.bbEvents.map { it.type })
        assertEquals(-110, s.bbEvents[0].startMin) // 10:10 pm the night before
        assertEquals(354, s.bbEvents[1].startMin)
        assertEquals(-21, s.bbEvents[1].impact)
        assertEquals(92, s.bbEvents[1].minutes)
    }

    @Test
    fun junk_is_skipped_not_fatal() {
        assertTrue(Parse.activities("<html>oops</html>").isEmpty())
        assertTrue(Parse.summaries("{}").isEmpty())
        assertNull(Parse.fitnessAge("[]"))
        assertNull(Parse.tokenExpiry("not-a-jwt"))
    }
}
