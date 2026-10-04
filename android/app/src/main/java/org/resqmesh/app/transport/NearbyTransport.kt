package org.resqmesh.app.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import org.resqmesh.app.core.*
import org.resqmesh.app.data.Wire
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Foreground-only P2P_CLUSTER adapter. No success is reported before a remote persisted-copy ACK. */
class NearbyTransport(
    context: Context, private val nodeId: String, private val serial: ScheduledExecutorService,
    private val log: (String) -> Unit,
    private val authenticate: (peer: String, code: String, answer: (Boolean) -> Unit) -> Unit
) : Transport {
    private val client = Nearby.getConnectionsClient(context)
    private val serviceId = "org.resqmesh.app.relay.v1"
    private val endpointNodes = mutableMapOf<String, String>()
    private val connected = mutableSetOf<String>()
    private val connecting = mutableSetOf<String>()
    private val discovered = mutableMapOf<String, String>()
    private val rejected = mutableSetOf<String>()
    private val retryCounts = mutableMapOf<String, Int>()
    private val retryTasks = mutableMapOf<String, ScheduledFuture<*>>()
    private val acknowledgements = Acknowledgements()
    private var receiver: ((String, String, Report) -> ReceiveResult)? = null
    private var controlReceiver: ((String, String, ControlPacket) -> Boolean)? = null
    private var eventListener: (TransportEvent) -> Unit = {}
    private val observedPeers = mutableMapOf<String, PeerState>()
    private val inventories = mutableMapOf<String, Set<String>>()
    private val receiptInventories = mutableMapOf<String, Set<String>>()
    private var active = false
    private var discoveryRestart: ScheduledFuture<*>? = null
    private fun peerEvent(peer: String, phase: PeerPhase) {
        val state = PeerState(peer, phase, System.currentTimeMillis()); observedPeers[peer] = state
        eventListener(TransportEvent.PeerChanged(nodeId, state))
    }
    private fun stateEvent(state: String, detail: String? = null) = eventListener(TransportEvent.StateChanged(nodeId, state, detail))

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpoint: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (bytes.size > 24_000) return
            serial.execute {
                if (!active || endpoint !in connected) return@execute
                runCatching {
                    val frame = JSONObject(String(bytes, Charsets.UTF_8))
                    when (frame.getString("type")) {
                        "report" -> {
                            val report = Wire.decode(frame.getJSONObject("report"))
                            if (report.simulation) { log("Rejected simulated packet on physical transport"); return@runCatching }
                            val sender = endpointNodes[endpoint] ?: return@runCatching
                            val result = receiver?.invoke(nodeId, sender, report)
                            val ok = result == ReceiveResult.ACCEPTED || result == ReceiveResult.DUPLICATE
                            val ack = JSONObject().put("type", "ack").put("id", report.id).put("stored", ok)
                            client.sendPayload(endpoint, Payload.fromBytes(ack.toString().toByteArray()))
                        }
                        "ack" -> acknowledgements.acknowledge(endpoint, frame.opt("id"), frame.opt("stored"))
                        "inventory", "capability", "receipt", "media_chunk" -> {
                            val peer = endpointNodes[endpoint] ?: return@runCatching
                            val packet = Wire.control(frame)
                            if (packet is ControlPacket.Delivery && packet.packet.receipt.simulation) return@runCatching
                            if (packet is ControlPacket.MediaChunk && packet.simulation) return@runCatching
                            if (packet is ControlPacket.Inventory) { inventories[peer] = packet.reports; receiptInventories[peer] = packet.receipts }
                            val accepted = controlReceiver?.invoke(nodeId, peer, packet) == true
                            client.sendPayload(endpoint, Payload.fromBytes(JSONObject().put("type", "ack").put("id", packet.id)
                                .put("stored", accepted).toString().toByteArray()))
                        }
                    }
                }.onFailure { log("Invalid nearby packet ignored") }
            }
        }
        override fun onPayloadTransferUpdate(endpoint: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) serial.execute { log("Radio transfer failed; packet will retry") }
        }
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpoint: String, info: ConnectionInfo) {
            serial.execute {
                val peer = info.endpointName.removePrefix("ResQMesh:")
                if (!active || !info.endpointName.startsWith("ResQMesh:") || peer == nodeId || !peer.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) {
                    client.rejectConnection(endpoint); return@execute
                }
                endpointNodes[endpoint] = peer
                peerEvent(peer, PeerPhase.AUTHENTICATING)
                authenticate(peer, info.authenticationDigits) { accepted -> serial.execute {
                    if (active && accepted) client.acceptConnection(endpoint, payloadCallback)
                        .addOnFailureListener { serial.execute { log("Accept failed: ${it.message}") } }
                    else {
                        rejected.add(endpoint); retryTasks.remove(endpoint)?.cancel(false)
                        peerEvent(peer, PeerPhase.REJECTED)
                        client.rejectConnection(endpoint)
                    }
                } }
            }
        }
        override fun onConnectionResult(endpoint: String, resolution: ConnectionResolution) { serial.execute {
            connecting.remove(endpoint)
            if (active && resolution.status.isSuccess) {
                connected.add(endpoint); retryTasks.remove(endpoint)?.cancel(false); retryCounts.remove(endpoint)
                endpointNodes[endpoint]?.let { peerEvent(it, PeerPhase.CONNECTED) }; stateEvent("active")
                log("Peer ${endpointNodes[endpoint]} connected and authenticated")
            } else {
                val peer = endpointNodes.remove(endpoint)
                peer?.let { peerEvent(it, if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) PeerPhase.REJECTED else PeerPhase.DISCOVERED) }
                log("Peer connection failed or rejected: ${resolution.status.statusCode}")
                if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) rejected.add(endpoint)
                else schedulePeerRetry(endpoint)
            }
        } }
        override fun onDisconnected(endpoint: String) { serial.execute {
            val name = endpointNodes.remove(endpoint)
            name?.let { inventories.remove(it); receiptInventories.remove(it); peerEvent(it, PeerPhase.LOST) }
            connected.remove(endpoint); connecting.remove(endpoint)
            acknowledgements.disconnect(endpoint)
            if (connected.isEmpty()) stateEvent("searching")
            log("Peer $name disconnected; reports retained locally")
            schedulePeerRetry(endpoint)
            restartDiscoverySoon()
        } }
    }
    private fun restartDiscoverySoon() {
        if (!active || discoveryRestart != null) return
        discoveryRestart = serial.schedule({
            discoveryRestart = null
            if (active) {
                client.stopDiscovery()
                client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener { serial.execute { log("Discovery restart failed: ${it.message}"); close() } }
            }
        }, 5, TimeUnit.SECONDS)
    }
    private fun requestPeer(endpoint: String) {
        val peer = discovered[endpoint] ?: return
        if (!active || nodeId >= peer || endpoint in connected || endpoint in rejected || endpoint in connecting) return
        connecting.add(endpoint)
        peerEvent(peer, PeerPhase.CONNECTING)
        client.requestConnection("ResQMesh:$nodeId", endpoint, lifecycle).addOnFailureListener {
            serial.execute { connecting.remove(endpoint); log("Peer request failed: ${it.message}"); schedulePeerRetry(endpoint) }
        }
    }
    private fun schedulePeerRetry(endpoint: String) {
        val peer = discovered[endpoint] ?: return
        if (!active || nodeId >= peer || endpoint in rejected || endpoint in connected || endpoint in retryTasks) return
        val attempt = ((retryCounts[endpoint] ?: 0) + 1).coerceAtMost(4)
        retryCounts[endpoint] = attempt
        val delaySeconds = if (attempt == 4) 30L else 3L * (1L shl (attempt - 1))
        log("Retrying peer $peer in ${delaySeconds}s; queued reports retained")
        retryTasks[endpoint] = serial.schedule({ retryTasks.remove(endpoint); requestPeer(endpoint) }, delaySeconds, TimeUnit.SECONDS)
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpoint: String, info: DiscoveredEndpointInfo) { serial.execute {
            if (!active || !info.endpointName.startsWith("ResQMesh:")) return@execute
            val peer = info.endpointName.removePrefix("ResQMesh:")
            if (!peer.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) return@execute
            discovered[endpoint] = peer
            if (endpoint !in connected && endpoint !in connecting) peerEvent(peer, PeerPhase.DISCOVERED)
            // One side initiates, avoiding symmetric simultaneous request races.
            if (endpoint !in retryTasks) requestPeer(endpoint)
        } }
        override fun onEndpointLost(endpoint: String) { serial.execute {
            val peer = discovered.remove(endpoint); connecting.remove(endpoint)
            if (endpoint !in connected) peer?.let { peerEvent(it, PeerPhase.LOST) }
            retryTasks.remove(endpoint)?.cancel(false); retryCounts.remove(endpoint)
        } }
    }
    fun start() {
        if (active) {
            retryCounts.clear(); rejected.clear()
            discovered.keys.toList().forEach(::requestPeer)
            restartDiscoverySoon(); log("Nearby retry requested; pending reports retained")
            return
        }
        active = true
        stateEvent("starting")
        client.startAdvertising("ResQMesh:$nodeId", serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnSuccessListener { serial.execute { log("Nearby advertising active") } }
            .addOnFailureListener { serial.execute { log("Advertising failed: ${it.message}"); close(); stateEvent("failed", it.message) } }
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnSuccessListener { serial.execute { log("Nearby discovery active"); stateEvent("searching") } }
            .addOnFailureListener { serial.execute { log("Discovery failed: ${it.message}"); close(); stateEvent("failed", it.message) } }
    }
    override fun peers(nodeId: String): Set<String> = connected.mapNotNull { endpointNodes[it] }.toSet()
    override fun peerStates(nodeId: String) = observedPeers.values.filter { it.phase != PeerPhase.LOST }.toList()
    override fun knownReports(nodeId: String, peer: String) = inventories[peer] ?: emptySet()
    override fun knownReceipts(nodeId: String, peer: String) = receiptInventories[peer] ?: emptySet()
    override fun eventsWith(listener: (TransportEvent) -> Unit) { eventListener = listener }
    override fun controlWith(receiver: (String, String, ControlPacket) -> Boolean) { controlReceiver = receiver }
    override fun receiveWith(receiver: (String, String, Report) -> ReceiveResult) { this.receiver = receiver }
    override fun send(from: String, to: String, report: Report, completed: (Boolean) -> Unit) {
        sendFrame(to, report.id, JSONObject().put("type", "report").put("report", Wire.encode(report)), completed)
    }
    override fun sendControl(from: String, to: String, packet: ControlPacket, completed: (Boolean) -> Unit) {
        sendFrame(to, packet.id, Wire.control(packet), completed)
    }
    private fun sendFrame(to: String, id: String, frame: JSONObject, completed: (Boolean) -> Unit) {
        val endpoint = connected.firstOrNull { endpointNodes[it] == to }
        if (!active || endpoint == null) { completed(false); return }
        acknowledgements.register(endpoint, id, completed)
        serial.schedule({ acknowledgements.timeout(endpoint, id, completed) }, 12, TimeUnit.SECONDS)
        try { client.sendPayload(endpoint, Payload.fromBytes(frame.toString().toByteArray()))
            .addOnFailureListener { serial.execute { acknowledgements.timeout(endpoint, id, completed) } }
        } catch (_: Exception) { acknowledgements.timeout(endpoint, id, completed) }
    }
    override fun close() {
        active = false
        discoveryRestart?.cancel(false); discoveryRestart = null
        retryTasks.values.forEach { it.cancel(false) }; retryTasks.clear(); retryCounts.clear(); rejected.clear(); discovered.clear()
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        connected.clear(); connecting.clear(); endpointNodes.clear()
        observedPeers.clear(); inventories.clear(); receiptInventories.clear()
        acknowledgements.clear()
        log("Nearby stopped; foreground controls required to restart")
        stateEvent("stopped")
    }
}
