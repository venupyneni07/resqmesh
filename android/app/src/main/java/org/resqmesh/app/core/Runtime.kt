package org.resqmesh.app.core

import java.util.UUID

enum class PeerPhase { DISCOVERED, CONNECTING, AUTHENTICATING, CONNECTED, LOST, REJECTED }
data class PeerState(val id: String, val phase: PeerPhase, val lastSeen: Long)
sealed class TransportEvent {
    data class PeerChanged(val local: String, val peer: PeerState) : TransportEvent()
    data class StateChanged(val local: String, val state: String, val detail: String? = null) : TransportEvent()
    data class EnvironmentChanged(val nodeId: String) : TransportEvent()
}
data class JourneyEvent(val id: String = UUID.randomUUID().toString(), val nodeId: String, val reportId: String,
    val kind: String, val at: Long, val peerId: String? = null, val path: List<String> = emptyList(), val receiptId: String? = null)
data class BackendReceipt(val id: String, val reportId: String, val incidentId: String, val type: String,
    val timestamp: Long, val gatewayId: String?, val simulation: Boolean, val relayPath: List<String>,
    val issuer: String = "resqmesh_backend", val trust: String = "backend_issued_unattested") {
    fun valid() = runCatching {
        listOf(id, reportId, incidentId).all { UUID.fromString(it).toString() == it.lowercase() } &&
            type in setOf("backend_received", "responder_acknowledged") && timestamp > 0 &&
            issuer == "resqmesh_backend" && trust == "backend_issued_unattested" &&
            (gatewayId == null || gatewayId.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) &&
            (type != "backend_received" || gatewayId == relayPath.lastOrNull()) &&
            relayPath.isNotEmpty() && relayPath.size <= 9 && relayPath.distinct().size == relayPath.size &&
            relayPath.all { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) }
    }.getOrDefault(false)
    fun consistentWith(report: Report) = valid() && reportId == report.id && simulation == report.simulation &&
        relayPath.first() == report.originId && relayPath.size <= report.maxHops + 1 && timestamp >= report.createdAt - 5 * 60_000 &&
        (type != "backend_received" || timestamp <= report.expiresAt + 5 * 60_000)
}
data class ReceiptPacket(val receipt: BackendReceipt, val path: List<String>, val expiresAt: Long,
    val forwardedTo: Set<String> = emptySet())
interface ReceiptStore {
    fun receipt(node: String, id: String): ReceiptPacket?
    fun receipts(node: String): List<ReceiptPacket>
    fun saveReceipt(node: String, packet: ReceiptPacket)
}
sealed class ControlPacket(open val id: String) {
    data class Inventory(override val id: String = UUID.randomUUID().toString(), val reports: Set<String>, val receipts: Set<String>) : ControlPacket(id)
    data class Capability(override val id: String = UUID.randomUUID().toString(), val nodeId: String,
        val reachable: Boolean, val observedAt: Long, val expiresAt: Long) : ControlPacket(id)
    data class Delivery(override val id: String = UUID.randomUUID().toString(), val packet: ReceiptPacket) : ControlPacket(id)
    data class MediaChunk(override val id: String = UUID.randomUUID().toString(), val reportId: String,
        val attachmentId: String, val index: Int, val dataBase64: String, val simulation: Boolean,
        val path: List<String>) : ControlPacket(id)
}

/** Receipt gossip has its own path/TTL. Knowing a remote report copy is never a delivery receipt. */
class ReceiptRelay(private val store: ReceiptStore, private val transport: Transport, private val now: () -> Long,
    private val onJourney: (JourneyEvent) -> Unit = {}) {
    private val sending = mutableSetOf<Triple<String, String, String>>()
    fun installFromBackend(node: String, receipt: BackendReceipt): Boolean = accept(node,
        ReceiptPacket(receipt, listOf(node), receipt.timestamp + 24 * 60 * 60_000L))
    private fun accept(node: String, packet: ReceiptPacket): Boolean {
        if (!packet.receipt.valid() || packet.receipt.timestamp > now() + 5 * 60_000 || packet.expiresAt <= now() ||
            packet.expiresAt > packet.receipt.timestamp + 24 * 60 * 60_000L || packet.path.size !in 1..9 || packet.path.distinct().size != packet.path.size ||
            packet.path.any { !it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) }) return false
        val known = (store as? ReportStore)?.get(node, packet.receipt.reportId)?.report
        if (known != null && !packet.receipt.consistentWith(known)) return false
        val existing = store.receipt(node, packet.receipt.id)
        if (existing != null) return existing.receipt == packet.receipt
        store.saveReceipt(node, packet.copy(forwardedTo = emptySet()))
        onJourney(JourneyEvent(nodeId = node, reportId = packet.receipt.reportId, kind = packet.receipt.type,
            at = now(), peerId = packet.path.dropLast(1).lastOrNull(), path = packet.receipt.relayPath, receiptId = packet.receipt.id))
        return true
    }
    fun receive(node: String, from: String, packet: ReceiptPacket): Boolean {
        val existing = store.receipt(node, packet.receipt.id)
        if (existing != null) return existing.receipt == packet.receipt
        if (packet.path.lastOrNull() != from || node in packet.path || packet.path.size >= 9) return false
        return accept(node, packet.copy(path = packet.path + node))
    }
    fun relay(node: String) {
        store.receipts(node).filter { it.expiresAt > now() && it.path.size < 9 }.forEach { packet ->
            transport.peers(node).sorted().filter { it !in packet.path && it !in packet.forwardedTo && packet.receipt.id !in transport.knownReceipts(node, it) }.forEach { peer ->
                val key = Triple(node, packet.receipt.id, peer)
                if (sending.add(key)) {
                    try { transport.sendControl(node, peer, ControlPacket.Delivery(packet = packet)) { ok ->
                        sending.remove(key)
                        if (ok) store.receipt(node, packet.receipt.id)?.let {
                            store.saveReceipt(node, it.copy(forwardedTo = it.forwardedTo + peer))
                        }
                    } } catch (_: Exception) { sending.remove(key) }
                }
            }
        }
    }
}

