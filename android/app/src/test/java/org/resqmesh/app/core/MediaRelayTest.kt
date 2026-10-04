package org.resqmesh.app.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.resqmesh.app.data.FileMediaStore
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class MediaRelayTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_800_000_000_000L
    private fun attachment(bytes: ByteArray, kind: String = "image") = Attachment(UUID.randomUUID().toString(), kind,
        if (kind == "image") "image/jpeg" else "$kind/mp4", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, if (kind == "image") null else 5000)
    private fun report(a: Attachment) = Report(UUID.randomUUID().toString(), "A", epoch, epoch + 1_800_000,
        QUICK_SOS_MESSAGE, null, null, simulation = true, schemaVersion = 4, messageSource = "preset", attachments = listOf(a))
    private class Reports : ReportStore {
        val rows = linkedMapOf<Pair<String, String>, StoredReport>()
        var received = false
        override fun get(nodeId: String, reportId: String) = rows[nodeId to reportId]
        override fun all(nodeId: String) = rows.values.filter { it.nodeId == nodeId }
        override fun save(stored: StoredReport) { rows[stored.nodeId to stored.report.id] = stored }
        override fun hasDeliveryReceipt(nodeId: String, reportId: String) = received
    }
    private fun network(vararg names: String) = SimulationTransport().also { sim ->
        names.forEach { sim.addNode(SimNode(it)) }
        names.toList().zipWithNext().forEach { (a, b) -> sim.setLink(a, b, true) }
    }
    private fun source(bytes: ByteArray) = temporary.newFile().also { it.writeBytes(bytes) }
    private fun packet(r: Report, a: Attachment, bytes: ByteArray, index: Int) = ControlPacket.MediaChunk(
        reportId = r.id, attachmentId = a.id, index = index,
        dataBase64 = Base64.getEncoder().encodeToString(bytes.copyOfRange(index * MEDIA_CHUNK_BYTES,
            minOf(bytes.size, (index + 1) * MEDIA_CHUNK_BYTES))), simulation = true, path = listOf("A"))

    @Test fun `manifest bounds and legacy contracts reject invalid media`() {
        val a = attachment(byteArrayOf(1))
        assertTrue(report(a).valid())
        for (version in 1..3) assertFalse(report(a).copy(schemaVersion = version).valid())
        assertFalse(a.copy(byteSize = 0).valid())
        assertFalse(a.copy(byteSize = 1_048_577).valid())
        assertFalse(a.copy(mimeType = "image/png").valid())
        assertFalse(a.copy(sha256 = "A".repeat(64)).valid())
        assertFalse(a.copy(durationMs = 1).valid())
        assertTrue(a.copy(kind = "audio", mimeType = "audio/mp4", durationMs = 30_000).valid())
        assertFalse(a.copy(kind = "audio", mimeType = "audio/mp4", durationMs = 30_001).valid())
        val video = a.copy(kind = "video", mimeType = "video/mp4", byteSize = 8_388_608, durationMs = 15_000)
        assertTrue(video.valid())
        assertFalse(video.copy(durationMs = 15_001).valid())
        assertFalse(video.copy(byteSize = 8_388_609).valid())
        assertFalse(validAttachments(listOf(a, a)))
        assertFalse(validAttachments(List(4) { a.copy(id = UUID.randomUUID().toString()) }))
        assertFalse(validAttachments(listOf(video, video.copy(id = UUID.randomUUID().toString()))))
    }

    @Test fun `alert precedes exact bytes across three hops and report receipt does not stop attachment relay`() {
        val bytes = ByteArray(MEDIA_CHUNK_BYTES * 3 + 13) { (it * 31).toByte() }
        val a = attachment(bytes); val r = report(a)
        val reports = Reports(); val transport = network("A", "B", "C", "Gateway")
        val store = FileMediaStore(temporary.newFolder())
        val engine = RelayEngine(reports, transport, { epoch })
        val relay = MediaRelay(reports, store, transport, { epoch }, true)
        transport.controlWith { node, from, p -> p is ControlPacket.MediaChunk && relay.receive(node, from, p) }
        assertTrue(engine.originate("A", r))
        assertTrue(store.importDraft("A", r.id, a, source(bytes)))
        assertFalse(relay.relay("A")) // Base report has not been sent or acknowledged yet.
        listOf("A", "B", "C").forEach(engine::relay)
        assertNotNull(reports.get("Gateway", r.id))
        assertFalse(store.available("B", r.id, a))
        assertFalse(store.available("Gateway", r.id, a))
        reports.received = true
        reports.rows.values.toList().forEach { reports.save(it.copy(status = DeliveryStatus.UPLOADED)) }
        repeat(16) { listOf("A", "B", "C", "Gateway").forEach { relay.relay(it) } }
        val files = listOf("A", "B", "C", "Gateway").map { node ->
            assertTrue("Complete media missing at $node", store.available(node, r.id, a))
            store.file(node, r.id, a)!!.also { assertArrayEquals(bytes, it.readBytes()) }
        }
        assertEquals(4, files.map(File::getAbsolutePath).distinct().size)
        assertFalse(relay.relay("A"))
        assertFalse(store.available("unrelated", r.id, a))
    }

    @Test fun `partial chunks survive restart and are never exposed as a complete attachment`() {
        val bytes = ByteArray(MEDIA_CHUNK_BYTES + 19) { it.toByte() }
        val a = attachment(bytes); val r = report(a); val root = temporary.newFolder()
        val store = FileMediaStore(root)
        assertTrue(store.putChunk("B", r.id, a, 0, bytes.copyOfRange(0, MEDIA_CHUNK_BYTES)))
        assertFalse(store.available("B", r.id, a)); assertNull(store.file("B", r.id, a))
        val restarted = FileMediaStore(root)
        assertTrue(restarted.putChunk("B", r.id, a, 1, bytes.copyOfRange(MEDIA_CHUNK_BYTES, bytes.size)))
        assertArrayEquals(bytes, restarted.file("B", r.id, a)!!.readBytes())
        assertTrue(restarted.putChunk("B", r.id, a, 0, bytes.copyOfRange(0, MEDIA_CHUNK_BYTES)))
        assertFalse(restarted.putChunk("B", r.id, a, 0, ByteArray(MEDIA_CHUNK_BYTES)))
        assertArrayEquals(bytes, restarted.file("B", r.id, a)!!.readBytes())
    }

    @Test fun `partition and lost ACK back off then resume durable chunks without duplicate bytes`() {
        val bytes = ByteArray(MEDIA_CHUNK_BYTES * 2 + 9) { it.toByte() }; val a = attachment(bytes); val r = report(a)
        val reports = Reports(); val base = network("A", "B"); var clock = epoch; var lost = false; var sends = 0
        val transport = object : Transport by base {
            override fun sendControl(from: String, to: String, packet: ControlPacket, completed: (Boolean) -> Unit) {
                sends++
                base.sendControl(from, to, packet) { ok ->
                    if (!lost && packet is ControlPacket.MediaChunk && packet.index == 1) { lost = true; completed(false) }
                    else completed(ok)
                }
            }
        }
        val store = FileMediaStore(temporary.newFolder()); val engine = RelayEngine(reports, transport, { clock })
        val relay = MediaRelay(reports, store, transport, { clock }, true)
        base.controlWith { node, from, p -> p is ControlPacket.MediaChunk && relay.receive(node, from, p) }
        assertTrue(engine.originate("A", r)); engine.relay("A"); assertTrue(store.importDraft("A", r.id, a, source(bytes)))
        base.setLink("A", "B", false); assertFalse(relay.relay("A")); assertEquals(0, sends)
        base.setLink("A", "B", true)
        assertTrue(relay.relay("A")); assertTrue(relay.relay("A")); assertEquals(2, sends)
        assertFalse(relay.relay("A")); assertEquals(2, sends)
        clock += 1000
        repeat(3) { assertTrue(relay.relay("A")) }
        assertArrayEquals(bytes, store.file("B", r.id, a)!!.readBytes())
    }

    @Test fun `corrupt assembly fails hash and sender can restart all chunks`() {
        val bytes = ByteArray(MEDIA_CHUNK_BYTES + 7) { (it * 7).toByte() }; val a = attachment(bytes); val r = report(a)
        val reports = Reports(); val base = network("A", "B"); var clock = epoch; var corrupt = true
        val transport = object : Transport by base {
            override fun sendControl(from: String, to: String, packet: ControlPacket, completed: (Boolean) -> Unit) {
                val outgoing = if (corrupt && packet is ControlPacket.MediaChunk && packet.index == 0) {
                    corrupt = false
                    packet.copy(dataBase64 = Base64.getEncoder().encodeToString(ByteArray(MEDIA_CHUNK_BYTES)))
                } else packet
                base.sendControl(from, to, outgoing, completed)
            }
        }
        val store = FileMediaStore(temporary.newFolder()); val engine = RelayEngine(reports, transport, { clock })
        val relay = MediaRelay(reports, store, transport, { clock }, true)
        base.controlWith { node, from, p -> p is ControlPacket.MediaChunk && relay.receive(node, from, p) }
        assertTrue(engine.originate("A", r)); engine.relay("A"); assertTrue(store.importDraft("A", r.id, a, source(bytes)))
        assertTrue(relay.relay("A")); assertTrue(relay.relay("A")); assertFalse(store.available("B", r.id, a))
        assertFalse(relay.relay("A")); clock += 1000
        assertTrue(relay.relay("A")); assertTrue(relay.relay("A"))
        assertArrayEquals(bytes, store.file("B", r.id, a)!!.readBytes())
    }

    @Test fun `chunk acceptance enforces mode manifest authenticated peer ttl and hop limits`() {
        val bytes = byteArrayOf(1, 2, 3); val a = attachment(bytes); val r = report(a)
        val reports = Reports(); val transport = network("A", "B", "C"); val store = FileMediaStore(temporary.newFolder())
        val engine = RelayEngine(reports, transport, { epoch }); val relay = MediaRelay(reports, store, transport, { epoch }, true)
        val p = packet(r, a, bytes, 0)
        assertFalse(relay.receive("B", "A", p)) // No alert manifest yet.
        assertTrue(engine.originate("A", r)); engine.relay("A")
        assertFalse(relay.receive("B", "A", p.copy(simulation = false)))
        assertFalse(relay.receive("B", "A", p.copy(reportId = UUID.randomUUID().toString())))
        assertFalse(relay.receive("B", "unknown", p))
        assertFalse(relay.receive("B", "A", p.copy(attachmentId = UUID.randomUUID().toString())))
        assertFalse(relay.receive("B", "A", p.copy(index = 1)))
        assertFalse(relay.receive("B", "A", p.copy(path = listOf("A", "B", "A"))))
        assertFalse(relay.receive("B", "A", p.copy(path = listOf("other", "A"))))
        assertFalse(relay.receive("B", "A", p.copy(dataBase64 = "a".repeat(MEDIA_MAX_BASE64_CHARS + 1))))
        assertFalse(relay.receive("B", "A", p.copy(dataBase64 = "!@#$")))
        val stored = reports.get("B", r.id)!!
        reports.save(stored.copy(report = stored.report.copy(expiresAt = epoch)))
        assertFalse(relay.receive("B", "A", p))
        reports.save(stored.copy(report = stored.report.copy(maxHops = 1)))
        assertFalse(relay.receive("B", "C", p.copy(path = listOf("A", "C"))))
        assertTrue(relay.receive("B", "A", p))
        assertFalse(relay.relay("B")) // Holder has reached maxHops.
    }

    @Test fun `conflicting partial duplicate cannot erase persisted valid chunk`() {
        val bytes = ByteArray(MEDIA_CHUNK_BYTES + 1) { (it + 2).toByte() }; val a = attachment(bytes); val r = report(a)
        val store = FileMediaStore(temporary.newFolder())
        assertTrue(store.putChunk("B", r.id, a, 0, bytes.copyOfRange(0, MEDIA_CHUNK_BYTES)))
        assertFalse(store.putChunk("B", r.id, a, 0, ByteArray(MEDIA_CHUNK_BYTES)))
        assertTrue(store.putChunk("B", r.id, a, 1, bytes.copyOfRange(MEDIA_CHUNK_BYTES, bytes.size)))
        assertArrayEquals(bytes, store.file("B", r.id, a)!!.readBytes())
    }

    @Test fun `import hash failure remains unavailable and backend observations are scoped independently`() {
        val bytes = byteArrayOf(1, 2, 3); val a = attachment(bytes); val r = report(a); val root = temporary.newFolder()
        val store = FileMediaStore(root)
        assertFalse(store.importDraft("A", r.id, a, source(byteArrayOf(3, 2, 1))))
        assertFalse(store.available("A", r.id, a))
        assertTrue(store.markUploaded("B", r.id, a, "https://backend-one"))
        assertTrue(FileMediaStore(root).uploaded("B", r.id, a, "https://backend-one"))
        assertFalse(store.available("B", r.id, a))
        assertFalse(store.uploaded("A", r.id, a, "https://backend-one"))
        assertFalse(store.uploaded("B", r.id, a, "https://backend-two"))
        assertTrue(store.importDraft("A", r.id, a, source(bytes)))
        assertArrayEquals(bytes, store.file("A", r.id, a)!!.readBytes())
    }

    @Test fun `reopening detects same length disk corruption and low disk never exposes partial bytes`() {
        val bytes = byteArrayOf(1, 2, 3); val a = attachment(bytes); val r = report(a); val root = temporary.newFolder()
        val store = FileMediaStore(root)
        assertTrue(store.importDraft("A", r.id, a, source(bytes)))
        store.file("A", r.id, a)!!.writeBytes(byteArrayOf(3, 2, 1))
        assertFalse(FileMediaStore(root).available("A", r.id, a))
        val noSpace = FileMediaStore(temporary.newFolder(), minimumFreeBytes = Long.MAX_VALUE)
        assertFalse(noSpace.importDraft("B", r.id, a, source(bytes)))
        assertFalse(noSpace.putChunk("B", r.id, a, 0, bytes))
        assertFalse(noSpace.available("B", r.id, a))
        assertTrue(FileMediaStore(root).importDraft("A", r.id, a, source(bytes)))
        assertArrayEquals(bytes, FileMediaStore(root).file("A", r.id, a)!!.readBytes())
    }

    @Test fun `one pass is bounded and outstanding async chunks do not spin`() {
        val bytes = byteArrayOf(1, 2, 3); val a = attachment(bytes); val r = report(a)
        val reports = Reports(); val base = network("A")
        (1..6).forEach { base.addNode(SimNode("peer$it")); base.setLink("A", "peer$it", true) }
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val transport = object : Transport by base {
            override fun sendControl(from: String, to: String, packet: ControlPacket, completed: (Boolean) -> Unit) { callbacks.add(completed) }
        }
        val store = FileMediaStore(temporary.newFolder()); val engine = RelayEngine(reports, transport, { epoch })
        val relay = MediaRelay(reports, store, transport, { epoch }, true)
        assertTrue(engine.originate("A", r)); engine.relay("A"); assertTrue(store.importDraft("A", r.id, a, source(bytes)))
        assertTrue(relay.relay("A")); assertEquals(4, callbacks.size)
        assertTrue(relay.relay("A")); assertEquals(6, callbacks.size)
        assertFalse(relay.relay("A")); assertEquals(6, callbacks.size)
        callbacks.forEach { it(false) }
        assertFalse(relay.relay("A"))
    }
}
