package org.resqmesh.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A real delayed HTTP response may not renew the lease for a different configured server. */
@RunWith(AndroidJUnit4::class)
class GatewaySettingsTest {
    @Test fun oldServerHeartbeatCannotMarkNewDestinationReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mesh = (context.applicationContext as MeshApplication).mesh
        await { mesh.state.value.ready }
        val before = mesh.state.value
        val key = org.resqmesh.app.data.CredentialStore(context).read()
        val delayed = HeartbeatServer(delayed = true)
        val unavailable = HeartbeatServer(delayed = false)
        var scenario: ActivityScenario<MainActivity>? = null
        var node: String? = null
        try {
            mesh.background(); mesh.switchMode(true); mesh.setRelayEnabled(true)
            before.lab.nodes.forEach { mesh.simSetInternet(it.id, false) }
            barrier(mesh)
            val oldIds = mesh.state.value.lab.nodes.map { it.id }.toSet()
            mesh.simAddNode(); await { mesh.state.value.lab.nodes.any { it.id !in oldIds } }
            node = mesh.state.value.lab.nodes.first { it.id !in oldIds }.id
            mesh.simSelectNode(node); barrier(mesh)
            save(mesh, delayed.url)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            mesh.simSetInternet(node, true)
            assertTrue("Old backend request began", delayed.arrived.await(10, TimeUnit.SECONDS))
            save(mesh, unavailable.url)
            assertTrue("New backend was independently checked", unavailable.arrived.await(10, TimeUnit.SECONDS))
            await { mesh.state.value.backend == unavailable.url && !mesh.state.value.connectivity.checking && mesh.state.value.connectivity.lastError != null }
            delayed.release.countDown()
            assertTrue("Delayed old response was released", delayed.completed.await(10, TimeUnit.SECONDS))
            // Let the released response pass through the network worker and serialized completion.
            SystemClock.sleep(750); barrier(mesh)
            assertEquals(unavailable.url, mesh.state.value.backend)
            assertFalse("A successful stale response belongs to the old server", mesh.state.value.connectivity.backendReachable)
            assertEquals(0L, mesh.state.value.connectivity.leaseExpiresAt)
        } finally {
            delayed.release.countDown(); delayed.close(); unavailable.close(); scenario?.close()
            mesh.background(); node?.let(mesh::simRemoveNode)
            before.lab.nodes.forEach { mesh.simSetInternet(it.id, it.internet) }
            mesh.simSelectNode(before.lab.selectedNodeId)
            val restored = CountDownLatch(1); mesh.saveSettings(before.backend, key) { restored.countDown() }
            assertTrue(restored.await(5, TimeUnit.SECONDS)); mesh.setRelayEnabled(before.relayEnabled); mesh.switchMode(before.simulation); barrier(mesh)
        }
    }
    private fun save(mesh: MeshController, url: String) {
        val saved = CountDownLatch(1); mesh.saveSettings(url, "") { assertNull(it); saved.countDown() }
        assertTrue(saved.await(5, TimeUnit.SECONDS))
    }
    private fun barrier(mesh: MeshController) { val done = CountDownLatch(1); mesh.serial.execute { done.countDown() }; assertTrue(done.await(5, TimeUnit.SECONDS)) }
    private fun await(condition: () -> Boolean) { val until = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) { if (SystemClock.elapsedRealtime() > until) fail("Gateway state did not settle"); SystemClock.sleep(50) }
    }
    private class HeartbeatServer(private val delayed: Boolean) {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val arrived = CountDownLatch(1); val release = CountDownLatch(if (delayed) 1 else 0); val completed = CountDownLatch(1)
        init {
            Thread({
                try {
                    while (!socket.isClosed) socket.accept().use { client ->
                        client.soTimeout = 10_000
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine() ?: return@use
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        val node = request.substringAfter("/api/gateways/").substringBefore("/heartbeat")
                        arrived.countDown(); release.await(15, TimeUnit.SECONDS)
                        val body = if (delayed) JSONObject().put("node_id", node).put("online", true).put("last_seen", System.currentTimeMillis())
                            .put("expires_at", System.currentTimeMillis() + 120_000).put("simulation", true).toString() else "{}"
                        val status = if (delayed) "200 OK" else "503 Service Unavailable"
                        client.getOutputStream().bufferedWriter().use { out -> out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"); out.flush() }
                        completed.countDown()
                    }
                } catch (_: Exception) { /* close() terminates a blocked accept during test cleanup. */ }
            }, "test-heartbeat").apply { isDaemon = true; start() }
        }
        fun close() { socket.close() }
    }
}