data class SimNode(val id: String, val internet: Boolean = false)
/** Only environmental conditions live here. No designated route or designated gateway. */
class SimulationEnvironment {
    val nodes = linkedMapOf<String, SimNode>()
    val links = linkedSetOf<Set<String>>()
    fun peers(id: String) = links.filter { id in it && it.all(nodes::containsKey) }.flatMap { it - id }.toSet()
}
class SimulationTransport(val environment: SimulationEnvironment = SimulationEnvironment(), private val now: () -> Long = System::currentTimeMillis) : Transport {
    private var receiver: ((String, String, Report) -> ReceiveResult)? = null
    private var control: ((String, String, ControlPacket) -> Boolean)? = null
    private var listener: (TransportEvent) -> Unit = {}
    private val inventories = mutableMapOf<Pair<String, String>, Set<String>>()
    private val receiptInventories = mutableMapOf<Pair<String, String>, Set<String>>()
    override fun receiveWith(receiver: (String, String, Report) -> ReceiveResult) { this.receiver = receiver }
    override fun controlWith(receiver: (String, String, ControlPacket) -> Boolean) { control = receiver }
    override fun eventsWith(listener: (TransportEvent) -> Unit) { this.listener = listener }
    override fun peers(nodeId: String) = environment.peers(nodeId)
    override fun peerStates(nodeId: String) = peers(nodeId).map { PeerState(it, PeerPhase.CONNECTED, now()) }
    override fun knownReports(nodeId: String, peer: String) = inventories[nodeId to peer] ?: emptySet()
    override fun knownReceipts(nodeId: String, peer: String) = receiptInventories[nodeId to peer] ?: emptySet()
    fun addNode(node: SimNode) { require(node.id.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))); environment.nodes[node.id] = node; listener(TransportEvent.EnvironmentChanged(node.id)) }
    fun removeNode(id: String) {
        peers(id).toList().forEach { setLink(id, it, false) }
        environment.nodes.remove(id); listener(TransportEvent.EnvironmentChanged(id))
    }
    fun setInternet(id: String, enabled: Boolean) {
        environment.nodes[id]?.let { environment.nodes[id] = it.copy(internet = enabled); listener(TransportEvent.EnvironmentChanged(id)) }
    }
    fun setLink(a: String, b: String, connected: Boolean) {
        require(a != b && a in environment.nodes && b in environment.nodes)
        val changed = if (connected) environment.links.add(setOf(a, b)) else environment.links.remove(setOf(a, b))
        if (!changed) return
        if (!connected) { inventories.remove(a to b); inventories.remove(b to a); receiptInventories.remove(a to b); receiptInventories.remove(b to a) }
        listOf(a to b, b to a).forEach { (local, peer) -> listener(TransportEvent.PeerChanged(local,
            PeerState(peer, if (connected) PeerPhase.CONNECTED else PeerPhase.LOST, now()))) }
    }
    override fun send(from: String, to: String, report: Report, completed: (Boolean) -> Unit) {
        if (to !in peers(from)) { completed(false); return }
        val result = receiver?.invoke(to, from, report)
        completed(result == ReceiveResult.ACCEPTED || result == ReceiveResult.DUPLICATE)
    }
    override fun sendControl(from: String, to: String, packet: ControlPacket, completed: (Boolean) -> Unit) {
        if (to !in peers(from)) { completed(false); return }
        if (packet is ControlPacket.Inventory) { inventories[to to from] = packet.reports; receiptInventories[to to from] = packet.receipts }
        completed(control?.invoke(to, from, packet) == true)
    }
}
