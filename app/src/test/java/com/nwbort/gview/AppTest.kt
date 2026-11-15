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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
        compose.waitForIdle()
        shot("10-map")
        back()
        back()

        // Routes fetched for the detail page now show in the list.
        compose.waitForIdle()
        shot("11-activities-with-routes")

        compose.onNodeWithText("Trends").performClick()
        waitForText("TRAINING VOLUME")
        shot("12-trends")
        scrollTo("RESTING HEART RATE")
        shot("13-trends-more")

        compose.onNodeWithText("HRV").performClick()
        waitForText("Lowest")
        shot("14-metric-hrv")
        back()

        compose.onNodeWithContentDescription("Settings").performClick()
        waitForText("Sync now")
        shot("15-settings")
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
