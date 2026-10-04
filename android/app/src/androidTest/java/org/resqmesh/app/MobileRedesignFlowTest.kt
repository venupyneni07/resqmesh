package org.resqmesh.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.resqmesh.app.core.DeliveryStatus
import org.resqmesh.app.core.LocationContext
import org.resqmesh.app.data.MeshDatabase
import org.resqmesh.app.data.RoomReportStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Local UI regression fixtures only: 12 explicitly synthetic reports on one isolated virtual node.
 * Four UPLOADED states are seeded directly for filter coverage; they do not claim a real upload.
 * The camera/microphone are never opened. The photo fixture is generated color bars.
 */
@RunWith(AndroidJUnit4::class)
class MobileRedesignFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun boundedReportsDraftsAndMediaLocationRemainUsableOffline() {
        val context = instrumentation.targetContext
        val mesh = (context.applicationContext as MeshApplication).mesh
        await("controller ready") { mesh.state.value.ready }
        val original = mesh.state.value
        val credentialBefore = org.resqmesh.app.data.CredentialStore(context).read()
        val prefs = context.getSharedPreferences("resqmesh", 0)
        val preferencesBefore = prefs.all.toMap()
        val db = Room.databaseBuilder(context, MeshDatabase::class.java, "resqmesh.db")
            .addMigrations(MeshDatabase.MIGRATION_1_2).build()
        val store = RoomReportStore(db.dao())
        val reportsBefore = db.dao().allReports().associate { (it.nodeId to it.reportId) to it.json }
        val createdNodes = linkedSetOf<String>()
        val expectedIds = linkedSetOf<String>()
        val uploadedIds = linkedSetOf<String>()
        var scenario: ActivityScenario<MainActivity>? = null
        var fixturePath: File? = null
        val oldLocation = SavedSosLocation(building = "SYNTHETIC REDESIGN TEST PLACE",
            context = LocationContext("manual", System.currentTimeMillis() - 7_200_000))

