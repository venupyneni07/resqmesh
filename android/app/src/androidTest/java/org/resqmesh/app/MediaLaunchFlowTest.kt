package org.resqmesh.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises real Home camera dispatch and ActivityResult handling with intercepted camera intents.
 * Only generated test media is written to the requested FileProvider URI. No camera or microphone
 * hardware is opened; voice coverage is limited to the selected permission-request state.
 */
@RunWith(AndroidJUnit4::class)
class MediaLaunchFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private data class CameraRequest(val action: String, val kind: String?, val phase: CapturePhase, val notice: String?)

    @Test fun homeDispatchesSelectedCameraAndIgnoresStaleResultsWithoutSending() {
        val mesh = (context.applicationContext as MeshApplication).mesh
        await("controller ready") { mesh.state.value.ready }
        barrier(mesh)
        val originalReports = reportIds()
        val preferences = context.getSharedPreferences("resqmesh", 0)
        val originalPreferences = preferences.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        val requests = CopyOnWriteArrayList<CameraRequest>()
        val fixture = AtomicReference<String?>(null)
        var currentActivity: MainActivity? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var ownsCapture = false
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                val action = intent.action ?: return null
                if (action != MediaStore.ACTION_IMAGE_CAPTURE && action != MediaStore.ACTION_VIDEO_CAPTURE) return null
                val current = requireNotNull(currentActivity)
                val state = current.mediaCapture.state.value
                requests += CameraRequest(action, state.kind, state.phase, state.notice)
                assertEquals("Camera selection is committed before dispatch", CapturePhase.WAITING_CAMERA, state.phase)
                assertEquals(if (action == MediaStore.ACTION_IMAGE_CAPTURE) "image" else "video", state.kind)
                assertNull("A new camera launch clears the previous failure", state.notice)
                assertFalse("Camera launch never starts audio recording", state.recording)
                @Suppress("DEPRECATION") val output = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
                requireNotNull(output) { "Camera receives an output URI" }
                assertEquals("content", output.scheme)
                assertEquals("${context.packageName}.capture", output.authority)
                assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                val asset = fixture.getAndSet(null) ?: return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                instrumentation.context.assets.open("media/$asset").use { source ->
                    requireNotNull(context.contentResolver.openOutputStream(output)).use { target -> source.copyTo(target) }
                }
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val activity = ActivityScenario.launch(MainActivity::class.java)
            scenario = activity
            activity.onActivity { current ->
                currentActivity = current
                assertEquals("Test begins without a capture draft", CapturePhase.IDLE, current.mediaCapture.state.value.phase)
            }
            ownsCapture = true
            await("Home media actions") { hasText(activity, "Take photo") }

            activity.onActivity { current ->
                val photo = views(current.window.decorView).filterIsInstance<Button>().single { it.text == "Take photo" }
                assertTrue(photo.performClick())
                photo.performClick()
            }
            await("photo cancellation") { hasText(activity, "No photo captured") }
            assertEquals("A first tap launches exactly one camera despite a duplicate tap", 1, requests.size)
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, requests.single().action)
            assertSelectedFallback(activity, "Try photo again")
            screenshot("01-photo-cancelled.png")

            click(activity, "Choose another method")
            assertTrue(hasText(activity, "Need help?"))
            click(activity, "Record video")
            await("video cancellation") { hasText(activity, "No video captured") }
            assertEquals(2, requests.size)
            assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, requests.last().action)
            assertSelectedFallback(activity, "Try video again")
            screenshot("02-video-cancelled.png")

            fixture.set("synthetic-video.mp4")
            click(activity, "Try video again")
            await("intercepted camera video prepared") { hasText(activity, "Ready to send") }
            assertEquals(3, requests.size)
            assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, requests.last().action)
            assertTrue(hasText(activity, "Send video SOS"))
            activity.onActivity { current ->
                val ready = current.mediaCapture.state.value
                assertEquals(CapturePhase.READY, ready.phase)
                assertEquals("video", ready.attachment?.metadata?.kind)
                assertTrue(File(requireNotNull(ready.attachment).filePath).isFile)
                current.mediaCapture.cameraFinished(false)
                assertEquals("A late cancelled camera result cannot erase ready media", ready, current.mediaCapture.state.value)
                assertFalse("A ready attachment blocks a new capture", current.mediaCapture.selectCapture("image"))
                assertEquals(ready, current.mediaCapture.state.value)
            }
            screenshot("03-video-result-ready.png")
            click(activity, "Discard capture")

            // Select the voice permission state directly: do not request permission or start a mic.
            activity.onActivity { current ->
                assertTrue(current.mediaCapture.selectCapture("audio", needsPermission = true))
                current.reviewMediaDraft()
                val selected = current.mediaCapture.state.value
                assertEquals(CapturePhase.REQUESTING_PERMISSION, selected.phase)
                assertNull(selected.notice)
                current.mediaCapture.cameraFinished(false)
                assertEquals("A stale camera result cannot overwrite a new microphone request", selected, current.mediaCapture.state.value)
                assertFalse("A pending permission request blocks duplicate selection", current.mediaCapture.selectCapture("audio", needsPermission = true))
                assertFalse("A pending permission request blocks other capture starts", current.mediaCapture.selectCapture("video"))
                assertFalse(current.mediaCapture.state.value.recording)
            }
            await("selected microphone permission UI") { hasText(activity, "Microphone permission") }
            assertFalse(contains(activity, "Capture cancelled"))
            screenshot("04-voice-permission-state.png")
            activity.onActivity { current -> current.mediaCapture.cancelPermissionRequest(); current.reviewMediaDraft() }
            await("voice cancellation") { hasText(activity, "Voice recording unavailable") }
            assertSelectedFallback(activity, "Try voice again")
            screenshot("05-voice-cancelled.png")

            click(activity, "Choose another method")
            fixture.set("synthetic-photo.jpg")
            click(activity, "Take photo")
            await("intercepted camera photo prepared") { hasText(activity, "Ready to send") }
            assertEquals(4, requests.size)
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, requests.last().action)
            assertTrue(hasText(activity, "Send photo SOS"))
            activity.onActivity { current ->
                val ready = current.mediaCapture.state.value
                assertEquals(CapturePhase.READY, ready.phase)
                assertEquals("image", ready.attachment?.metadata?.kind)
                assertNull(ready.notice)
                current.mediaCapture.cameraFinished(false)
                assertEquals(ready, current.mediaCapture.state.value)
            }
            screenshot("06-photo-result-ready.png")
            click(activity, "Discard capture")
            assertTrue(hasText(activity, "Need help?"))
        } finally {
            if (ownsCapture) scenario?.onActivity { it.mediaCapture.discard() }
            scenario?.close()
            instrumentation.removeMonitor(monitor)
            barrier(mesh)
            val reportsUnchanged = originalReports == reportIds()
            val preferencesUnchanged = originalPreferences == preferences.all
            val directory = File(context.getExternalFilesDir(null), "media-launch-test").apply { mkdirs() }
            File(directory, "verification.json").writeText(JSONObject()
                .put("camera_dispatches", JSONArray(requests.map { request -> JSONObject()
                    .put("action", request.action).put("kind", request.kind).put("phase", request.phase.name)
                    .put("stale_notice_cleared", request.notice == null) }))
                .put("reports_unchanged", reportsUnchanged).put("preferences_unchanged", preferencesUnchanged)
                .put("camera_results", "Instrumentation interception with explicit cancellations and generated test files")
                .put("microphone_opened", false).put("camera_hardware_opened", false).toString(2))
            assertTrue("Selecting, cancelling, retrying or previewing media sends no SOS", reportsUnchanged)
            assertTrue("Media launch test preserves saved settings", preferencesUnchanged)
        }
    }

    private fun assertSelectedFallback(activity: ActivityScenario<MainActivity>, retry: String) {
        assertTrue(hasText(activity, retry))
        assertTrue(hasText(activity, "Choose another method"))
        assertTrue(hasText(activity, "Send SOS without media"))
        for (title in listOf("Record voice", "Take photo", "Record video"))
            assertFalse("Fallback must not ask the user to select a method again: $title", hasText(activity, title))
    }
    private fun views(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (index in 0 until view.childCount) addAll(views(view.getChildAt(index)))
    }
    private fun click(activity: ActivityScenario<MainActivity>, title: String) {
        activity.onActivity { current ->
            val button = views(current.window.decorView).filterIsInstance<Button>().single { it.text == title }
            assertTrue(button.isEnabled); assertTrue(button.performClick())
        }
        instrumentation.waitForIdleSync()
    }
    private fun hasText(activity: ActivityScenario<MainActivity>, text: String): Boolean {
        var result = false
        activity.onActivity { current -> result = views(current.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text == text } }
        return result
    }
    private fun contains(activity: ActivityScenario<MainActivity>, text: String): Boolean {
        var result = false
        activity.onActivity { current -> result = views(current.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains(text) } }
        return result
    }
    private fun reportIds(): Set<String> = SQLiteDatabase.openDatabase(context.getDatabasePath("resqmesh.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT DISTINCT reportId FROM reports", null).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }
    private fun await(reason: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (!predicate()) { if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out: $reason"); SystemClock.sleep(40) }
    }
    private fun barrier(mesh: MeshController) {
        val done = CountDownLatch(1); mesh.serial.execute { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Native screenshot unavailable")
        try {
            val directory = File(context.getExternalFilesDir(null), "media-launch-test").apply { mkdirs() }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
