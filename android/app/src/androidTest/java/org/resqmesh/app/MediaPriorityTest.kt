package org.resqmesh.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.resqmesh.app.core.BackendReceipt
import org.resqmesh.app.core.Report
import org.resqmesh.app.data.FileMediaStore
import org.resqmesh.app.data.MeshDatabase
import org.resqmesh.app.data.RoomReportStore
import org.resqmesh.app.data.Wire
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A deliberately slow local HTTP fixture, never a real emergency backend or peer-radio test. */
@RunWith(AndroidJUnit4::class)
class MediaPriorityTest {
    @Test fun heldAttachmentUploadCannotDelayAnotherAlertReceipt() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val mesh = (context.applicationContext as MeshApplication).mesh
        await(10_000, "Controller initialization") { mesh.state.value.ready }
        val original = mesh.state.value
        val credentialBefore = org.resqmesh.app.data.CredentialStore(context).read()
        val prefs = context.getSharedPreferences("resqmesh", 0)
        val keys = setOf("simulation", "relayEnabled", "backend", "apiKey", "simulationEnvironment", "selectedSim")
        val savedPrefs = prefs.all.filterKeys { it in keys }
        val db = Room.databaseBuilder(context, MeshDatabase::class.java, "resqmesh.db")
            .addMigrations(MeshDatabase.MIGRATION_1_2).build()
        val reports = RoomReportStore(db.dao())
        val files = FileMediaStore(File(context.filesDir, "media"))
        val fixture = SlowMediaFixture()
        var testNode: String? = null
        var activity: android.app.Activity? = null
        var capture: File? = null
        try {
            mesh.background(); mesh.setRelayEnabled(false); mesh.switchMode(true); barrier(mesh)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, false) }; barrier(mesh)
            val configured = CountDownLatch(1); val configError = AtomicReference<String?>()
            mesh.saveSettings(fixture.origin, "") { configError.set(it); configured.countDown() }
            assertTrue(configured.await(10, TimeUnit.SECONDS)); assertNull(configError.get()); barrier(mesh)
            val beforeNodes = mesh.state.value.lab.nodes.map { it.id }.toSet()
            mesh.simAddNode(); barrier(mesh)
            val node = mesh.state.value.lab.nodes.single { it.id !in beforeNodes }.id
            testNode = node; fixture.allowedNode.set(node)
            mesh.simSelectNode(node); barrier(mesh)
            assertTrue("Fixture node has no peer links", mesh.state.value.peers.isEmpty())

            val image = File(File(context.filesDir, "media-drafts").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
            capture = image
            i.context.assets.open("media/synthetic-photo.jpg").use { input -> image.outputStream().use { input.copyTo(it) } }
            val draft = MediaCaptureViewModel.prepareAttachment(image, "image")
            val firstId = create(mesh, SosDraft(text = "SYNTHETIC PRIORITY TEST: generated image, no real emergency.", attachments = listOf(draft)))
            val first = reports.get(node, firstId)!!.report
            assertEquals(1, first.attachments.size)
            assertTrue(files.available(node, firstId, first.attachments.single()))
            image.delete()

            mesh.setRelayEnabled(true)
            activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            mesh.simSetInternet(node, true)
            assertTrue("Controller must start its actual attachment PUT", fixture.putStarted.await(25, TimeUnit.SECONDS))
            assertNull("HTTP fixture failure", fixture.failure.get())
            assertTrue("First alert receipt is installed before the attachment finishes",
                reports.receipts(node).any { it.receipt.reportId == firstId && it.receipt.type == "backend_received" })
            assertFalse("Held media has no completion marker", files.uploaded(node, firstId, first.attachments.single(), fixture.origin))
            assertEquals(1L, fixture.releasePut.count)

            val secondId = create(mesh, SosDraft(text = "SYNTHETIC PRIORITY TEST: second small alert during held media upload.", emergencyType = "other"))
            await(8_000, "Second alert receipt while media PUT is held") {
                reports.receipts(node).any { it.receipt.reportId == secondId && it.receipt.type == "backend_received" }
            }
            assertEquals("Test has not released the attachment response", 1L, fixture.releasePut.count)
            assertFalse("Media response is still held when the second receipt arrives", fixture.putResponded.get())
            assertFalse(files.uploaded(node, firstId, first.attachments.single(), fixture.origin))
            assertTrue("Fixture accepted the second real HTTP report", fixture.acceptedReports.containsKey(secondId))
            assertTrue(reports.get(node, secondId)!!.report.attachments.isEmpty())
            assertNull("HTTP fixture failure", fixture.failure.get())

            fixture.releasePut.countDown()
            await(8_000, "Independent media completion after releasing its response") {
                files.uploaded(node, firstId, first.attachments.single(), fixture.origin)
            }
            assertTrue(fixture.putResponded.get())
            i.sendStatus(0, Bundle().apply { putString("stream",
                "\nHTTP priority fixture: second controller backend_received receipt arrived while the first attachment PUT response remained held. Media completed independently after release. Synthetic reports=$firstId,$secondId.\n") })
        } finally {
            fixture.releasePut.countDown()
            try {
                i.runOnMainSync { activity?.finish() }
                mesh.background(); mesh.setRelayEnabled(false); barrier(mesh)
                mesh.simSelectNode(original.lab.selectedNodeId)
                testNode?.let(mesh::simRemoveNode)
                original.lab.nodes.forEach { mesh.simSetInternet(it.id, it.internet) }
                val restored = CountDownLatch(1)
                mesh.saveSettings(original.backend, credentialBefore) { restored.countDown() }
                restored.await(5, TimeUnit.SECONDS)
                mesh.setRelayEnabled(original.relayEnabled); mesh.switchMode(original.simulation); barrier(mesh)
                val editor = prefs.edit(); keys.forEach(editor::remove)
                savedPrefs.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value); is Float -> editor.putFloat(key, value)
                } }
                assertTrue("Restore user preferences", editor.commit())
                mesh.refresh(); barrier(mesh)
            } finally { capture?.delete(); fixture.close(); db.close() }
        }
    }

    private fun create(mesh: MeshController, draft: SosDraft): String {
        val done = CountDownLatch(1); val id = AtomicReference<String?>(); val error = AtomicReference<String?>()
        mesh.create(draft) { reportId, problem -> id.set(reportId); error.set(problem); done.countDown() }
        assertTrue("Save synthetic SOS", done.await(10, TimeUnit.SECONDS)); assertNull(error.get())
        return requireNotNull(id.get())
    }
    private fun barrier(mesh: MeshController) {
        val done = CountDownLatch(1); mesh.serial.execute { done.countDown() }
        assertTrue("Controller serial barrier", done.await(10, TimeUnit.SECONDS))
    }
    private fun await(timeout: Long, label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail("Timed out: $label"); SystemClock.sleep(50) }
    }

    /** Concurrent sockets prevent the intentionally held media response from blocking fixture alert endpoints. */
    private class SlowMediaFixture : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val closed = AtomicBoolean(false)
        val origin = "http://127.0.0.1:${server.localPort}"
        val allowedNode = AtomicReference<String?>()
        val putStarted = CountDownLatch(1)
        val releasePut = CountDownLatch(1)
        val putResponded = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val acceptedReports = ConcurrentHashMap<String, Report>()
        private val receipts = ConcurrentHashMap<String, BackendReceipt>()
        private val available = ConcurrentHashMap.newKeySet<String>()

        init {
            workers.execute {
                while (!closed.get()) {
                    try {
                        val socket = server.accept()
                        workers.execute { socket.use { serve(it) } }
                    } catch (error: Exception) { if (!closed.get()) failure.compareAndSet(null, error) }
                }
            }
        }
        private fun serve(socket: Socket) {
            try {
                socket.soTimeout = 20_000
                val input = BufferedInputStream(socket.getInputStream())
                val request = requireNotNull(line(input)).split(' ')
                require(request.size == 3)
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val header = requireNotNull(line(input))
                    if (header.isEmpty()) break
                    val at = header.indexOf(':'); require(at > 0)
                    headers[header.substring(0, at).lowercase()] = header.substring(at + 1).trim()
                }
                val length = headers["content-length"]?.toInt() ?: 0
                require(length in 0..1_048_576)
                val body = ByteArray(length)
                var read = 0
                while (read < body.size) { val count = input.read(body, read, body.size - read); require(count > 0); read += count }
                val uri = URI(request[1]); val path = uri.path.split('/').filter(String::isNotEmpty)
                val result = when {
                    request[0] == "POST" && path.size == 4 && path.take(2) == listOf("api", "gateways") && path[3] == "heartbeat" ->
                        JSONObject().put("node_id", path[2]).put("online", true).put("expires_at", System.currentTimeMillis() + 60_000)
                    request[0] == "POST" && path == listOf("api", "reports") -> {
                        val report = Wire.decode(JSONObject(String(body, Charsets.UTF_8)))
                        require(report.simulation && report.originId == allowedNode.get() && report.text.startsWith("SYNTHETIC PRIORITY TEST:"))
                        require(headers["x-resqmesh-node-id"] == report.relayPath.last())
                        acceptedReports[report.id] = report
                        val receipt = receipts.getOrPut(report.id) { BackendReceipt(UUID.randomUUID().toString(), report.id,
                            UUID.randomUUID().toString(), "backend_received", System.currentTimeMillis(), report.relayPath.last(), true, report.relayPath) }
                        require(receipt.consistentWith(report))
                        JSONObject().put("report_id", report.id).put("incident_id", receipt.incidentId).put("status", "accepted")
                            .put("receipt", Wire.receipt(receipt))
                    }
                    request[0] == "GET" && path == listOf("api", "receipts") -> {
                        val reportId = requireNotNull(uri.rawQuery).substringAfter("report_id=")
                        JSONObject().put("receipts", JSONArray().also { items -> receipts[reportId]?.let { items.put(Wire.receipt(it)) } })
                    }
                    request[0] == "GET" && path.size == 4 && path.take(2) == listOf("api", "reports") && path[3] == "attachments" -> {
                        val report = requireNotNull(acceptedReports[path[2]])
                        JSONObject().put("attachments", JSONArray().also { items -> report.attachments.forEach { attachment ->
                            items.put(Wire.attachment(attachment).put("status", if ("${report.id}:${attachment.id}" in available) "available" else "pending"))
                        } })
                    }
                    request[0] == "PUT" && path.size == 5 && path.take(2) == listOf("api", "reports") && path[3] == "attachments" -> {
                        val report = requireNotNull(acceptedReports[path[2]])
                        val attachment = report.attachments.single { it.id == path[4] }
                        require(headers["x-resqmesh-node-id"] == report.relayPath.last())
                        require(body.size.toLong() == attachment.byteSize)
                        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                        require(sha == attachment.sha256)
                        putStarted.countDown()
                        check(releasePut.await(20, TimeUnit.SECONDS)) { "Media response was never released" }
                        available.add("${report.id}:${attachment.id}")
                        putResponded.set(true)
                        Wire.attachment(attachment).put("status", "available")
                    }
                    else -> error("Unexpected fixture request ${request[0]} ${uri.path}")
                }
                respond(socket, 200, result)
            } catch (error: Exception) {
                if (!closed.get()) failure.compareAndSet(null, error)
                runCatching { respond(socket, 500, JSONObject().put("detail", "Synthetic test fixture failure")) }
            }
        }
        private fun line(input: BufferedInputStream): String? {
            val bytes = ArrayList<Byte>()
            while (bytes.size <= 8192) {
                val value = input.read()
                if (value < 0) return if (bytes.isEmpty()) null else error("Incomplete HTTP line")
                if (value == 10) {
                    require(bytes.lastOrNull() == 13.toByte())
                    return bytes.dropLast(1).toByteArray().toString(Charsets.US_ASCII)
                }
                bytes.add(value.toByte())
            }
            error("HTTP header too long")
        }
        private fun respond(socket: Socket, status: Int, json: JSONObject) {
            val body = json.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(body); flush()
            }
        }
        override fun close() {
            releasePut.countDown(); closed.set(true); server.close(); workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
