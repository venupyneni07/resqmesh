package org.resqmesh.app

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume
import org.junit.runner.RunWith
import org.resqmesh.app.core.LocationContext
import org.resqmesh.app.data.LocalCipher
import java.util.UUID
import java.io.File

/** Local emulator regressions, not a substitute for real-user or TalkBack usability testing. */
@RunWith(AndroidJUnit4::class)
class EnglishAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun encryptedUnsentDraftSurvivesNewStoreAndExplicitDiscard() {
        val name = "test-unsent-draft-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            val snapshot = SavedSosDraft(mapOf("text" to "SYNTHETIC DRAFT ONLY", "location" to "SYNTHETIC LANDMARK"), 2,
                setOf("cannot_move"), setOf("message"), LocationContext("manual", 123456), true)
            assertTrue(SosDraftStore(context, name).save(snapshot))
            val encoded = requireNotNull(prefs.getString("draft", null))
            assertTrue(LocalCipher.isEncrypted(encoded))
            assertFalse(encoded.contains("SYNTHETIC DRAFT ONLY"))
            val restored = requireNotNull(SosDraftStore(context, name).load())
            assertEquals(snapshot.category, restored.category)
            assertEquals(snapshot.fields["text"], restored.fields["text"])
            assertEquals(snapshot.fields["location"], restored.fields["location"])
            assertEquals(snapshot.needs, restored.needs)
            assertEquals(snapshot.location, restored.location)
            SosDraftStore(context, name).clear()
            assertNull(SosDraftStore(context, name).load())
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun englishCoreActionsRemainLabelledAndDraftSurvivesRestart() {
        val draftPrefs = context.getSharedPreferences("resqmesh_unsent_draft", 0)
        val draftBefore = draftPrefs.all.toMap()
        val mesh = (context.applicationContext as MeshApplication).mesh
        val ready = java.util.concurrent.CountDownLatch(1)
        mesh.serial.execute { ready.countDown() }
        assertTrue("Controller initialized", ready.await(30, java.util.concurrent.TimeUnit.SECONDS))
        val originals = mesh.state.value.reports.map { it.report.id }.toSet()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            run {
                val expectedTitle = "Need help?"
                val activity = ActivityScenario.launch(MainActivity::class.java); scenario = activity
                instrumentation.waitForIdleSync()
                activity.onActivity { current ->
                    assertEquals("App UI is English", "en", current.resources.configuration.locales[0].language)
                    val title = views(current.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == expectedTitle }
                    if (Build.VERSION.SDK_INT >= 28) assertTrue("Screen title is an accessibility heading", title.isAccessibilityHeading)
                    for (id in listOf(R.string.ui_record_voice, R.string.ui_take_photo, R.string.ui_record_video, R.string.ui_choose_help)) {
                        val control = button(current, current.getString(id))
                        assertTrue("Core capture control is enabled", control.isEnabled)
                        assertTrue("Core capture control has a 48dp minimum target", control.minHeight >= (48 * current.resources.displayMetrics.density).toInt())
                        assertFalse(control.text.isBlank())
                    }
                }
                screenshot("en-home.png")
                activity.onActivity { current ->
                    button(current, current.getString(R.string.ui_choose_help)).performClick()
                    button(current, current.getString(R.string.ui_medical)).performClick()
                    button(current, current.getString(R.string.ui_review_sos)).performClick()
                    assertTrue(views(current.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == current.getString(R.string.ui_review_your_sos) })
                    assertTrue(button(current, current.getString(R.string.ui_send_emergency_sos)).isEnabled)
                }
                screenshot("en-review.png")
                activity.close(); scenario = null
                // A fresh Activity launch (without a retained ViewModel or recreation Bundle) restores the selection.
                val reopened = ActivityScenario.launch(MainActivity::class.java); scenario = reopened
                reopened.onActivity { current ->
                    button(current, current.getString(R.string.ui_resume_draft)).performClick()
                    assertTrue(button(current, current.getString(R.string.ui_medical)).isSelected)
                }
                reopened.close(); scenario = null
            }
            assertTrue("Draft navigation sends no new SOS", mesh.state.value.reports.map { it.report.id }.toSet().all { it in originals })
        } finally {
            try { scenario?.close() } finally {
                restoreStrings(draftPrefs, draftBefore)
            }
        }
    }

    /** Run separately after the operator sets real emulator font scale to 2.0; this test never changes global settings. */
    @Test fun landscapeAtReal200PercentFontKeepsReviewActionReachable() {
        Assume.assumeTrue("Requires the emulator's actual font scale >= 2.0; no synthetic scaling", context.resources.configuration.fontScale >= 1.9f)
        val draftPrefs = context.getSharedPreferences("resqmesh_unsent_draft", 0)
        val draftBefore = draftPrefs.all.toMap()
        var scenario: ActivityScenario<MainActivity>? = null
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        try {
            val activity = ActivityScenario.launch(MainActivity::class.java); scenario = activity
            activity.onActivity { current -> originalOrientation = current.requestedOrientation; current.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val deadline = SystemClock.uptimeMillis() + 10000
            var landscape = false
            while (!landscape && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                activity.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                if (!landscape) SystemClock.sleep(100)
            }
            assertTrue("Landscape configuration applied", landscape)
            activity.onActivity { current -> assertTrue("Real font scale is retained", current.resources.configuration.fontScale >= 1.9f) }
            screenshot("en-landscape-200-home.png")
            clickReachable(activity, R.string.ui_choose_help)
            clickReachable(activity, R.string.ui_medical)
            clickReachable(activity, R.string.ui_review_sos)
            screenshot("en-landscape-200-review-top.png")
            activity.onActivity { current ->
                val send = button(current, current.getString(R.string.ui_send_emergency_sos))
                send.requestRectangleOnScreen(Rect(0, 0, send.width, send.height), true)
            }
            instrumentation.waitForIdleSync()
            activity.onActivity { current ->
                val send = button(current, current.getString(R.string.ui_send_emergency_sos))
                val bounds = Rect()
                assertTrue("Send action is reachable by scrolling", send.getGlobalVisibleRect(bounds))
                assertEquals("Entire send action fits in viewport", send.height, bounds.height())
                assertTrue("Send action remains at least 48dp tall", send.height >= (48 * current.resources.displayMetrics.density).toInt())
            }
            screenshot("en-landscape-200-review-action.png")
        } finally {
            try {
                scenario?.onActivity { it.requestedOrientation = originalOrientation }
                instrumentation.waitForIdleSync()
            } finally {
                try { scenario?.close() } finally {
                    restoreStrings(draftPrefs, draftBefore)
                }
            }
        }
    }

    private fun clickReachable(activity: ActivityScenario<MainActivity>, stringId: Int) {
        activity.onActivity { current ->
            val control = button(current, current.getString(stringId))
            control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
        }
        instrumentation.waitForIdleSync()
        activity.onActivity { current ->
            val control = button(current, current.getString(stringId)); val bounds = Rect()
            assertTrue("Action is reachable: ${control.text}", control.getGlobalVisibleRect(bounds))
            control.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "local-completion-ui").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun button(activity: MainActivity, text: String) = views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == text }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun restoreStrings(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
    }
}
