package org.resqmesh.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DynamicRuntimeTest {
    private val now = 1_800_000_000_000L
    private class Store : ReportStore, ReceiptStore {
        val reports = mutableMapOf<Pair<String, String>, StoredReport>()
        val acknowledgements = mutableMapOf<Pair<String, String>, ReceiptPacket>()
        override fun get(nodeId: String, reportId: String) = reports[nodeId to reportId]
        override fun all(nodeId: String) = reports.values.filter { it.nodeId == nodeId }
        override fun save(stored: StoredReport) { reports[stored.nodeId to stored.report.id] = stored }
        override fun receipt(node: String, id: String) = acknowledgements[node to id]
        override fun receipts(node: String) = acknowledgements.filterKeys { it.first == node }.values.toList()
        override fun saveReceipt(node: String, packet: ReceiptPacket) { acknowledgements[node to packet.receipt.id] = packet }
        override fun hasDeliveryReceipt(nodeId: String, reportId: String) = receipts(nodeId).any { it.receipt.reportId == reportId }
    }
    private fun report(origin: String) = Report(UUID.randomUUID().toString(), origin, now, now + 1_800_000,
        "Floodwater rising; three people trapped", "Building", "Ground", simulation = true,
        schemaVersion = 2, emergencyType = "flood", peopleAffected = 3, vulnerability = "One elderly person")
    private fun receipt(r: Report, gateway: String, path: List<String>, kind: String = "backend_received") = BackendReceipt(
        UUID.randomUUID().toString(), r.id, UUID.randomUUID().toString(), kind, now, gateway, true, path)

    @Test fun `arbitrary population and links produce actual received path`() {
        val t = SimulationTransport(); val s = Store(); val e = RelayEngine(s, t, { now })
        val root = "RQM-918F"; val relay = "RQM-2CB8"; val gateway = "RQM-0C10"
        t.addNode(SimNode(root)); val report = report(root); e.originate(root, report); e.relay(root)
        assertTrue(t.peers(root).isEmpty()); assertEquals(1, s.reports.size)
        t.addNode(SimNode(relay)); t.setLink(root, relay, true); e.relay(root)
        assertEquals(listOf(root, relay), s.get(relay, report.id)!!.report.relayPath)
        t.addNode(SimNode(gateway)); t.setLink(relay, gateway, true); e.relay(relay)
        assertEquals(listOf(root, relay, gateway), s.get(gateway, report.id)!!.report.relayPath)
        t.removeNode(relay); assertTrue(t.peers(root).isEmpty()); assertTrue(t.peers(gateway).isEmpty())
        assertNotNull(s.get(relay, report.id)) // environmental removal never deletes retained data
    }
    @Test fun `multiple peer events share one transport contract with appearance and loss`() {
        val t = SimulationTransport(); val events = mutableListOf<TransportEvent>(); t.eventsWith(events::add)
        t.addNode(SimNode("local")); (1..12).forEach { t.addNode(SimNode("peer-$it")); t.setLink("local", "peer-$it", true) }
        assertEquals(12, t.peerStates("local").size)
        t.setLink("local", "peer-4", false); t.setInternet("peer-7", true)
        assertEquals(11, t.peers("local").size)
        assertTrue(events.any { it is TransportEvent.PeerChanged && it.local == "local" && it.peer.phase == PeerPhase.LOST })
        assertTrue(events.any { it is TransportEvent.EnvironmentChanged && it.nodeId == "peer-7" })
    }
    @Test fun `origin learns gateway delivery only after receipt crosses restored links`() {
        val t = SimulationTransport(); listOf("origin", "relay", "gateway").forEach { t.addNode(SimNode(it)) }
        t.setLink("origin", "relay", true); t.setLink("relay", "gateway", true)
        val s = Store(); val events = mutableListOf<JourneyEvent>(); val e = RelayEngine(s, t, { now }); val r = report("origin")
        val rr = ReceiptRelay(s, t, { now }, events::add)
        t.controlWith { node, from, packet -> packet is ControlPacket.Delivery && rr.receive(node, from, packet.packet) }
        e.originate("origin", r); e.relay("origin"); e.relay("relay")
        t.setLink("origin", "relay", false)
        rr.installFromBackend("gateway", receipt(r, "gateway", listOf("origin", "relay", "gateway")))
        rr.relay("gateway"); rr.relay("relay")
        assertFalse(s.hasDeliveryReceipt("origin", r.id))
        assertFalse(events.any { it.nodeId == "origin" && it.kind == "backend_received" })
        t.setLink("origin", "relay", true); rr.relay("relay")
        assertTrue(s.hasDeliveryReceipt("origin", r.id))
        assertEquals(1, events.count { it.nodeId == "origin" && it.kind == "backend_received" })
    }
    @Test fun `two gateways do not duplicate an immutable receipt at origin`() {
        val t = SimulationTransport(); listOf("source", "gate-one", "gate-two").forEach { t.addNode(SimNode(it)) }
        t.setLink("source", "gate-one", true); t.setLink("source", "gate-two", true)
        val s = Store(); val rr = ReceiptRelay(s, t, { now }); val r = report("source")
        t.controlWith { node, from, p -> p is ControlPacket.Delivery && rr.receive(node, from, p.packet) }
        val evidence = receipt(r, "gate-one", listOf("source", "gate-one"))
        rr.installFromBackend("gate-one", evidence); rr.installFromBackend("gate-two", evidence)
        rr.relay("gate-one"); rr.relay("gate-two"); rr.relay("source")
        assertEquals(1, s.receipts("source").size)
        assertEquals(listOf("source", "gate-one"), s.receipts("source").single().receipt.relayPath)
    }
    @Test fun `receipt TTL loop altered IDs and invalid issuer are rejected`() {
        val t = SimulationTransport(); val s = Store(); val rr = ReceiptRelay(s, t, { now }); val r = report("o")
        val receipt = receipt(r, "g", listOf("o", "g"))
        assertFalse(rr.installFromBackend("g", receipt.copy(issuer = "invented")))
        assertFalse(rr.receive("o", "g", ReceiptPacket(receipt, listOf("g"), now)))
        assertFalse(rr.receive("o", "g", ReceiptPacket(receipt, listOf("o", "g"), now + 1000)))
        assertTrue(rr.installFromBackend("g", receipt))
        assertFalse(rr.installFromBackend("g", receipt.copy(type = "responder_acknowledged")))
    }
    @Test fun `structured report validates nullable and bounded user fields`() {
        val r = report("RQM-REAL")
        assertTrue(r.valid()); assertFalse(r.copy(peopleAffected = -1).valid()); assertFalse(r.copy(peopleAffected = 10001).valid())
        assertFalse(r.copy(emergencyType = "invented-category").valid()); assertFalse(r.copy(room = "x".repeat(41)).valid())
        assertFalse(r.copy(locationText = " ").valid()); assertTrue(r.copy(peopleAffected = null, vulnerability = null).valid())
    }
    @Test fun `responder acknowledgement is separately stored and returns only over available links`() {
        val t = SimulationTransport(); listOf("source", "carrier").forEach { t.addNode(SimNode(it)) }
        val s = Store(); val rr = ReceiptRelay(s, t, { now }); val r = report("source")
        t.controlWith { node, from, p -> p is ControlPacket.Delivery && rr.receive(node, from, p.packet) }
        val accepted = receipt(r, "carrier", listOf("source", "carrier"))
        val human = accepted.copy(id = UUID.randomUUID().toString(), type = "responder_acknowledged", gatewayId = null)
        rr.installFromBackend("source", accepted); rr.installFromBackend("carrier", human); rr.relay("carrier")
        assertEquals(listOf("backend_received"), s.receipts("source").map { it.receipt.type })
        t.setLink("source", "carrier", true); rr.relay("carrier"); rr.relay("carrier")
        assertEquals(setOf("backend_received", "responder_acknowledged"), s.receipts("source").map { it.receipt.type }.toSet())
        assertEquals(2, s.receipts("source").size)
    }
    @Test fun `bounded gateway work rotates across a backlog even if earlier items keep failing`() {
        val reports = (1..300).map { "report-%04d".format(it) }
        val seen = mutableSetOf<String>(); var cursor: String? = null
        repeat(10) {
            val batch = rotatingBatch(reports, cursor, 32) { it }
            assertEquals(32, batch.size); seen.addAll(batch); cursor = batch.last()
        }
        assertEquals(reports.toSet(), seen)
        assertEquals(listOf("report-0001"), rotatingBatch(listOf("report-0001"), "deleted-report", 32) { it })
        assertTrue(rotatingBatch(emptyList<String>(), cursor, 32) { it }.isEmpty())
    }
    @Test fun `expiry maintenance works without forwarding while relay is paused`() {
        var clock = now; val t = SimulationTransport(); val s = Store(); val events = mutableListOf<JourneyEvent>()
        val e = RelayEngine(s, t, { clock }, onJourney = events::add); val r = report("isolated")
        e.originate("isolated", r); clock = r.expiresAt; e.expire("isolated"); e.expire("isolated")
        assertEquals(DeliveryStatus.EXPIRED, s.get("isolated", r.id)!!.status)
        assertEquals(1, events.count { it.kind == "expired" })
        assertTrue(events.none { it.kind == "peer_stored" })
    }
    @Test fun `receipt cannot contradict the locally known report origin mode or time`() {
        val t = SimulationTransport(); val s = Store(); val e = RelayEngine(s, t, { now }); val r = report("source")
        e.originate("source", r); val rr = ReceiptRelay(s, t, { now }); val good = receipt(r, "gateway", listOf("source", "gateway"))
        assertFalse(rr.installFromBackend("source", good.copy(relayPath = listOf("someone-else", "gateway"))))
        assertFalse(rr.installFromBackend("source", good.copy(simulation = false)))
        assertFalse(rr.installFromBackend("source", good.copy(timestamp = r.createdAt - 600_000)))
        assertFalse(rr.installFromBackend("source", good.copy(gatewayId = "wrong-gateway")))
        assertTrue(rr.installFromBackend("source", good))
    }
}
