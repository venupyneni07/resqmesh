package org.resqmesh.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.resqmesh.app.core.DeliveryStatus
import org.resqmesh.app.core.LocationContext
import org.resqmesh.app.core.QUICK_SOS_MESSAGE
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native-view product regression test. No radios, backend, model, or external network required. */
@RunWith(AndroidJUnit4::class)
class ProductFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun offlineSosDraftJourneyAndSettingsSurviveRecreation() {
        val context = instrumentation.targetContext
        val mesh = (context.applicationContext as MeshApplication).mesh
        await("controller ready") { mesh.state.value.ready }
        val original = mesh.state.value
        val prefs = context.getSharedPreferences("resqmesh", 0)
        val changedKeys = setOf("simulation", "relayEnabled", "backend", "apiKey", "simulationEnvironment", "selectedSim", "savedSosLocation", "omitQuickLocation")
        val savedPreferences = prefs.all.filterKeys { it in changedKeys }
        val originalKey = org.resqmesh.app.data.CredentialStore(context).read()
        val originalIds = original.lab.nodes.map { it.id }.toSet()
        val createdNodes = linkedSetOf<String>()
        val customBackend = "http://127.0.0.1:49173"
        val description = "SYNTHETIC UI TEST ${UUID.randomUUID()}: no real emergency; testing local draft persistence."
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            // Pause before changing the environment; existing reports and topology are retained.
            mesh.background()
            mesh.setRelayEnabled(false)
            mesh.switchMode(true)
            serialBarrier(mesh)
            original.lab.nodes.filter { it.internet }.forEach { mesh.simSetInternet(it.id, false) }
            mesh.simAddNode()
            mesh.simAddNode()
            serialBarrier(mesh)
            createdNodes.addAll(mesh.state.value.lab.nodes.map { it.id }.filter { it !in originalIds })
            assertEquals("Exactly two isolated virtual test devices", 2, createdNodes.size)
            val source = createdNodes.first()
            val peer = createdNodes.last()
            mesh.simSelectNode(source)
            mesh.simSetLink(source, peer, true)
            serialBarrier(mesh)
            mesh.saveSosLocation(null)
            assertTrue("Use an explicit unknown-location fixture", prefs.edit().putBoolean("omitQuickLocation", true).commit())

            val activity = ActivityScenario.launch(MainActivity::class.java)
            scenario = activity
            await("paused home") { hasText(activity, "Nearby relay paused") }
            assertFalse("Disabled relay must not advertise availability", hasText(activity, "Nearby relay available"))
            assertFalse("Offline test must not claim a gateway", hasText(activity, "Gateway in range"))
            screenshot("product-home-paused.png")

