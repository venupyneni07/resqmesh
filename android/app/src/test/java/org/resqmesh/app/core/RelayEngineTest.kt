package org.resqmesh.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RelayEngineTest {
    private class MemoryStore : ReportStore {
        val rows = mutableMapOf<Pair<String, String>, StoredReport>()
        override fun get(nodeId: String, reportId: String) = rows[nodeId to reportId]
        override fun all(nodeId: String) = rows.values.filter { it.nodeId == nodeId }
        override fun save(stored: StoredReport) { rows[stored.nodeId to stored.report.id] = stored }
    }
    private fun linearTransport(): SimulationTransport {
        val environment = SimulationEnvironment()
        listOf("A", "B", "C", "D").forEach { environment.nodes[it] = SimNode(it) }
        environment.links.addAll(listOf(setOf("A", "B"), setOf("B", "C"), setOf("C", "D")))
        return SimulationTransport(environment)
    }
    private var now = 1_800_000_000_000L
    private fun report(maxHops: Int = 8, ttl: Long = 30 * 60_000L) = Report(
        UUID.randomUUID().toString(), "A", now, now + ttl, "Three people trapped by floodwater", "Demo apartment", "Ground floor",
        maxHops = maxHops, simulation = true)
    private fun round(engine: RelayEngine) = listOf("D", "C", "B", "A").forEach(engine::relay)

    @Test fun `three rounds produce exact three hop path to gateway`() {
        val store = MemoryStore(); val e = RelayEngine(store, linearTransport(), { now }); val r = report()
        assertTrue(e.originate("A", r))
        round(e); assertNotNull(store.get("B", r.id)); assertNull(store.get("C", r.id))
        round(e); assertNotNull(store.get("C", r.id)); assertNull(store.get("D", r.id))
        round(e)
        assertEquals(listOf("A", "B", "C", "D"), store.get("D", r.id)!!.report.relayPath)
        assertEquals(3, store.get("D", r.id)!!.report.hopCount)
        assertEquals(DeliveryStatus.RECEIVED, store.get("D", r.id)!!.status)
        assertEquals(DeliveryStatus.FORWARDED, store.get("A", r.id)!!.status)
    }
    @Test fun `partition retains message and resumes when link returns`() {
        val store = MemoryStore(); val t = linearTransport().apply { setLink("B", "C", false) }
        val e = RelayEngine(store, t, { now }); val r = report(); e.originate("A", r)
        repeat(5) { round(e) }
        assertNotNull(store.get("B", r.id)); assertNull(store.get("C", r.id)); assertEquals(2, store.rows.size)
        t.setLink("B", "C", true); repeat(2) { round(e) }
        assertNotNull(store.get("D", r.id)); assertEquals(4, store.rows.size)
    }
    @Test fun `duplicate UUID is ignored without overwriting first received envelope`() {
        val store = MemoryStore(); val e = RelayEngine(store, linearTransport(), { now }); val r = report()
        assertEquals(ReceiveResult.ACCEPTED, e.receive("B", "A", r))
        assertEquals(ReceiveResult.DUPLICATE, e.receive("B", "A", r))
        assertEquals(ReceiveResult.INVALID, e.receive("B", "A", r.copy(text = "Altered duplicate")))
        assertEquals(r.text, store.get("B", r.id)!!.report.text); assertEquals(1, store.rows.size)
    }
    @Test fun `hop budget prevents a ninth hop`() {
        val store = MemoryStore(); val e = RelayEngine(store, linearTransport(), { now }); val r = report(maxHops = 1)
        e.originate("A", r); repeat(5) { round(e) }
        assertEquals(1, store.get("B", r.id)!!.report.hopCount); assertNull(store.get("C", r.id))
        assertEquals(ReceiveResult.HOP_LIMIT, e.receive("C", "B", store.get("B", r.id)!!.report))
    }
    @Test fun `expired report cannot originate receive or resume after partition`() {
        val store = MemoryStore(); val t = linearTransport().apply { setLink("B", "C", false) }
        val e = RelayEngine(store, t, { now }); val r = report(ttl = 1000)
        e.originate("A", r); round(e); now += 1000; t.setLink("B", "C", true); round(e)
        assertEquals(DeliveryStatus.EXPIRED, store.get("B", r.id)!!.status)
        assertNull(store.get("C", r.id)); assertFalse(e.originate("A", r.copy(id = UUID.randomUUID().toString())))
        assertEquals(ReceiveResult.EXPIRED, e.receive("C", "B", store.get("B", r.id)!!.report))
    }
    @Test fun `sender must match last path element`() {
        val e = RelayEngine(MemoryStore(), linearTransport(), { now })
        assertEquals(ReceiveResult.INVALID, e.receive("B", "C", report()))
    }
    @Test fun `looped path is rejected even with valid hop count`() {
        val e = RelayEngine(MemoryStore(), linearTransport(), { now })
        val r = report().copy(relayPath = listOf("A", "B"), hopCount = 1)
        assertEquals(ReceiveResult.LOOP, e.receive("A", "B", r))
    }
    @Test fun `invalid UUID hop count and duplicate paths fail validation`() {
        val r = report()
        assertFalse(r.copy(id = "not-a-uuid").valid())
        assertFalse(r.copy(hopCount = 2).valid())
        assertFalse(r.copy(relayPath = listOf("A", "B", "A"), hopCount = 2).valid())
        assertFalse(r.copy(maxHops = 9).valid())
        assertFalse(r.copy(originId = "spaces forbidden", relayPath = listOf("spaces forbidden")).valid())
    }
    @Test fun `future timestamp is rejected`() {
        val e = RelayEngine(MemoryStore(), linearTransport(), { now })
        val r = report().copy(createdAt = now + 120_000)
        assertFalse(e.originate("A", r)); assertEquals(ReceiveResult.INVALID, e.receive("B", "A", r))
    }
    @Test fun `restart retains dedup and forwarding state in supplied store`() {
        val store = MemoryStore(); val r = report()
        RelayEngine(store, linearTransport(), { now }).also { it.originate("A", r); round(it) }
        val restarted = RelayEngine(store, linearTransport(), { now })
        assertFalse(restarted.originate("A", r)); repeat(3) { round(restarted) }
        assertEquals(4, store.rows.size); assertNotNull(store.get("D", r.id))
    }
    @Test fun `successful relay never implies backend upload`() {
        val store = MemoryStore(); val e = RelayEngine(store, linearTransport(), { now }); e.originate("A", report())
        repeat(8) { round(e) }; assertTrue(store.rows.values.none { it.status == DeliveryStatus.UPLOADED })
    }
    @Test fun `failed sends are retried and only acknowledged peer is recorded`() {
        val store = MemoryStore(); var available = false; var sends = 0
        val transport = object : Transport {
            override fun peers(nodeId: String) = setOf("B")
            override fun receiveWith(receiver: (String, String, Report) -> ReceiveResult) {}
            override fun send(from: String, to: String, report: Report, completed: (Boolean) -> Unit) { sends++; completed(available) }
        }
        val e = RelayEngine(store, transport, { now }); val r = report(); e.originate("A", r)
        e.relay("A"); assertEquals(DeliveryStatus.PENDING, store.get("A", r.id)!!.status)
        available = true; e.relay("A"); e.relay("A")
        assertEquals(2, sends); assertEquals(setOf("B"), store.get("A", r.id)!!.forwardedTo)
    }
    @Test fun `late ACK cannot revive expired report`() {
        val store = MemoryStore(); var callback: ((Boolean) -> Unit)? = null
        val transport = object : Transport {
            override fun peers(nodeId: String) = setOf("B")
            override fun receiveWith(receiver: (String, String, Report) -> ReceiveResult) {}
            override fun send(from: String, to: String, report: Report, completed: (Boolean) -> Unit) { callback = completed }
        }
        val e = RelayEngine(store, transport, { now }); val r = report(ttl = 1000); e.originate("A", r)
        e.relay("A"); now += 1001; e.relay("A"); callback!!(true)
        assertEquals(DeliveryStatus.EXPIRED, store.get("A", r.id)!!.status)
    }
    @Test fun `synchronous transport exception releases in flight state for retry`() {
        val store = MemoryStore(); var sends = 0
        val transport = object : Transport {
            override fun peers(nodeId: String) = setOf("B")
            override fun receiveWith(receiver: (String, String, Report) -> ReceiveResult) {}
            override fun send(from: String, to: String, report: Report, completed: (Boolean) -> Unit) {
                sends++
                if (sends == 1) throw SecurityException("Permission revoked")
                completed(true)
            }
        }
        val e = RelayEngine(store, transport, { now }); val r = report(); e.originate("A", r)
        e.relay("A"); assertEquals(DeliveryStatus.PENDING, store.get("A", r.id)!!.status)
        e.relay("A"); assertEquals(2, sends); assertEquals(DeliveryStatus.FORWARDED, store.get("A", r.id)!!.status)
    }
}
