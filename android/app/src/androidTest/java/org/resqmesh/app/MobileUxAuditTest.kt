package org.resqmesh.app

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in native screenshots of the user's current environment. This test never submits a report,
 * starts capture, changes a setting, adds a simulation fixture, or asks for a permission.
 * Normal foreground relaying may update delivery state while the screens are inspected.
 */
@RunWith(AndroidJUnit4::class)
class MobileUxAuditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val captures = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private val phase: String by lazy {
        (InstrumentationRegistry.getArguments().getString("auditPhase", "before") ?: "before")
            .also { require(it.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "Invalid audit phase" } }
    }
    private val directory: File by lazy {
        File(context.getExternalFilesDir(null), "mobile-ux-audit/$phase").apply { mkdirs() }
    }

    @Test fun captureCurrentMobileExperienceWithoutSending() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mobileUxAudit") == "true")
        val mesh = (context.applicationContext as MeshApplication).mesh
        await("controller initialization") { mesh.state.value.ready }
        serialBarrier(mesh)
        val beforeIds = reportIds()
        val preferences = context.getSharedPreferences("resqmesh", 0)
        val beforePreferences = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        val beforeMode = mesh.state.value.simulation
        val beforeDevice = mesh.state.value.localNodeId
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val activity = ActivityScenario.launch(MainActivity::class.java)
            scenario = activity
            instrumentation.waitForIdleSync()
            capture("01-home.png")
            assertBeforeScrolling(activity, "Record voice", "Take photo", "Record video")

            val locationAction = listOf("Add location", "Change location").firstOrNull { hasButton(activity, it) }
            if (locationAction != null) {
                click(activity, locationAction)
                capture("01b-location-for-sos.png")
                click(activity, "Back to Home")
            }

            click(activity, if (hasButton(activity, "Choose help")) "Choose help" else "SEND SOS")
            capture("02-sos-draft.png")
            click(activity, "Medical")
            capture("03-sos-choice.png")
            click(activity, "Review SOS")
            assertTrue("Review screen is reached without sending", hasText(activity, "Review your SOS"))
            capture("04-sos-review.png")
            scrollTo(activity, "LOCATION TO SEND")
            capture("05-sos-review-location.png")
            assertEquals("A reviewed draft must not create a report", beforeIds, reportIds())
            activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            instrumentation.waitForIdleSync()
            click(activity, "Home")

            if (hasButton(activity, "Reports")) {
                click(activity, "Reports")
                capture("05b-your-reports.png")
            }
            val existingReport = mesh.state.value.reports
                .filter { it.report.originId == beforeDevice }
                .maxByOrNull { it.report.createdAt }?.report
            if (existingReport != null && clickReport(activity, existingReport.id, existingReport.text)) {
                assertTrue("The chosen existing report is displayed", hasTextContaining(activity, existingReport.id.take(8)))
                capture("06-existing-sos-delivery.png")
                if (hasText(activity, "DELIVERY STATUS")) {
                    scrollTo(activity, "DELIVERY STATUS")
                    capture("07-existing-sos-progress.png")
                }
                if (hasButton(activity, "Delivery details")) {
                    click(activity, "Delivery details")
                    capture("07b-existing-sos-details.png")
                }
                click(activity, "Home")
            } else notes += "No current-device SOS report was available; no report was created for this audit."

            click(activity, "Network")
            capture("08-nearby-network.png")
            click(activity, "Settings")
            capture("09-settings.png")
            if (hasButton(activity, "Connection setup")) {
                click(activity, "Connection setup")
                capture("09b-connection-setup.png")
                click(activity, "Connection setup")
            }
            if (hasButton(activity, "Simulation tools")) click(activity, "Simulation tools")
            if (hasButton(activity, "Open Simulation Lab")) {
                scrollTo(activity, "Open Simulation Lab")
                capture("10-settings-developer-tools.png")
                if (beforeMode) {
                    click(activity, "Open Simulation Lab")
                    capture("11-simulation-lab.png")
                    click(activity, "Back to Settings")
                } else notes += "Simulation Lab skipped because opening it in physical mode would change the mode."
            }
            click(activity, "Home")
            capture("12-home-returned.png")
            activity.onActivity { current ->
                val mainScroll = views(current.window.decorView).filterIsInstance<ScrollView>().first()
                assertEquals("Returning Home resets the scroll position", 0, mainScroll.scrollY)
            }
            assertBeforeScrolling(activity, "Record voice", "Take photo", "Record video")
        } finally {
            scenario?.close()
            serialBarrier(mesh)
            val afterIds = reportIds()
            val afterPreferences = preferences.all
            val reportIdsPreserved = beforeIds == afterIds
            val preferencesPreserved = beforePreferences == afterPreferences
            val modePreserved = beforeMode == mesh.state.value.simulation && beforeDevice == mesh.state.value.localNodeId
            File(directory, "audit.json").writeText(JSONObject()
                .put("phase", phase)
                .put("screenshots", JSONArray(captures))
                .put("notes", JSONArray(notes))
                .put("report_ids_before", beforeIds.size)
                .put("report_ids_after", afterIds.size)
                .put("report_ids_unchanged", reportIdsPreserved)
                .put("preferences_unchanged", preferencesPreserved)
                .put("mode_and_selected_device_unchanged", modePreserved)
                .put("capture_started", false)
                .put("sos_submitted", false)
                .toString(2))
            assertTrue("Audit must not create or remove a persisted report ID", reportIdsPreserved)
            assertTrue("Audit must preserve every saved preference", preferencesPreserved)
            assertTrue("Audit must preserve mode and selected device", modePreserved)
        }
    }

    private fun reportIds(): Set<String> = SQLiteDatabase.openDatabase(
        context.getDatabasePath("resqmesh.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY
    ).use { database ->
        database.rawQuery("SELECT DISTINCT reportId FROM reports", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun serialBarrier(mesh: MeshController) {
        val done = CountDownLatch(1)
        mesh.serial.execute { done.countDown() }
        assertTrue("Controller queue completes", done.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out waiting for $description")
            SystemClock.sleep(50)
        }
    }

    private fun views(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(views(view.getChildAt(index)))
    }

    private fun hasText(scenario: ActivityScenario<MainActivity>, text: String): Boolean {
        var found = false
        scenario.onActivity { activity ->
            found = views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == text }
        }
        return found
    }

    private fun hasButton(scenario: ActivityScenario<MainActivity>, text: String): Boolean {
        var found = false
        scenario.onActivity { activity ->
            found = views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() == text }
        }
        return found
    }

    private fun hasTextContaining(scenario: ActivityScenario<MainActivity>, text: String): Boolean {
        var found = false
        scenario.onActivity { activity ->
            found = views(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains(text, ignoreCase = true) }
        }
        return found
    }

    private fun assertBeforeScrolling(scenario: ActivityScenario<MainActivity>, vararg actions: String) {
        scenario.onActivity { activity ->
            actions.forEach { name ->
                val action = views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == name }
                val bounds = Rect()
                assertTrue("$name is available before scrolling", action.getGlobalVisibleRect(bounds))
                assertEquals("$name is fully visible before scrolling", action.height, bounds.height())
            }
        }
    }

    private fun clickReport(scenario: ActivityScenario<MainActivity>, reportId: String, reportText: String): Boolean {
        fun find(root: View): View? = views(root).firstOrNull { it.tag == "report:$reportId" }
            ?: views(root).filterIsInstance<Button>().firstOrNull { action ->
                action.text.toString() == "View delivery status" &&
                    (action.parent as? View)?.let { container ->
                        views(container).filterIsInstance<TextView>().any { item ->
                            item.text.toString().contains(reportId.take(8), ignoreCase = true) || item.text.toString() == reportText
                        }
                    } == true
            }
        var found = false
        scenario.onActivity { activity ->
            find(activity.window.decorView)?.let { target ->
                found = true
                scrollIntoView(activity.window.decorView, target)
            }
        }
        if (!found) return false
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val target = find(activity.window.decorView) ?: error("Selected report row disappeared")
            assertTrue("Existing report action is visible", target.getGlobalVisibleRect(Rect()))
            assertTrue("Existing report opens", target.performClick())
        }
        instrumentation.waitForIdleSync()
        return true
    }

    private fun click(scenario: ActivityScenario<MainActivity>, text: String) {
        scrollTo(scenario, text)
        scenario.onActivity { activity ->
            val button = views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == text }
            assertTrue("$text is enabled", button.isEnabled)
            assertTrue("$text is visible", button.getGlobalVisibleRect(Rect()))
            assertTrue("$text action runs", button.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun scrollTo(scenario: ActivityScenario<MainActivity>, text: String) {
        scenario.onActivity { activity ->
            val target = views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }
            scrollIntoView(activity.window.decorView, target)
        }
        instrumentation.waitForIdleSync()
    }

    private fun scrollIntoView(root: View, target: View) {
        val scroll = views(root).filterIsInstance<ScrollView>().firstOrNull { parentOf(it, target) } ?: return
        val targetAt = IntArray(2); val scrollAt = IntArray(2)
        target.getLocationInWindow(targetAt); scroll.getLocationInWindow(scrollAt)
        scroll.scrollTo(0, scroll.scrollY + targetAt[1] - scrollAt[1] - 24)
    }

    private fun parentOf(parent: View, child: View): Boolean {
        var candidate = child.parent
        while (candidate is View) {
            if (candidate === parent) return true
            candidate = (candidate as View).parent
        }
        return false
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Allow native smooth-scroll and focus transitions to finish before taking the screenshot.
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Native screenshot unavailable")
        try {
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            captures += name
        } finally { bitmap.recycle() }
    }
}
