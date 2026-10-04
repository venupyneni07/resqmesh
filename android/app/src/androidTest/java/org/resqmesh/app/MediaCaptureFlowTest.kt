package org.resqmesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.VideoView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic app-owned files exercise capture preparation, preview and saved SOS without opening a camera or microphone. */
@RunWith(AndroidJUnit4::class)
class MediaCaptureFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun immediateMediaChoicesPreviewPlaybackAndLocalSos() {
        val context = instrumentation.targetContext
        val mesh = (context.applicationContext as MeshApplication).mesh
        await("controller ready") { mesh.state.value.ready }
        val original = mesh.state.value
        val preferences = context.getSharedPreferences("resqmesh", 0)
        val keys = setOf("simulation", "relayEnabled", "backend", "apiKey", "simulationEnvironment", "selectedSim", "savedSosLocation")
        val snapshot = preferences.all.filterKeys { it in keys }
        val originalKey = org.resqmesh.app.data.CredentialStore(context).read()
        var scenario: ActivityScenario<MainActivity>? = null
        val created = mutableSetOf<String>()
        try {
            mesh.background(); mesh.setRelayEnabled(false); mesh.switchMode(true); barrier(mesh)
            original.lab.nodes.filter { it.internet }.forEach { mesh.simSetInternet(it.id, false) }
            mesh.simAddNode(); barrier(mesh)
            created.addAll(mesh.state.value.lab.nodes.map { it.id }.filter { id -> original.lab.nodes.none { it.id == id } })
            assertEquals(1, created.size)
            mesh.simSelectNode(created.single()); barrier(mesh)
            mesh.saveSosLocation(SavedSosLocation(building = "SYNTHETIC MEDIA TEST PLACE", context = org.resqmesh.app.core.LocationContext("manual", System.currentTimeMillis())))
            val activity = ActivityScenario.launch(MainActivity::class.java); scenario = activity
            await("home ready") { hasText(activity, "Record voice") }
            activity.onActivity { current ->
                val all = views(current.window.decorView)
                val navigation = all.filterIsInstance<Button>().single { it.text == "Home" }
                val navigationRect = Rect(); navigation.getGlobalVisibleRect(navigationRect)
                for (title in listOf("Record voice", "Take photo", "Record video")) {
                    val button = all.filterIsInstance<Button>().single { it.text == title }
                    val rect = Rect()
                    assertTrue("$title is visible immediately", button.getGlobalVisibleRect(rect))
                    assertTrue("$title fits above navigation without scrolling", rect.bottom <= navigationRect.top)
                }
            }
            screenshot("media-home.png")

            // Directly exercise the permission-denied callback. Never request or capture ambient audio in tests.
            activity.onActivity { current ->
                assertTrue(current.mediaCapture.selectCapture("audio", needsPermission = true))
                current.reviewMediaDraft()
                current.onRequestPermissionsResult(72, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(PackageManager.PERMISSION_DENIED))
            }
            await("permission fallback") { contains(activity, "Microphone access was not allowed") }
            assertTrue(hasText(activity, "Send SOS without media"))
            screenshot("media-permission-fallback.png")
            click(activity, "Home")

            val assets = listOf(Triple("image", "synthetic-photo.jpg", "photo"), Triple("audio", "synthetic-audio.m4a", "voice"), Triple("video", "synthetic-video.mp4", "video"))
            for ((kind, asset, label) in assets) {
                val file = File(File(context.filesDir, "media-drafts").apply { mkdirs() }, "${UUID.randomUUID()}.${asset.substringAfterLast('.')}")
                instrumentation.context.assets.open("media/$asset").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                val before = mesh.state.value.reports.map { it.report.id }.toSet()
                activity.onActivity { current -> current.mediaCapture.finishFile(file, kind); current.reviewMediaDraft() }
                await("$label ready") { hasText(activity, "Ready to send") }
                assertTrue(hasText(activity, "Send $label SOS"))
                assertFalse("Media does not force category selection", hasText(activity, "What help do you need?"))
                assertEquals("Preview creates no report", before, mesh.state.value.reports.map { it.report.id }.toSet())
                activity.onActivity { current ->
                    val attachment = current.mediaCapture.state.value.attachment!!
                    assertEquals(kind, attachment.metadata.kind)
                    assertTrue(File(attachment.filePath).isFile)
                    assertEquals(64, attachment.metadata.sha256.length)
                    assertEquals(File(attachment.filePath).length(), attachment.metadata.byteSize)
                    if (kind == "image") {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(attachment.filePath, bounds)
                        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 1280)
                    } else assertTrue(attachment.metadata.durationMs!! > 0)
                }
                activity.recreate(); instrumentation.waitForIdleSync()
                await("$label restored") { hasText(activity, "Send $label SOS") }
                assertTrue("Capture location preserved", contains(activity, "SYNTHETIC MEDIA TEST PLACE"))
                screenshot("media-$label-ready.png")
                if (kind == "audio") {
                    click(activity, "Play voice message")
                    assertTrue("Real encoded audio starts playback", hasText(activity, "Stop playback"))
                } else if (kind == "video") {
                    click(activity, "Play video")
                    await("encoded video playback") {
                        var playing = false
                        activity.onActivity { current -> playing = views(current.window.decorView).filterIsInstance<VideoView>().any { it.isPlaying } }
                        playing
                    }
                }
                activity.onActivity { current ->
                    val send = views(current.window.decorView).filterIsInstance<Button>().single { it.text == "Send $label SOS" }
                    send.performClick(); send.performClick()
                }
                activity.recreate()
                await("$label report saved") { mesh.state.value.reports.any { it.report.id !in before } }
                await("$label journey") { hasText(activity, "SOS delivery") }
                val report = mesh.state.value.reports.single { it.report.id !in before }.report
                assertEquals("preset", report.messageSource)
                assertEquals("other", report.emergencyType)
                assertEquals(1, report.attachments.size)
                assertEquals(kind, report.attachments.single().kind)
                val evidence = mesh.state.value.media.single { it.reportId == report.id }
                assertTrue(evidence.localAvailable)
                assertFalse(evidence.backendReceived)
                assertNull("Saved-report snapshots do not eagerly decrypt media", evidence.filePath)
                val storedBlob = File(context.filesDir, "media").walkTopDown().single {
                    it.name == "complete.bin" && it.path.contains("/${report.id}/")
                }
                assertTrue("Durable media is encrypted", org.resqmesh.app.data.LocalCipher.isEncrypted(storedBlob.readBytes()))
                val opened = CountDownLatch(1)
                var previewPath: String? = null
                activity.onActivity {
                    mesh.requestMediaPreview(report.id, report.attachments.single().id) { path -> previewPath = path; opened.countDown() }
                }
                assertTrue("Explicit preview request completes", opened.await(10, TimeUnit.SECONDS))
                val preview = File(requireNotNull(previewPath))
                assertTrue(preview.isFile)
                assertTrue("Decrypted preview stays in private cache", preview.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
                assertEquals(report.attachments.single().byteSize, preview.length())
                val previewHash = java.security.MessageDigest.getInstance("SHA-256").digest(preview.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(report.attachments.single().sha256, previewHash)
                await("saved attachment preview is published") { mesh.state.value.media.single { it.reportId == report.id }.filePath == previewPath }
                if (kind == "audio") {
                    await("saved voice playback control") { hasText(activity, "Play voice message") }
                    click(activity, "Play voice message")
                    assertTrue("Saved encrypted voice can be played", hasText(activity, "Stop playback"))
                } else if (kind == "video") {
                    await("saved video playback control") { hasText(activity, "Play video") }
                    click(activity, "Play video")
                    await("saved encrypted video plays") {
                        var playing = false
                        activity.onActivity { current -> playing = views(current.window.decorView).filterIsInstance<VideoView>().any { it.isPlaying } }
                        playing
                    }
                }
                assertTrue(contains(activity, "Saved here · attachment delivery unconfirmed"))
                screenshot("media-$label-saved.png")
                click(activity, "Home")
            }

            // Missing files fail visibly without producing a report, preserving the no-media escape route.
            val beforeMissing = mesh.state.value.reports.map { it.report.id }.toSet()
            activity.onActivity { current ->
                current.mediaCapture.finishFile(File(context.filesDir, "media-drafts/${UUID.randomUUID()}.m4a"), "audio")
                current.reviewMediaDraft()
            }
            await("missing media message") { contains(activity, "No captured media was saved") }
            assertTrue(hasText(activity, "Send SOS without media"))
            assertEquals(beforeMissing, mesh.state.value.reports.map { it.report.id }.toSet())
            screenshot("media-missing-file-fallback.png")
        } finally {
            scenario?.close(); mesh.background(); barrier(mesh)
            mesh.simSelectNode(original.lab.selectedNodeId)
            created.forEach(mesh::simRemoveNode)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, it.internet) }
            mesh.saveSettings(original.backend, originalKey) {}
            mesh.setRelayEnabled(original.relayEnabled); mesh.switchMode(original.simulation); barrier(mesh)
            val editor = preferences.edit(); keys.forEach(editor::remove)
            snapshot.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value); is Int -> editor.putInt(key, value); is Float -> editor.putFloat(key, value)
            } }
            assertTrue(editor.commit()); mesh.refresh(); barrier(mesh)
        }
    }

    private fun views(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun click(activity: ActivityScenario<MainActivity>, title: String) {
        activity.onActivity { current -> val button = views(current.window.decorView).filterIsInstance<Button>().single { it.text == title }
            assertTrue(button.isEnabled); assertTrue(button.performClick()) }
        instrumentation.waitForIdleSync()
    }
    private fun hasText(activity: ActivityScenario<MainActivity>, value: String): Boolean {
        var present = false; activity.onActivity { current -> present = views(current.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text == value } }; return present
    }
    private fun contains(activity: ActivityScenario<MainActivity>, value: String): Boolean {
        var present = false; activity.onActivity { current -> present = views(current.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains(value) } }; return present
    }
    private fun await(reason: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (!check()) { if (SystemClock.elapsedRealtime() >= until) fail("Timed out: $reason"); SystemClock.sleep(40) }
    }
    private fun barrier(mesh: MeshController) { val latch = CountDownLatch(1); mesh.serial.execute { latch.countDown() }; assertTrue(latch.await(10, TimeUnit.SECONDS)) }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "media-flow-test").apply { mkdirs() }
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
