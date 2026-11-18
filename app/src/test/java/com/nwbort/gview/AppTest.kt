package com.nwbort.gview

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.nwbort.gview.ui.Fmt
import java.io.File

/** The real app wired to [FakeGarmin], with map tiles off so screenshots don't need the network. */
class TestApp : Application() {
    override fun onCreate() {
        Graph.api = FakeGarmin()
        super.onCreate()
        Graph.init(this)
        Graph.tiles.online = false
    }
}

/**
 * Drives the app through every screen on the JVM, so a crash anywhere in
 * composition fails the build. The PNGs it leaves in app/build/screenshots/
 * are for checking layout without a phone to hand.
 *
 * One test method on purpose: the app's singletons belong to one Application,
 * and Robolectric makes a new one per test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", application = TestApp::class)
class AppTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun connect_sync_and_every_screen_renders() {
        waitForText("Connect Garmin")
        shot("01-welcome")

        compose.onNodeWithText("Connect Garmin").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("test-key")
        shot("02-connect")
        compose.onNodeWithText("Connect").performClick()

        // The first sync pulls a year from the fake Garmin.
        // Real time, not Robolectric's clock: the sync runs on background threads.
        val deadline = System.currentTimeMillis() + 60_000
        while (!(Graph.store.cache.value.lastSync != null && !Graph.sync.running.value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            compose.waitForIdle()
        }
        val cache = Graph.store.cache.value
        assertTrue(cache.activities.size > 200)
        assertTrue(cache.summaries.size >= 365)
        assertEquals(31.0, cache.fitnessAge!!, 0.0)
        waitForText("Today")
        waitForText("SLEEP")
        shot("03-today")
        scrollTo("MOVEMENT")
        shot("04-today-more")
        scrollTo("HEART RATE")
        shot("04b-today-heart")
        scrollTo("STRESS")
        shot("05-today-end")

        compose.onNodeWithContentDescription("Previous day").performClick()
        waitForText("Yesterday")
        shot("06-yesterday")

        compose.onNodeWithText("Activities").performClick()
        waitForText("This week")
        shot("07-activities")

        compose.onAllNodesWithText("Long Run").onFirst().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Route map").fetchSemanticsNodes().isNotEmpty() }
        waitForText("Avg heart rate")
        shot("08-activity")
        scrollTo("SPLITS")
        shot("09-activity-splits")

        compose.onNodeWithContentDescription("Route map").performScrollTo().performClick()
        waitForText("Heart rate")
        shot("10-map")
        // Switch the map's chart to heart rate and read it partway along: a dot marks the spot.
        compose.onNodeWithText("Heart rate").performClick()
        compose.onNodeWithContentDescription("Heart rate chart").performTouchInput {
            down(center); moveTo(Offset(width * 0.6f, centerY))
        }
        compose.waitForIdle()
        shot("10b-map-heart-rate")
        compose.onNodeWithContentDescription("Heart rate chart").performTouchInput { up() }
        back()
        back()

        // The last-activity widget's tap opens the newest activity's page, from anywhere.
        compose.onNodeWithText("Today").performClick()
        val latest = Graph.store.cache.value.sortedActivities.first()
        compose.runOnUiThread { compose.activity.onNewIntent(MainActivity.openActivity(compose.activity, latest.id)) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(Fmt.when_(latest), substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("08b-from-widget")
        back()

        // Routes fetched for the detail page now show in the list.
        compose.waitForIdle()
        shot("11-activities-with-routes")

        // Runs' files are analysed in the background after a sync.
        val analysedBy = System.currentTimeMillis() + 60_000
        while (Graph.store.cache.value.runStats.size < 10 && System.currentTimeMillis() < analysedBy) {
            Thread.sleep(100)
            compose.waitForIdle()
        }
        assertTrue("runs analysed: ${Graph.store.cache.value.runStats.size}", Graph.store.cache.value.runStats.size >= 10)

        compose.onNodeWithText("Trends").performClick()
        waitForText("TRAINING VOLUME")
        shot("12-trends")
        scrollTo("FITNESS & FATIGUE")
        shot("12c-fitness")
        scrollTo("AEROBIC EFFICIENCY")
        shot("12d-efficiency")
        compose.onNodeWithText("From everything synced", substring = true).performScrollTo()
        shot("12e-recovery")
        compose.onNodeWithText("Garmin's fastest stretch", substring = true).performScrollTo()
        shot("12f-bests")
        scrollTo("TIME IN HEART-RATE ZONES")
        shot("12g-zones")
        scrollTo("RESTING HEART RATE")
        shot("13-trends-more")

        compose.onNodeWithText("HRV").performClick()
        waitForText("Lowest")
        shot("14-metric-hrv")
        back()

        compose.onAllNodesWithText("All").onFirst().performClick() // the range picker's, at the top
        compose.waitForIdle()
        shot("12b-trends-all")

        compose.onNodeWithContentDescription("Settings").performClick()
        waitForText("Sync now")
        shot("15-settings")

        // Two years of history: the older year is fetched on top of the first.
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("2 years").performClick()
        val backfillBy = System.currentTimeMillis() + 60_000
        while (Graph.store.cache.value.historyYears != 2 || Graph.sync.running.value ||
            (Graph.store.cache.value.summaries.keys.minOrNull() ?: "9") > java.time.LocalDate.now().minusDays(700).toString()) {
            if (System.currentTimeMillis() > backfillBy) throw AssertionError("history not backfilled: ${Graph.store.cache.value.earliest}")
            Thread.sleep(100)
            compose.waitForIdle()
        }
        assertTrue(Graph.store.cache.value.activities.size > 500)
        shot("16-settings-two-years")
        back()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun scrollTo(text: String) {
        compose.onAllNodesWithText(text).onFirst().performScrollTo()
        compose.waitForIdle()
    }

    private fun back() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