        try {
            mesh.background(); mesh.setRelayEnabled(false); mesh.switchMode(true); barrier(mesh)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, false) }
            mesh.simAddNode(); barrier(mesh)
            createdNodes.addAll(mesh.state.value.lab.nodes.map { it.id }.filter { id -> original.lab.nodes.none { it.id == id } })
            assertEquals("Only one virtual fixture node is added", 1, createdNodes.size)
            val source = createdNodes.single()
            mesh.simSelectNode(source); mesh.simSetInternet(source, false); barrier(mesh)
            assertTrue(mesh.state.value.lab.links.none { source == it.first || source == it.second })
            assertTrue(mesh.saveSosLocation(oldLocation))

            repeat(12) { index ->
                val completed = CountDownLatch(1)
                var createdId: String? = null
                var failure: String? = null
                mesh.create(SosDraft(text = "SYNTHETIC REDESIGN FIXTURE ${index + 1}/12. Local interface test; no real emergency or delivery.",
                    emergencyType = if (index % 2 == 0) "medical" else "flood",
                    building = "SYNTHETIC TEST ${index + 1}")) { id, error ->
                    createdId = id; failure = error; completed.countDown()
                }
                assertTrue("Synthetic report saved", completed.await(10, TimeUnit.SECONDS))
                assertNull(failure)
                val id = requireNotNull(createdId); expectedIds.add(id)
                if (index < 4) {
                    // Explicit local fixture state, with no fabricated backend receipt and no HTTP.
                    store.save(requireNotNull(store.get(source, id)).copy(status = DeliveryStatus.UPLOADED))
                    uploadedIds.add(id)
                }
            }
            mesh.refresh(); barrier(mesh)
            assertEquals(expectedIds, mesh.state.value.reports.map { it.report.id }.toSet())
            assertTrue("Synthetic upload states must not manufacture receipts", mesh.state.value.receipts.isEmpty())

            val activity = ActivityScenario.launch(MainActivity::class.java); scenario = activity
            await("four primary capture choices") { hasText(activity, "Choose help") }
            activity.onActivity { current ->
                val nav = action(current, "Home")
                val navBounds = Rect(); assertTrue(nav.getGlobalVisibleRect(navBounds))
                for (title in listOf("Record voice", "Take photo", "Record video", "Choose help")) {
                    val control = action(current, title)
                    val bounds = Rect()
                    assertTrue("$title is visible immediately", control.getGlobalVisibleRect(bounds))
                    assertEquals("$title is not clipped vertically", control.height, bounds.height())
                    assertTrue("$title fits above navigation", bounds.bottom <= navBounds.top)
                }
                for (title in listOf("Home", "Reports", "Network", "Settings")) assertTrue(action(current, title).isShown)
            }
            assertEquals("Home remains compact with twelve stored reports", 1, reportIds(activity).size)
            assertTrue(expectedIds.containsAll(reportIds(activity)))
            screenshot("redesign-home-with-twelve-reports.png")

            click(activity, "Reports")
            click(activity, "All reports")
            val allPages = collectPages(activity)
            assertEquals("Every synthetic report is reachable exactly once across pages", expectedIds, allPages.toSet())
            assertEquals("Pagination must not duplicate a report", 12, allPages.size)
            click(activity, "Needs attention")
            val attentionPages = collectPages(activity)
            assertEquals("Needs attention excludes only the seeded uploaded fixtures", expectedIds - uploadedIds, attentionPages.toSet())
            assertEquals(8, attentionPages.size)
            click(activity, "All reports")
            assertEquals("Changing filter returns to the first five reports", 5, reportIds(activity).size)
            screenshot("redesign-reports-first-page.png")

            click(activity, "Home")
            click(activity, "View delivery status")
            assertTrue(hasText(activity, "SOS delivery"))
            assertFalse("Technical routes/history stay collapsed", hasTag(activity, "delivery-technical-details"))
            clickStarting(activity, "Delivery details")
            assertTrue("Technical evidence remains available on demand", hasTag(activity, "delivery-technical-details"))
            clickStarting(activity, "Delivery details")
            assertFalse(hasTag(activity, "delivery-technical-details"))
            screenshot("redesign-delivery-summary.png")

            click(activity, "Home")
            click(activity, "Choose help")
            click(activity, "Flood")
            clickStarting(activity, "Write a message")
            val draftText = "SYNTHETIC UNSENT REDESIGN DRAFT ${UUID.randomUUID()}"
            activity.onActivity { current -> edit(current, "Describe the emergency").setText(draftText) }
            click(activity, "Home")
            assertTrue("Unsent details can be resumed from Home", hasText(activity, "Resume draft"))
            click(activity, "Network"); click(activity, "Reports"); click(activity, "Settings"); click(activity, "Home")
            click(activity, "Resume draft")
            activity.recreate(); instrumentation.waitForIdleSync()
            activity.onActivity { current ->
                assertEquals(draftText, edit(current, "Describe the emergency").text.toString())
                assertTrue("Explicit category survives navigation/recreation", action(current, "Flood").isSelected)
            }
            assertEquals("Navigation and editing must not send anything", expectedIds, mesh.state.value.reports.map { it.report.id }.toSet())
            click(activity, "Home")
            click(activity, "Discard draft")
            clickDialog("Keep draft")
            click(activity, "Resume draft")
            activity.onActivity { current -> assertEquals(draftText, edit(current, "Describe the emergency").text.toString()) }
            click(activity, "Home"); click(activity, "Discard draft"); clickDialog("Discard")
            assertFalse("Confirmed discard removes the draft affordance", hasText(activity, "Resume draft"))
            assertEquals(expectedIds, mesh.state.value.reports.map { it.report.id }.toSet())

            // A generated photo exercises editing around a real private file, without opening a camera.
            val copied = File(File(context.filesDir, "media-drafts").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
            fixturePath = copied
            instrumentation.context.assets.open("media/synthetic-photo.jpg").use { input -> copied.outputStream().use { input.copyTo(it) } }
            activity.onActivity { current -> current.mediaCapture.finishFile(copied, "image"); current.reviewMediaDraft() }
            await("photo ready") { hasText(activity, "Send photo SOS") }
            var ready: DraftAttachment? = null
            activity.onActivity { ready = it.mediaCapture.state.value.attachment }
            val originalCapture = requireNotNull(ready)
            assertTrue(contains(activity, oldLocation.building))
            click(activity, "Change location")
            val mediaLandmark = "SYNTHETIC MEDIA LOCATION CHANGE"
            activity.onActivity { current ->
                val inputs = views(current.window.decorView).filterIsInstance<EditText>().filter { it.isShown }
                assertEquals("A concise landmark editor", 1, inputs.size)
                inputs.single().setText(mediaLandmark)
            }
            activity.recreate(); instrumentation.waitForIdleSync()
            activity.onActivity { current ->
                assertEquals("An unapplied location edit survives recreation", mediaLandmark,
                    views(current.window.decorView).filterIsInstance<EditText>().single { it.isShown }.text.toString())
                assertEquals("Editing location does not replace the private capture", originalCapture.metadata.id,
                    current.mediaCapture.state.value.attachment?.metadata?.id)
            }
            click(activity, "Use this location")
            assertTrue(contains(activity, mediaLandmark))
            assertEquals("Media location edits do not overwrite the user's saved place", oldLocation.building, mesh.savedSosLocation()?.building)
            activity.recreate(); instrumentation.waitForIdleSync()
            await("photo remains ready after location edit") { hasText(activity, "Send photo SOS") }
            activity.onActivity { current ->
                val restored = requireNotNull(current.mediaCapture.state.value.attachment)
                assertEquals(originalCapture.metadata, restored.metadata)
                assertEquals(originalCapture.filePath, restored.filePath)
                assertTrue(File(restored.filePath).isFile)
            }
            assertTrue(contains(activity, mediaLandmark))
            screenshot("redesign-media-location-edited.png")
            click(activity, "Change location"); click(activity, "Continue without location")
            assertTrue("Unknown location never removes the capture", hasText(activity, "Send photo SOS"))
            assertFalse("An omitted location does not keep the old landmark on screen", contains(activity, mediaLandmark))
            assertEquals("Editing capture context never sends a report", expectedIds, mesh.state.value.reports.map { it.report.id }.toSet())
            activity.onActivity { current -> assertEquals(originalCapture.metadata.id, current.mediaCapture.state.value.attachment?.metadata?.id) }
            screenshot("redesign-media-unknown-location.png")

            // Home location preferences are explicit and separate from a media-only location change.
            click(activity, "Home"); click(activity, "Change location")
            val homeLandmark = "SYNTHETIC HOME LOCATION CHANGE"
            activity.onActivity { current -> views(current.window.decorView).filterIsInstance<EditText>().single { it.isShown }.setText(homeLandmark) }
            click(activity, "Use this location")
            val changedHome = requireNotNull(mesh.savedSosLocation())
            assertTrue(listOf(changedHome.building, changedHome.locationText).contains(homeLandmark))
            assertTrue(contains(activity, homeLandmark))
        } finally {
            scenario?.onActivity { it.mediaCapture.discard() }
            scenario?.close(); mesh.background(); barrier(mesh)
            fixturePath?.delete()
            mesh.simSelectNode(original.lab.selectedNodeId)
            createdNodes.forEach(mesh::simRemoveNode)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, it.internet) }
            val settingsRestored = CountDownLatch(1)
            mesh.saveSettings(original.backend, credentialBefore) { settingsRestored.countDown() }
            assertTrue(settingsRestored.await(10, TimeUnit.SECONDS))
            mesh.setRelayEnabled(original.relayEnabled); mesh.switchMode(original.simulation); barrier(mesh)
            val editor = prefs.edit().clear()
            preferencesBefore.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue("Original app preferences restored", editor.commit())
            mesh.refresh(); barrier(mesh)
            assertEquals("No preference remains changed by the fixture", preferencesBefore, prefs.all)
            val after = db.dao().allReports().associate { (it.nodeId to it.reportId) to it.json }
            reportsBefore.forEach { (key, packet) -> assertEquals("Existing report payload is preserved", packet, after[key]) }
            db.close()
        }
    }

    private fun collectPages(activity: ActivityScenario<MainActivity>): List<String> {
        val collected = mutableListOf<String>()
        repeat(10) {
            val page = reportIds(activity)
            assertTrue("A report page contains at most five rows", page.size in 1..5)
            assertTrue("Pages cannot repeat a row", page.none(collected::contains))
            collected.addAll(page)
            var nextEnabled = false
            activity.onActivity { current -> nextEnabled = findAction(current, "Next")?.isEnabled == true }
            if (!nextEnabled) return collected
            click(activity, "Next")
        }
        fail("Pagination did not terminate within ten pages")
        return collected
    }

    private fun reportIds(activity: ActivityScenario<MainActivity>): List<String> {
        var ids = emptyList<String>()
        activity.onActivity { current -> ids = views(current.window.decorView).mapNotNull { view ->
            (view.tag as? String)?.takeIf { view.isShown && it.startsWith("sos-report:") }?.removePrefix("sos-report:")
        } }
        return ids
    }
    private fun views(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun findAction(activity: MainActivity, title: String): View? = views(activity.window.decorView)
        .filterIsInstance<TextView>().filter { it.isShown && it.text.toString() == title }
        .mapNotNull { text -> generateSequence<View>(text) { it.parent as? View }.firstOrNull { it.isClickable } }
        .distinct().singleOrNull()
    private fun action(activity: MainActivity, title: String): View = requireNotNull(findAction(activity, title)) { "Missing or ambiguous action: $title" }
    private fun click(activity: ActivityScenario<MainActivity>, title: String) {
        activity.onActivity { current -> val target = action(current, title); assertTrue("$title is enabled", target.isEnabled); assertTrue(target.performClick()) }
        instrumentation.waitForIdleSync()
    }
    private fun clickStarting(activity: ActivityScenario<MainActivity>, prefix: String) {
        activity.onActivity { current ->
            val target = views(current.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text.toString().startsWith(prefix) }
            assertTrue(target.isEnabled); assertTrue(target.performClick())
        }
        instrumentation.waitForIdleSync()
    }
    private fun clickDialog(title: String) {
        instrumentation.waitForIdleSync()
        await("dialog action $title") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            root.findAccessibilityNodeInfosByText(title).any { it.text?.toString()?.equals(title, ignoreCase = true) == true && it.isClickable }
        }
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val target = root.findAccessibilityNodeInfosByText(title).single { it.text?.toString()?.equals(title, ignoreCase = true) == true && it.isClickable }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync()
    }
    private fun edit(activity: MainActivity, hint: String): EditText = views(activity.window.decorView).filterIsInstance<EditText>().single { it.hint?.toString() == hint }
    private fun hasText(activity: ActivityScenario<MainActivity>, value: String): Boolean {
        var result = false
        activity.onActivity { current -> result = views(current.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == value } }
        return result
    }
    private fun contains(activity: ActivityScenario<MainActivity>, value: String): Boolean {
        var result = false
        activity.onActivity { current -> result = views(current.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains(value) } }
        return result
    }
    private fun hasTag(activity: ActivityScenario<MainActivity>, value: String): Boolean {
        var result = false
        activity.onActivity { current -> result = views(current.window.decorView).any { it.isShown && it.tag == value } }
        return result
    }
    private fun await(reason: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (!condition()) { if (SystemClock.elapsedRealtime() >= until) fail("Timed out: $reason"); SystemClock.sleep(40) }
    }
    private fun barrier(mesh: MeshController) {
        val done = CountDownLatch(1); mesh.serial.execute { done.countDown() }; assertTrue(done.await(10, TimeUnit.SECONDS))
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "mobile-redesign-test").apply { mkdirs() }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