            if (!SosLocationProvider(context).hasPermission()) {
                // Exercise both the no-fix fallback and the Android permission-denied callback.
                var fallbackCalled = false
                activity.onActivity { current -> SosLocationProvider(current).request { fix -> assertNull(fix); fallbackCalled = true } }
                assertTrue("Missing permission returns without waiting for GPS", fallbackCalled)
                click(activity, "Choose help"); click(activity, "Location  +")
                activity.onActivity { current -> current.onRequestPermissionsResult(71,
                    arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION),
                    intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED, android.content.pm.PackageManager.PERMISSION_DENIED)) }
                assertTrue(hasTextContaining(activity, "Location access declined"))
                screenshot("product-location-denied.png")
                click(activity, "Home")
            }
            // The primary action starts a draft. Entering, reviewing, or leaving that draft sends nothing.
            val beforeDraft = mesh.state.value.reports.map { it.report.id }.toSet()
            click(activity, "Choose help")
            assertTrue(hasText(activity, "What help do you need?"))
            activity.onActivity { current ->
                val categories = setOf("Medical", "Fire", "Flood", "Accident", "Trapped", "Safety threat", "Other / not sure")
                val choices = views(current.window.decorView).filterIsInstance<Button>().filter { it.text.toString() in categories }
                assertEquals("All emergency categories are available", categories.size, choices.size)
                assertTrue("Opening SOS must not invent an emergency type", choices.none { it.isSelected })
            }
            serialBarrier(mesh)
            assertEquals("Opening the SOS form must not create a report", beforeDraft, mesh.state.value.reports.map { it.report.id }.toSet())
            click(activity, "Review SOS")
            assertTrue("An explicit emergency choice is required", hasText(activity, "What help do you need?"))
            assertFalse(hasText(activity, "Review your SOS"))
            serialBarrier(mesh)
            assertEquals("Reviewing without a category cannot send", beforeDraft, mesh.state.value.reports.map { it.report.id }.toSet())
            click(activity, "Other / not sure")
            click(activity, "Review SOS")
            assertTrue(hasText(activity, "Review your SOS"))
            assertTrue(hasTextContaining(activity, "Other / not sure"))
            assertTrue(hasTextContaining(activity, QUICK_SOS_MESSAGE))
            assertTrue("An unspecified people count remains visibly unknown", hasText(activity, "People affected: Unknown"))
            assertTrue("No selected needs must not become an inferred assessment", hasText(activity, "Needs: Not specified"))
            screenshot("product-sos-review-no-typed-details.png")
            back(activity)
            assertTrue("System Back returns from review to the existing draft", hasText(activity, "What help do you need?"))
            activity.onActivity { current ->
                assertTrue(views(current.window.decorView).filterIsInstance<Button>().single { it.text == "Other / not sure" }.isSelected)
            }
            click(activity, "Home")
            serialBarrier(mesh)
            assertEquals("Leaving a reviewed draft must not send it", beforeDraft, mesh.state.value.reports.map { it.report.id }.toSet())

            // A general SOS is available without typing, but opening its confirmation sends nothing.
            val beforeQuick = mesh.state.value.reports.map { it.report.id }.toSet()
            activity.onActivity { current ->
                val send = views(current.window.decorView).filterIsInstance<Button>().single { it.text == "General SOS" }
                send.performClick(); send.performClick()
            }
            await("general SOS confirmation") { generalDialogVisible() }
            assertGeneralDialog("Location unknown")
            serialBarrier(mesh)
            assertEquals("Opening twice must not submit an SOS", beforeQuick, mesh.state.value.reports.map { it.report.id }.toSet())
            screenshot("product-general-sos-confirmation.png")
            clickDialog("Cancel")
            await("one cancel dismisses the only general SOS dialog") { !generalDialogVisible() }
            serialBarrier(mesh)
            assertEquals("Cancel must not submit an SOS", beforeQuick, mesh.state.value.reports.map { it.report.id }.toSet())

            click(activity, "General SOS")
            await("general SOS confirmation before Back") { generalDialogVisible() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back dismisses general SOS confirmation") { !generalDialogVisible() }
            serialBarrier(mesh)
            assertEquals("Back must not submit an SOS", beforeQuick, mesh.state.value.reports.map { it.report.id }.toSet())

            click(activity, "General SOS")
            await("general SOS confirmation before explicit send") { generalDialogVisible() }
            assertGeneralDialog("Location unknown")
            clickDialog("Send SOS")
            await("quick SOS saved") { mesh.state.value.reports.any { it.report.id !in beforeQuick } }
            await("quick SOS delivery") { hasText(activity, "SOS delivery") }
            serialBarrier(mesh)
            assertEquals("Explicit confirmation creates exactly one SOS", 1, mesh.state.value.reports.count { it.report.id !in beforeQuick })
            val quick = mesh.state.value.reports.single { it.report.id !in beforeQuick }
            assertEquals(QUICK_SOS_MESSAGE, quick.report.text)
            assertEquals("preset", quick.report.messageSource)
            assertEquals("other", quick.report.emergencyType)
            assertNull(quick.report.peopleAffected)
            assertTrue(quick.report.quickNeeds.isEmpty())
            assertTrue(quick.report.attachments.isEmpty())
            assertEquals("unknown", quick.report.locationContext.source)
            assertNull(quick.report.locationContext.latitude)
            assertNull(quick.report.locationContext.longitude)
            assertEquals(DeliveryStatus.PENDING, quick.status)
            assertTrue(hasText(activity, "Saved locally"))
            assertTrue(hasText(activity, "Stored on this device"))
            assertTrue(hasText(activity, "Backend received"))
            assertTrue(hasText(activity, "No backend receipt yet"))
            assertTrue(hasText(activity, "Human acknowledged"))
            assertTrue(hasText(activity, "No human acknowledgement yet"))
            screenshot("product-quick-sos-local.png")
            click(activity, "Home")
            click(activity, "Choose help")
            screenshot("product-emergency-choices.png")
            click(activity, "Flood")
            activity.onActivity { current ->
                val needs = views(current.window.decorView).filterIsInstance<CheckBox>()
                needs.single { it.text == "Cannot move" }.performClick()
                needs.single { it.text == "Cannot speak" }.performClick()
                needs.single { it.text == "People injured" }.performClick()
            }
            screenshot("product-quick-choices.png")
            click(activity, "People affected  +")
            click(activity, "3")
            click(activity, "Write a message  +")
            expand(activity, "Location")
            activity.onActivity { current ->
                edit(current, "Describe the emergency").setText(description)
                edit(current, "Building or landmark").setText("SYNTHETIC TEST BUILDING")
                edit(current, "Address or access point").setText("Local test only")
                edit(current, "e.g. Ground").setText("2")
                edit(current, "e.g. 12").setText("TEST-12")
            }
            screenshot("product-sos-draft.png")
            activity.recreate()
            instrumentation.waitForIdleSync()
            activity.onActivity { current ->
                assertEquals(description, edit(current, "Describe the emergency").text.toString())
                assertEquals("SYNTHETIC TEST BUILDING", edit(current, "Building or landmark").text.toString())
                assertEquals("TEST-12", edit(current, "e.g. 12").text.toString())
                assertEquals("3", edit(current, "Unknown").text.toString())
                assertTrue(views(current.window.decorView).filterIsInstance<Button>().single { it.text == "Flood" }.isSelected)
                assertTrue(views(current.window.decorView).filterIsInstance<CheckBox>().filter { it.text.toString() in setOf("Cannot move", "Cannot speak", "People injured") }.all { it.isChecked })
            }

            val beforeDetailed = mesh.state.value.reports.map { it.report.id }.toSet()
            click(activity, "Review SOS")
            assertReview(activity, description)
            serialBarrier(mesh)
            assertEquals("Review is read-only until the explicit final send", beforeDetailed, mesh.state.value.reports.map { it.report.id }.toSet())
            screenshot("product-sos-review.png")
            activity.recreate()
            instrumentation.waitForIdleSync()
            assertReview(activity, description)
            click(activity, "Edit details")
            assertTrue(hasText(activity, "What help do you need?"))
            activity.onActivity { current ->
                assertEquals(description, edit(current, "Describe the emergency").text.toString())
                assertEquals("SYNTHETIC TEST BUILDING", edit(current, "Building or landmark").text.toString())
                assertEquals("TEST-12", edit(current, "e.g. 12").text.toString())
                assertEquals("3", edit(current, "Unknown").text.toString())
                assertTrue(views(current.window.decorView).filterIsInstance<Button>().single { it.text == "Flood" }.isSelected)
                assertTrue(views(current.window.decorView).filterIsInstance<CheckBox>().filter { it.text.toString() in setOf("Cannot move", "Cannot speak", "People injured") }.all { it.isChecked })
            }
            click(activity, "Review SOS")
            assertReview(activity, description)
            // Hold persistence in flight so recreation cannot race past the critical duplicate-send window.
            val serialBlocked = CountDownLatch(1)
            val releaseCreate = CountDownLatch(1)
            mesh.serial.execute { serialBlocked.countDown(); releaseCreate.await() }
            try {
                assertTrue("Persistence queue is held before sending", serialBlocked.await(10, TimeUnit.SECONDS))
                activity.onActivity { current ->
                    val send = views(current.window.decorView).filterIsInstance<Button>().single { it.text == "SEND EMERGENCY SOS" }
                    send.performClick(); send.performClick()
                }
                activity.recreate()
                instrumentation.waitForIdleSync()
                assertTrue("Pending send survives recreation", hasText(activity, "Saving SOS…"))
                activity.onActivity { current ->
                    val send = views(current.window.decorView).filterIsInstance<Button>().single { it.text == "Saving SOS…" }
                    assertFalse("Recreated screen cannot submit the pending SOS again", send.isEnabled)
                    // performClick bypasses touch dispatch; the retained submit guard must still reject this.
                    send.performClick()
                }
                assertEquals("Persistence is still queued, not falsely reported as saved", beforeDetailed,
                    mesh.state.value.reports.map { it.report.id }.toSet())
                screenshot("product-sos-saving-recreated.png")
            } finally {
                // Release even when an assertion fails so app cleanup can restore the user's environment.
                releaseCreate.countDown()
            }
            await("saved local report") { mesh.state.value.reports.any { it.report.text == description } }
            await("delivery journey") { hasText(activity, "SOS delivery") }
            serialBarrier(mesh)
            assertEquals("Double-tapping and recreating during final send creates exactly one report", 1, mesh.state.value.reports.count { it.report.id !in beforeDetailed })
            val saved = mesh.state.value.reports.single { it.report.text == description }
            assertEquals(source, saved.nodeId)
            assertEquals(source, saved.report.originId)
            assertEquals(listOf(source), saved.report.relayPath)
            assertEquals(DeliveryStatus.PENDING, saved.status)
            assertTrue(saved.report.simulation)
            assertEquals("flood", saved.report.emergencyType)
            assertEquals(3, saved.report.peopleAffected)
            assertEquals(setOf("cannot_move", "cannot_speak", "people_injured"), saved.report.quickNeeds.toSet())
            assertEquals("manual", saved.report.locationContext.source)
            assertNotNull(saved.report.locationContext.observedAt)
            assertEquals("user", saved.report.messageSource)
            assertTrue("An offline source cannot know any delivery receipt", mesh.state.value.receipts.none { it.receipt.reportId == saved.report.id })
            assertFalse(hasText(activity, "Delivered to response system"))
            assertFalse(hasText(activity, "Responder acknowledged"))
            assertFalse(hasText(activity, "Uploaded from this device"))
            screenshot("product-local-journey.png")

            // A saved location keeps its actual age through recreation and the next quick SOS.
            val oldObservation = System.currentTimeMillis() - 7_200_000
            assertTrue(mesh.saveSosLocation(SavedSosLocation(building = "SYNTHETIC SAVED PLACE", context = LocationContext("manual", oldObservation))))
            assertTrue("Use the explicitly saved test location", prefs.edit().putBoolean("omitQuickLocation", false).commit())
            click(activity, "Home")
            activity.recreate(); instrumentation.waitForIdleSync()
            assertTrue(hasTextContaining(activity, "2 h ago · may be outdated"))
            val beforeSaved = mesh.state.value.reports.map { it.report.id }.toSet()
            click(activity, "General SOS")
            await("saved-location general SOS confirmation") { generalDialogVisible() }
            assertGeneralDialog("SYNTHETIC SAVED PLACE", "Saved location", "2 h ago · may be outdated")
            serialBarrier(mesh)
            assertEquals("Reviewing a saved-location SOS sends nothing", beforeSaved, mesh.state.value.reports.map { it.report.id }.toSet())
            assertTrue(mesh.saveSosLocation(SavedSosLocation(building = "SYNTHETIC CHANGE AFTER CONFIRMATION",
                context = LocationContext("manual", System.currentTimeMillis()))))
            assertGeneralDialog("SYNTHETIC SAVED PLACE", "Saved location", "2 h ago · may be outdated")
            assertFalse("An open confirmation keeps its location snapshot", dialogText().contains("SYNTHETIC CHANGE AFTER CONFIRMATION"))
            screenshot("product-general-sos-saved-location.png")
            clickDialog("Send SOS")
            await("quick SOS with saved location") { mesh.state.value.reports.any { it.report.id !in beforeSaved } }
            serialBarrier(mesh)
            assertEquals("Saved-location confirmation creates exactly one SOS", 1, mesh.state.value.reports.count { it.report.id !in beforeSaved })
            val savedQuick = mesh.state.value.reports.single { it.report.id !in beforeSaved }.report
            assertEquals("SYNTHETIC SAVED PLACE", savedQuick.building)
            assertEquals("saved", savedQuick.locationContext.source)
            assertEquals(oldObservation, savedQuick.locationContext.observedAt)
            assertEquals("preset", savedQuick.messageSource)
            click(activity, "Settings")
            click(activity, "Connection setup")
            activity.onActivity { edit(it, "Backend URL").setText(customBackend) }
            click(activity, "Save connection")
            await("saved custom backend") { mesh.state.value.backend == customBackend }
            activity.recreate()
            instrumentation.waitForIdleSync()
            activity.onActivity { current ->
                assertEquals("Recreation must hydrate saved settings, not emulator defaults", customBackend,
                    edit(current, "Backend URL").text.toString())
            }
            screenshot("product-settings-restored.png")
            click(activity, "Home")
            await("paused after settings recreation") { hasText(activity, "Nearby relay paused") }
            assertFalse(hasText(activity, "Nearby relay available"))
        } finally {
            // Closing first prevents restored Internet flags or phone mode from starting networking.
            scenario?.close()
            mesh.background()
            serialBarrier(mesh)
            mesh.simSelectNode(original.lab.selectedNodeId)
            createdNodes.forEach(mesh::simRemoveNode)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, it.internet) }
            mesh.saveSettings(original.backend, originalKey) {}
            mesh.setRelayEnabled(original.relayEnabled)
            mesh.switchMode(original.simulation)
            serialBarrier(mesh)

            // Keep all Room history, including the explicitly labeled synthetic test report.
            // Its removed virtual node can be inspected through archived simulation history.
            val editor = prefs.edit()
            changedKeys.forEach { editor.remove(it) }
            savedPreferences.forEach { (name, value) ->
                when (value) {
                    is String -> editor.putString(name, value)
                    is Boolean -> editor.putBoolean(name, value)
                    is Int -> editor.putInt(name, value)
                    is Long -> editor.putLong(name, value)
                    is Float -> editor.putFloat(name, value)
                }
            }
            assertTrue("Restore original test-modified preferences", editor.commit())
            mesh.refresh()
            serialBarrier(mesh)
        }
    }

    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(views(root.getChildAt(i)))
    }
    private fun edit(activity: MainActivity, hint: String): EditText = views(activity.window.decorView)
        .filterIsInstance<EditText>().single { it.hint?.toString() == hint }

    private fun click(scenario: ActivityScenario<MainActivity>, title: String) {
        scenario.onActivity { current ->
            val target = views(current.window.decorView).filterIsInstance<Button>().single { it.text.toString() == title }
            assertTrue("Enabled native button: $title", target.isEnabled)
            assertTrue("Native button click: $title", target.performClick())
        }
        instrumentation.waitForIdleSync()
    }
    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        instrumentation.waitForIdleSync()
    }
    private fun generalDialogVisible(): Boolean = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText("Send general SOS?")?.any { it.text?.toString() == "Send general SOS?" } == true

    private fun dialogText(): String {
        fun texts(node: AccessibilityNodeInfo): List<String> = buildList {
            node.text?.toString()?.let(::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(texts(it)) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::texts)?.joinToString("\n").orEmpty()
    }

    private fun assertGeneralDialog(vararg locationDetails: String) {
        instrumentation.waitForIdleSync()
        val content = dialogText()
        listOf("Send general SOS?", QUICK_SOS_MESSAGE,
            "No selected category, typed draft, voice, photo or video is included",
            "Saves on this phone first. Delivery needs a connection.").plus(locationDetails).forEach { detail ->
            assertTrue("General SOS confirmation includes: $detail", content.contains(detail))
        }
    }

    private fun clickDialog(title: String) {
        instrumentation.waitForIdleSync()
        await("dialog action $title") {
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)
                ?.any { it.text?.toString()?.equals(title, ignoreCase = true) == true && it.isClickable } == true
        }
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val target = root.findAccessibilityNodeInfosByText(title)
            .single { it.text?.toString()?.equals(title, ignoreCase = true) == true && it.isClickable }
        assertTrue("Dialog action $title", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun assertReview(scenario: ActivityScenario<MainActivity>, description: String) {
        assertTrue(hasText(scenario, "Review your SOS"))
        for (detail in listOf("Flood", "Cannot move", "Cannot speak", "People injured", "3", "SYNTHETIC TEST BUILDING", "Local test only", "TEST-12", description)) {
            assertTrue("Review includes the actual draft detail: $detail", hasTextContaining(scenario, detail))
        }
    }
    private fun expand(scenario: ActivityScenario<MainActivity>, title: String) {
        var expanded = false
        scenario.onActivity { current -> expanded = views(current.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "$title  −" } }
        if (!expanded) click(scenario, "$title  +")
    }
    private fun hasText(scenario: ActivityScenario<MainActivity>, expected: String): Boolean {
        var found = false
        scenario.onActivity { current -> found = views(current.window.decorView).filterIsInstance<TextView>()
            .any { it.visibility == View.VISIBLE && it.text.toString() == expected } }
        return found
    }
    private fun hasTextContaining(scenario: ActivityScenario<MainActivity>, expected: String): Boolean {
        var found = false
        scenario.onActivity { current -> found = views(current.window.decorView).filterIsInstance<TextView>()
            .any { it.isShown && it.text.toString().contains(expected) } }
        return found
    }
    private fun serialBarrier(mesh: MeshController) {
        val done = CountDownLatch(1)
        mesh.serial.execute { done.countDown() }
        assertTrue("Controller operation completed", done.await(10, TimeUnit.SECONDS))
    }
    private fun await(reason: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out waiting for $reason")
            SystemClock.sleep(50)
        }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // Main-thread idleness can precede the compositor presenting the new screen.
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "product-flow-test").apply { mkdirs() }
            File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
