package org.resqmesh.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, read-only native evidence capture after the live integration test has saved receipts. */
@RunWith(AndroidJUnit4::class)
class CaptureEvidenceTest {
    @Test fun captureCurrentMediaHome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureEvidence") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mesh = (instrumentation.targetContext.applicationContext as MeshApplication).mesh
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val end = SystemClock.elapsedRealtime() + 10_000
            while (!mesh.state.value.ready) {
                if (SystemClock.elapsedRealtime() > end) fail("Controller did not initialize")
                SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val buttons = views(activity.window.decorView).filterIsInstance<Button>()
                listOf("Record voice", "Take photo", "Record video").forEach { title ->
                    val button = buttons.single { it.text.toString() == title }
                    assertTrue("$title visible before scrolling", button.getGlobalVisibleRect(android.graphics.Rect()))
                }
            }
            capture("media-home-current.png")
        }
    }
    @Test fun captureExistingLocalReceiptJourney() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureEvidence") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mesh = (instrumentation.targetContext.applicationContext as MeshApplication).mesh
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (!mesh.state.value.ready || mesh.state.value.receipts.none { it.receipt.type == "responder_acknowledged" }) {
                if (SystemClock.elapsedRealtime() > end) fail("Run the live receipt test before evidence capture")
                SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            capture("native-home-live.png")
            val state = mesh.state.value
            val acknowledged = state.receipts.filter { it.receipt.type == "responder_acknowledged" }.map { it.receipt.reportId }.toSet()
            val report = state.reports.filter { it.report.originId == state.localNodeId && it.report.id in acknowledged }
                .maxBy { it.report.createdAt }.report
            click(scenario, "Reports")
            click(scenario, "All reports")
            var page = 0
            while (!hasReport(scenario, report.id)) {
                check(page++ < state.reports.size) { "Acknowledged report is not reachable in report pages" }
                click(scenario, "Next")
            }
            scenario.onActivity { activity ->
                val target = views(activity.window.decorView).filterIsInstance<Button>().first { button ->
                    button.tag == "report:${report.id}"
                }
                assertTrue(target.performClick())
            }
            instrumentation.waitForIdleSync()
            capture("native-journey-summary.png")
            click(scenario, "Delivery details")
            scrollTo(scenario, "Backend receipt arrived")
            capture("native-journey-events.png")
            scrollTo(scenario, "Response system receipt")
            capture("native-backend-and-responder-receipts.png")
            click(scenario, "Network"); capture("native-network-local.png")
            click(scenario, "Settings")
            if (state.simulation) {
                click(scenario, "Simulation tools"); click(scenario, "Open Simulation Lab"); capture("native-simulation-lab.png")
            }
            click(scenario, "Home")
        } finally { scenario.close() }
    }
    private fun views(v: View): List<View> = buildList { add(v); if (v is ViewGroup) for (i in 0 until v.childCount) addAll(views(v.getChildAt(i))) }
    private fun hasReport(s: ActivityScenario<MainActivity>, reportId: String): Boolean {
        var found = false
        s.onActivity { a -> found = views(a.window.decorView).any { it.tag == "report:$reportId" } }
        return found
    }
    private fun click(s: ActivityScenario<MainActivity>, text: String) {
        s.onActivity { a ->
            val button = views(a.window.decorView).filterIsInstance<Button>().first { it.text.toString() == text }
            assertTrue("$text is enabled", button.isEnabled)
            assertTrue(button.performClick())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun scrollTo(s: ActivityScenario<MainActivity>, text: String) {
        s.onActivity { a ->
            val all = views(a.window.decorView)
            val scroll = all.filterIsInstance<ScrollView>().single()
            val target = all.filterIsInstance<TextView>().first { it.text.toString() == text }
            val targetAt = IntArray(2); val scrollAt = IntArray(2); target.getLocationInWindow(targetAt); scroll.getLocationInWindow(scrollAt)
            scroll.scrollTo(0, scroll.scrollY + targetAt[1] - scrollAt[1] - 24)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation(); i.waitForIdleSync()
        val image = i.uiAutomation.takeScreenshot() ?: error("No native screenshot available")
        try { val directory = File(i.targetContext.getExternalFilesDir(null), "live-evidence").apply { mkdirs() }
            File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
