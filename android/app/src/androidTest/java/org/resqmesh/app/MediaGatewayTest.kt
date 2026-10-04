package org.resqmesh.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.resqmesh.app.data.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real files through virtual peers and actual HTTP; no microphone, camera, or peer radios used. */
@RunWith(AndroidJUnit4::class)
class MediaGatewayTest {
    @Test fun capturedMediaSurvivesOfflineRelayAndUploadsIndependently() {
        val i = InstrumentationRegistry.getInstrumentation()
        val backend = InstrumentationRegistry.getArguments().getString("liveBackend")
        assumeTrue("Opt in with an isolated liveBackend", backend != null)
        val context = i.targetContext
        val mesh = (context.applicationContext as MeshApplication).mesh
        await(10_000) { mesh.state.value.ready }
        val original = mesh.state.value
        val credentialBefore = org.resqmesh.app.data.CredentialStore(context).read()
        val prefs = context.getSharedPreferences("resqmesh", 0)
        val keys = setOf("simulation", "relayEnabled", "backend", "apiKey", "simulationEnvironment", "selectedSim")
        val savedPrefs = prefs.all.filterKeys { it in keys }
        val db = Room.databaseBuilder(context, MeshDatabase::class.java, "resqmesh.db").addMigrations(MeshDatabase.MIGRATION_1_2).build()
        val reports = RoomReportStore(db.dao())
        val files = FileMediaStore(File(context.filesDir, "media"))
        val nodes = mutableListOf<String>()
        var activity: android.app.Activity? = null
        try {
            mesh.background(); mesh.setRelayEnabled(false); mesh.switchMode(true); barrier(mesh)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, false) }
            val configured = CountDownLatch(1)
            mesh.saveSettings(backend!!, "") { error -> assertNull(error); configured.countDown() }
            assertTrue(configured.await(10, TimeUnit.SECONDS)); barrier(mesh)
            fun newNode(): String {
                val before = mesh.state.value.lab.nodes.map { it.id }.toSet()
                mesh.simAddNode(); barrier(mesh)
                return mesh.state.value.lab.nodes.single { it.id !in before }.id.also(nodes::add)
            }
            val origin = newNode(); val relay = newNode(); val gateway = newNode()
            mesh.simSelectNode(origin); barrier(mesh)
            val fixtures = listOf("audio" to "synthetic-audio.m4a", "image" to "synthetic-photo.jpg", "video" to "synthetic-video.mp4")
            val drafts = fixtures.map { (kind, name) ->
                val file = File(File(context.filesDir, "media-drafts").apply { mkdirs() }, "${UUID.randomUUID()}.${name.substringAfterLast('.')}")
                i.context.assets.open("media/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                MediaCaptureViewModel.prepareAttachment(file, kind)
            }
            val done = CountDownLatch(1); val id = AtomicReference<String?>(); val error = AtomicReference<String?>()
            mesh.create(SosDraft(text = "SYNTHETIC MEDIA TEST ONLY. Generated color bars and a tone; no real emergency.", attachments = drafts)) { reportId, failure ->
                id.set(reportId); error.set(failure); done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertNull(error.get()); val reportId = id.get()!!
            val originalReport = reports.get(origin, reportId)!!.report
            assertEquals(4, originalReport.schemaVersion); assertEquals(3, originalReport.attachments.size)
            assertTrue(mesh.state.value.media.all { it.localAvailable && !it.backendReceived })
            // Origin's durable copies survive loss of the capture draft files.
            drafts.forEach { assertTrue(File(it.filePath).delete()) }
            originalReport.attachments.forEach { assertTrue(files.available(origin, reportId, it)) }
            activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            mesh.setRelayEnabled(true); mesh.simSetLink(origin, relay, true)
            await(20_000) { reports.get(relay, reportId) != null && originalReport.attachments.all { files.available(relay, reportId, it) } }
            assertEquals(listOf(origin, relay), reports.get(relay, reportId)!!.report.relayPath)
            mesh.simSetLink(origin, relay, false); barrier(mesh)
            mesh.simSetLink(relay, gateway, true)
            await(20_000) { reports.get(gateway, reportId) != null && originalReport.attachments.all { files.available(gateway, reportId, it) } }
            assertEquals(listOf(origin, relay, gateway), reports.get(gateway, reportId)!!.report.relayPath)
            originalReport.attachments.forEach { attachment ->
                assertEquals(attachment.sha256, sha(files.file(gateway, reportId, attachment)!!.readBytes()))
                assertFalse(files.uploaded(origin, reportId, attachment, backend))
            }
            mesh.simSetInternet(gateway, true)
            await(90_000) { originalReport.attachments.all { files.uploaded(gateway, reportId, it, backend) } }
            val status = request("$backend/api/reports/$reportId/attachments")
            val availability = JSONObject(status).getJSONArray("attachments")
            assertEquals(3, availability.length())
            for (j in 0 until availability.length()) assertEquals("available", availability.getJSONObject(j).getString("status"))
            originalReport.attachments.forEach { attachment ->
                val connection = URL("$backend/api/reports/$reportId/attachments/${attachment.id}").openConnection() as HttpURLConnection
                try {
                    assertEquals(200, connection.responseCode)
                    assertTrue(connection.contentType.startsWith(attachment.mimeType))
                    val bytes = connection.inputStream.use { it.readBytes() }
                    assertEquals(attachment.byteSize, bytes.size.toLong()); assertEquals(attachment.sha256, sha(bytes))
                } finally { connection.disconnect() }
            }
            assertTrue("Partitioned source does not infer a remote SOS receipt", reports.receipts(origin).isEmpty())
            assertTrue("Media upload is not inferred from another virtual node", mesh.state.value.media.all { !it.backendReceived })
            mesh.simSetLink(origin, relay, true)
            await(15_000) { reports.receipts(origin).any { it.receipt.reportId == reportId } }
            assertTrue("An alert receipt does not claim attachment confirmation", mesh.state.value.media.all { !it.backendReceived })
            mesh.simSetInternet(origin, true)
            await(30_000) { mesh.state.value.media.filter { it.reportId == reportId }.let { it.size == 3 && it.all { m -> m.backendReceived } } }
            i.sendStatus(0, Bundle().apply { putString("stream", "\nActual media integration report=$reportId origin=$origin relay=$relay gateway=$gateway; audio/photo/video bytes match SHA256 at backend. Alert receipts and media confirmation remain separate.\n") })
        } finally {
            i.runOnMainSync { activity?.finish() }
            mesh.background(); mesh.setRelayEnabled(false); barrier(mesh)
            mesh.simSelectNode(original.lab.selectedNodeId)
            nodes.forEach(mesh::simRemoveNode)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, it.internet) }
            val restored = CountDownLatch(1)
            mesh.saveSettings(original.backend, credentialBefore) { restored.countDown() }
            restored.await(5, TimeUnit.SECONDS)
            mesh.setRelayEnabled(original.relayEnabled); mesh.switchMode(original.simulation); barrier(mesh)
            val editor = prefs.edit(); keys.forEach(editor::remove)
            savedPrefs.forEach { (key, value) -> when(value) {
                is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value); is Float -> editor.putFloat(key, value)
            } }
            assertTrue(editor.commit()); mesh.refresh(); barrier(mesh); db.close()
        }
    }
    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try { connection.connectTimeout = 4000; connection.readTimeout = 10000
            assertEquals(200, connection.responseCode); return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun barrier(mesh: MeshController) {
        val done = CountDownLatch(1); mesh.serial.execute { done.countDown() }; assertTrue(done.await(10, TimeUnit.SECONDS))
    }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail("Timed out waiting for real file relay or HTTP media availability"); SystemClock.sleep(100) }
    }
}
