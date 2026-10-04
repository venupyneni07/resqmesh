package org.resqmesh.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class QuickSosTest {
    private val now = 1_800_000_000_000L
    private fun report(text: String = "", location: LocationContext = LocationContext()): Report {
        val message = sosMessage(text)
        return Report(UUID.randomUUID().toString(), "source", now, now + 1_800_000,
            message.text, null, null, simulation = true, schemaVersion = 3,
            messageSource = message.source, locationContext = location)
    }

    @Test fun `zero entry and whitespace SOS need no category location count or message`() {
        for (text in listOf("", "  ", "\n\t")) {
            val report = report(text)
            assertTrue(report.valid())
            assertEquals(QUICK_SOS_MESSAGE, report.text)
            assertEquals("preset", report.messageSource)
            assertNull(report.peopleAffected)
            assertEquals(LocationContext(), report.locationContext)
        }
    }

    @Test fun `short typed messages retain explicit user provenance`() {
        assertEquals(SosMessage("help", "user"), sosMessage("  help  "))
        assertTrue(report("!").valid())
        assertEquals("user", report(QUICK_SOS_MESSAGE).messageSource)
        assertFalse(report("My own words").copy(messageSource = "preset").valid())
        assertFalse(report().copy(messageSource = "model").valid())
    }

    @Test fun `quick needs remain independent of unknown people counts`() {
        val report = report().copy(quickNeeds = listOf("cannot_move", "cannot_speak", "people_injured"))
        assertTrue(report.valid())
        assertNull(report.peopleAffected)
        assertNull(report.emergencyType)
        assertFalse(report.copy(quickNeeds = listOf("cannot_move", "cannot_move")).valid())
        assertFalse(report.copy(quickNeeds = listOf("invented_need")).valid())
    }

    @Test fun `unknown location cannot conceal an observation or coordinates`() {
        assertTrue(report().valid())
        assertFalse(report(location = LocationContext(observedAt = now)).valid())
        assertFalse(report(location = LocationContext(latitude = 12.9, longitude = 77.6)).valid())
        assertFalse(report(location = LocationContext(accuracyM = 10.0)).valid())
        assertFalse(report(location = LocationContext("guessed", now)).valid())
    }

    @Test fun `old saved locations stay valid without being presented as fresh observations`() {
        val old = LocationContext("saved", now - 7 * 86_400_000L)
        assertTrue(report(location = old).valid())
        assertEquals(now - 7 * 86_400_000L, report(location = old).locationContext.observedAt)
        assertTrue(report(location = LocationContext("manual", now)).valid())
        assertFalse(report(location = LocationContext("manual")).valid())
        assertFalse(report(location = LocationContext("saved", 0)).valid())
        assertTrue(report(location = LocationContext("manual", now + 300_000)).valid())
        assertFalse(report(location = LocationContext("manual", now + 300_001)).valid())
    }

    @Test fun `device coordinates must be paired finite and geographically bounded`() {
        val device = LocationContext("device", now, 12.9716, 77.5946, 25.0)
        assertTrue(report(location = device).valid())
        assertTrue(report(location = device.copy(latitude = -90.0, longitude = 180.0, accuracyM = 0.0)).valid())
        assertFalse(report(location = device.copy(latitude = null)).valid())
        assertFalse(report(location = device.copy(latitude = null, longitude = null, accuracyM = null)).valid())
        assertFalse(report(location = device.copy(latitude = 90.01)).valid())
        assertFalse(report(location = device.copy(longitude = -180.01)).valid())
        assertFalse(report(location = device.copy(latitude = Double.NaN)).valid())
        assertFalse(report(location = device.copy(longitude = Double.POSITIVE_INFINITY)).valid())
        assertFalse(report(location = device.copy(accuracyM = -1.0)).valid())
        assertFalse(report(location = device.copy(accuracyM = Double.NaN)).valid())
        assertFalse(report(location = LocationContext("manual", now, accuracyM = 10.0)).valid())
    }

    @Test fun `relay preserves source metadata and rejects reused UUID with changed quick needs or location`() {
        val rows = mutableMapOf<Pair<String, String>, StoredReport>()
        val store = object : ReportStore {
            override fun get(nodeId: String, reportId: String) = rows[nodeId to reportId]
            override fun all(nodeId: String) = rows.values.filter { it.nodeId == nodeId }
            override fun save(stored: StoredReport) { rows[stored.nodeId to stored.report.id] = stored }
        }
        val transport = SimulationTransport()
        listOf("source", "relay", "gateway").forEach { transport.addNode(SimNode(it)) }
        transport.setLink("source", "relay", true)
        transport.setLink("relay", "gateway", true)
        val engine = RelayEngine(store, transport, { now })
        val original = report(location = LocationContext("device", now - 60_000, 12.9, 77.6, 20.0))
            .copy(quickNeeds = listOf("cannot_speak"))
        assertTrue(engine.originate("source", original))
        engine.relay("source"); engine.relay("relay")
        val delivered = store.get("gateway", original.id)!!.report
        assertEquals(original.copy(hopCount = 2, relayPath = listOf("source", "relay", "gateway")), delivered)
        assertEquals(DeliveryStatus.RECEIVED, store.get("gateway", original.id)!!.status)
        assertEquals(ReceiveResult.INVALID, engine.receive("relay", "source", original.copy(quickNeeds = emptyList())))
        assertEquals(ReceiveResult.INVALID, engine.receive("relay", "source",
            original.copy(locationContext = original.locationContext.copy(observedAt = now))))
    }

    @Test fun `legacy report validation is unchanged`() {
        for (version in 1..2) {
            val legacy = report("Existing report").copy(schemaVersion = version)
            assertTrue(legacy.valid())
            assertFalse(legacy.copy(text = " ").valid())
            assertFalse(legacy.copy(quickNeeds = listOf("cannot_speak")).valid())
            assertFalse(legacy.copy(locationContext = LocationContext("saved", now)).valid())
        }
        assertFalse(report().copy(schemaVersion = 5).valid())
    }
}
