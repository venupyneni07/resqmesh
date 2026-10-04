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
import org.resqmesh.app.core.*
import org.resqmesh.app.data.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in actual HTTP ingest + delayed reverse receipt propagation, never a physical-radio test. */
@RunWith(AndroidJUnit4::class)
class SimulationGatewayTest {
    @Test fun dynamicEnvironmentAutomaticallyRelaysAndReturnsBackendReceipt() {
        val backend = InstrumentationRegistry.getArguments().getString("liveBackend")
        assumeTrue("Supply liveBackend to opt into actual local server integration", backend != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("resqmesh", 0)
        val mesh = (context.applicationContext as MeshApplication).mesh
        await(10_000) { mesh.state.value.ready }
        val original = mesh.state.value
        val changedKeys = setOf("simulation", "relayEnabled", "backend", "apiKey", "simulationEnvironment", "selectedSim")
        val savedPreferences = prefs.all.filterKeys { it in changedKeys }
        val oldKey = org.resqmesh.app.data.CredentialStore(context).read()
        val createdNodes = linkedSetOf<String>()
        var activity: android.app.Activity? = null
        val db = Room.databaseBuilder(context, MeshDatabase::class.java, "resqmesh.db").addMigrations(MeshDatabase.MIGRATION_1_2).build()
        val store = RoomReportStore(db.dao())
        try {
            mesh.background(); mesh.setRelayEnabled(false); mesh.switchMode(true); serialBarrier(mesh)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, false) }; serialBarrier(mesh)
            val configured = CountDownLatch(1); mesh.saveSettings(backend!!, "") { configured.countDown() }
            assertTrue(configured.await(10, TimeUnit.SECONDS)); await(5000) { mesh.state.value.ready && mesh.state.value.simulation }
            fun addNode(): String {
                val before = mesh.state.value.lab.nodes.map { it.id }.toSet(); mesh.simAddNode()
                await(5000) { mesh.state.value.lab.nodes.any { it.id !in before } }
                return mesh.state.value.lab.nodes.first { it.id !in before }.id.also { createdNodes.add(it) }
            }
            val origin = addNode(); mesh.simSelectNode(origin); await(5000) { mesh.state.value.localNodeId == origin }
            assertTrue(mesh.state.value.peers.isEmpty())
            mesh.setRelayEnabled(true)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val ids = mutableListOf<String>()
            fun create(draft: SosDraft) {
                val done = CountDownLatch(1); val id = AtomicReference<String?>(); val error = AtomicReference<String?>()
                mesh.create(draft) { reportId, failure -> id.set(reportId); error.set(failure); done.countDown() }
                assertTrue(done.await(10, TimeUnit.SECONDS)); assertNull(error.get()); ids.add(id.get()!!)
            }
            create(SosDraft("SYNTHETIC TEST ONLY: Ground floor lo water fast ga vastundi. Three people trapped. One elderly person.", "flood", "Synthetic test apartment", "Ground floor", peopleAffected = 3, vulnerability = "One elderly person"))
            val observedAt = System.currentTimeMillis() - 7_200_000
            create(SosDraft(emergencyType = "trapped", building = "SYNTHETIC TEST LOCATION", floor = "Basement",
                quickNeeds = setOf("cannot_move", "cannot_speak", "people_injured"), peopleAffected = 2,
                locationContext = LocationContext("saved", observedAt, 12.9716, 77.5946, 35.0)))
            val quickId = ids.last()
            fun assertQuick(node: String) {
                val report = store.get(node, quickId)!!.report
                assertEquals(QUICK_SOS_MESSAGE, report.text); assertEquals("preset", report.messageSource)
                assertEquals(setOf("cannot_move", "cannot_speak", "people_injured"), report.quickNeeds.toSet())
                assertEquals(2, report.peopleAffected); assertEquals("saved", report.locationContext.source)
                assertEquals(observedAt, report.locationContext.observedAt)
                assertEquals(12.9716, report.locationContext.latitude!!, 0.000001)
                assertEquals(77.5946, report.locationContext.longitude!!, 0.000001)
            }
            assertQuick(origin)
            assertEquals(2, store.all(origin).size)
            assertTrue(mesh.state.value.receipts.isEmpty())
            val relay = addNode(); mesh.simSetLink(origin, relay, true)
            await(10_000) { ids.all { store.get(relay, it) != null } }
            ids.forEach { assertEquals(listOf(origin, relay), store.get(relay, it)!!.report.relayPath) }; assertQuick(relay)
            // Break the reverse path before any gateway exists. Upload elsewhere must not update origin by omniscience.
            mesh.simSetLink(origin, relay, false); await(5000) { mesh.state.value.peers.isEmpty() }
            val gateway = addNode(); mesh.simSetLink(relay, gateway, true)
            await(10_000) { ids.all { store.get(gateway, it) != null } }
            assertQuick(gateway)
            assertTrue(store.receipts(origin).isEmpty())
            mesh.simSetInternet(gateway, true)
            await(90_000) { ids.all { id -> store.receipts(gateway).any { it.receipt.reportId == id && it.receipt.type == "backend_received" } } }
            val snapshotRequest = URL("$backend/api/state").openConnection() as HttpURLConnection
            try {
                snapshotRequest.connectTimeout = 5000; snapshotRequest.readTimeout = 10_000
                val reports = JSONObject(snapshotRequest.inputStream.bufferedReader().use { it.readText() }).getJSONArray("reports")
                val uploaded = (0 until reports.length()).map { reports.getJSONObject(it) }.single { it.getString("id") == quickId }
                assertEquals(QUICK_SOS_MESSAGE, uploaded.getString("text")); assertEquals("preset", uploaded.getString("message_source"))
                assertEquals(3, uploaded.getJSONArray("quick_needs").length())
                assertEquals("saved", uploaded.getJSONObject("location_context").getString("source"))
                assertEquals(observedAt, uploaded.getJSONObject("location_context").getLong("observed_at"))
            } finally { snapshotRequest.disconnect() }
            assertTrue("Origin cannot see remote database rows or receipts", store.receipts(origin).isEmpty())
            assertTrue(mesh.state.value.receipts.isEmpty())
            mesh.simSetLink(origin, relay, true)
            await(15_000) { ids.all { id -> store.receipts(origin).any { it.receipt.reportId == id } } }
            ids.forEach { id ->
                val receipt = store.receipts(origin).first { it.receipt.reportId == id }.receipt
                assertEquals(listOf(origin, relay, gateway), receipt.relayPath)
                assertEquals("backend_issued_unattested", receipt.trust)
                assertEquals(DeliveryStatus.FORWARDED, store.get(origin, id)!!.status)
            }
            // A second arbitrary node can independently gain backend connectivity; no designated gateway exists.
            val secondGateway = addNode(); mesh.simSetLink(origin, secondGateway, true); mesh.simSetInternet(secondGateway, true)
            await(30_000) { mesh.state.value.peers.any { it.id == secondGateway && it.gatewayAvailable } }
            mesh.simSetInternet(secondGateway, false)
            await(5000) { mesh.state.value.peers.none { it.id == secondGateway && it.gatewayAvailable } }
            mesh.simRemoveNode(secondGateway)
            await(5000) { mesh.state.value.peers.none { it.id == secondGateway } }
            assertEquals(2, store.receipts(origin).count { it.receipt.type == "backend_received" })
            // Explicit test operator action for only these newly-created synthetic incidents.
            mesh.simSetLink(origin, relay, false); await(5000) { mesh.state.value.peers.isEmpty() }
            store.receipts(origin).map { it.receipt.incidentId }.distinct().forEach { incident ->
                val request = URL("$backend/api/incidents/$incident/acknowledge").openConnection() as HttpURLConnection
                try { request.requestMethod = "POST"; request.connectTimeout = 5000; request.readTimeout = 10_000
                    assertTrue("Test operator acknowledgement must succeed", request.responseCode in 200..299)
                    request.inputStream.close()
                } finally { request.disconnect() }
            }
            await(40_000) { ids.all { id -> store.receipts(gateway).any { it.receipt.reportId == id && it.receipt.type == "responder_acknowledged" } } }
            assertTrue(store.receipts(origin).none { it.receipt.type == "responder_acknowledged" })
            mesh.simSetLink(origin, relay, true)
            await(15_000) { ids.all { id -> store.receipts(origin).any { it.receipt.reportId == id && it.receipt.type == "responder_acknowledged" } } }
            assertEquals(4, store.receipts(origin).size)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nDynamic simulation: local=$origin, late relay=$relay, temporary gateway=$gateway. Backend and responder receipts reached source only after reverse link restored. Report IDs=${ids.joinToString()}\n")
            })
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            mesh.background(); mesh.setRelayEnabled(false); serialBarrier(mesh)
            mesh.simSelectNode(original.lab.selectedNodeId)
            createdNodes.forEach(mesh::simRemoveNode)
            original.lab.nodes.forEach { mesh.simSetInternet(it.id, it.internet) }
            val restored = CountDownLatch(1); mesh.saveSettings(original.backend, oldKey) { restored.countDown() }; restored.await(5, TimeUnit.SECONDS)
            mesh.setRelayEnabled(original.relayEnabled); mesh.switchMode(original.simulation); serialBarrier(mesh)
            val editor = prefs.edit(); changedKeys.forEach(editor::remove)
            savedPreferences.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value); is Float -> editor.putFloat(key, value)
            } }
            assertTrue("Restore original settings and simulation environment", editor.commit())
            mesh.refresh(); serialBarrier(mesh); db.close()
        }
    }
    private fun serialBarrier(mesh: MeshController) {
        val done = CountDownLatch(1); mesh.serial.execute { done.countDown() }
        assertTrue("Controller operation completed", done.await(10, TimeUnit.SECONDS))
    }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail("Timed out waiting for actual runtime state / HTTP receipt"); SystemClock.sleep(150) }
    }
}
