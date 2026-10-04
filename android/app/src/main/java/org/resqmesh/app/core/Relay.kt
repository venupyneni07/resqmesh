package org.resqmesh.app.core

import java.util.UUID

const val QUICK_SOS_MESSAGE = "Help needed; details unavailable."
val QUICK_NEEDS = setOf("cannot_move", "cannot_speak", "people_injured")

/** An observation, not a guarantee that the person is still at this location. */
data class LocationContext(
    val source: String = "unknown", val observedAt: Long? = null,
    val latitude: Double? = null, val longitude: Double? = null, val accuracyM: Double? = null
) {
    fun validAt(createdAt: Long): Boolean {
        if (source == "unknown") return observedAt == null && latitude == null && longitude == null && accuracyM == null
        if (source !in setOf("manual", "saved", "device")) return false
        val observed = observedAt ?: return false
        if (observed <= 0 || createdAt <= 0 || (observed > createdAt && observed - createdAt > 300_000L)) return false
        if ((latitude == null) != (longitude == null)) return false
        if (latitude != null && (!latitude.isFinite() || latitude !in -90.0..90.0)) return false
        if (longitude != null && (!longitude.isFinite() || longitude !in -180.0..180.0)) return false
        if (accuracyM != null && (latitude == null || !accuracyM.isFinite() || accuracyM < 0)) return false
        return source != "device" || latitude != null
    }
}

data class SosMessage(val text: String, val source: String)
fun sosMessage(text: String): SosMessage = text.trim().let {
    if (it.isEmpty()) SosMessage(QUICK_SOS_MESSAGE, "preset") else SosMessage(it, "user")
}

/** Immutable wire envelope. Timestamps are Unix milliseconds; path includes the current holder. */
data class Report(
    val id: String, val originId: String, val createdAt: Long, val expiresAt: Long,
    val text: String, val building: String?, val zone: String?, val hopCount: Int = 0,
    val maxHops: Int = 8, val relayPath: List<String> = listOf(originId), val simulation: Boolean,
    val schemaVersion: Int = 1, val emergencyType: String? = null, val locationText: String? = null,
    val floor: String? = null, val room: String? = null, val peopleAffected: Int? = null, val vulnerability: String? = null,
    val messageSource: String = "user", val quickNeeds: List<String> = emptyList(),
    val locationContext: LocationContext = LocationContext(),
    val attachments: List<Attachment> = emptyList()
) {
    fun valid(): Boolean = runCatching {
        UUID.fromString(id).toString() == id.lowercase() && originId.isNotBlank() &&
            text.isNotBlank() && text.length <= 2000 && createdAt > 0 && expiresAt > createdAt &&
            expiresAt - createdAt <= 86_400_000L && maxHops in 1..8 && hopCount in 0..maxHops &&
            relayPath.size == hopCount + 1 && relayPath.first() == originId &&
            relayPath.toSet().size == relayPath.size && relayPath.all { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) } &&
            validOptional(building, 120) && validOptional(zone, 120) && schemaVersion in 1..4 &&
            (schemaVersion >= 4 || attachments.isEmpty()) && validAttachments(attachments) &&
            (emergencyType == null || emergencyType in EMERGENCY_TYPES) &&
            validOptional(locationText, 240) && validOptional(floor, 40) && validOptional(room, 40) &&
            validOptional(vulnerability, 240) && (peopleAffected == null || peopleAffected in 0..10000) &&
            (schemaVersion >= 3 || (messageSource == "user" && quickNeeds.isEmpty() && locationContext == LocationContext())) &&
            (schemaVersion < 3 || (messageSource in setOf("preset", "user") &&
                (messageSource != "preset" || text == QUICK_SOS_MESSAGE) &&
                listOf(text, building, zone, locationText, floor, room, vulnerability).all { value ->
                    value == null || value.none { it.code < 32 && it !in "\n\r\t" }
                } &&
                quickNeeds.size == quickNeeds.toSet().size && quickNeeds.all { it in QUICK_NEEDS } &&
                locationContext.validAt(createdAt)))
    }.getOrDefault(false)
}
val EMERGENCY_TYPES = listOf("medical", "fire", "flood", "accident", "trapped", "safety_threat", "other")
private fun validOptional(value: String?, max: Int) = value == null || (value.isNotBlank() && value.length <= max)

enum class DeliveryStatus { PENDING, RECEIVED, FORWARDED, UPLOADED, EXPIRED }
data class StoredReport(val nodeId: String, val report: Report, val status: DeliveryStatus,
    val forwardedTo: Set<String> = emptySet(), val incidentId: String? = null)
interface ReportStore {
    fun get(nodeId: String, reportId: String): StoredReport?
    fun all(nodeId: String): List<StoredReport>
    fun save(stored: StoredReport)
    fun hasDeliveryReceipt(nodeId: String, reportId: String): Boolean = false
}
enum class ReceiveResult { ACCEPTED, DUPLICATE, EXPIRED, HOP_LIMIT, LOOP, INVALID }

/** Transport knows only peers and bytes/envelopes. Radio routing and AI never enter this core. */
interface Transport {
    fun peers(nodeId: String): Set<String>
    fun receiveWith(receiver: (nodeId: String, from: String, report: Report) -> ReceiveResult)
    /** Success means the peer acknowledged its persisted copy, not backend delivery. */
    fun send(from: String, to: String, report: Report, completed: (Boolean) -> Unit)
    fun close() {}
    fun knownReports(nodeId: String, peer: String): Set<String> = emptySet()
    fun knownReceipts(nodeId: String, peer: String): Set<String> = emptySet()
    fun controlWith(receiver: (String, String, ControlPacket) -> Boolean) {}
    fun sendControl(from: String, to: String, packet: ControlPacket, completed: (Boolean) -> Unit) { completed(false) }
    fun eventsWith(listener: (TransportEvent) -> Unit) {}
    fun peerStates(nodeId: String): List<PeerState> = peers(nodeId).map { PeerState(it, PeerPhase.CONNECTED, 0) }
}

class RelayEngine(
    private val store: ReportStore, private val transport: Transport, private val now: () -> Long,
    private val event: (String, String) -> Unit = { _, _ -> },
    private val onJourney: (JourneyEvent) -> Unit = {}
) {
    private val sending = mutableSetOf<String>()
    init { transport.receiveWith(::receive) }

    fun originate(nodeId: String, report: Report): Boolean {
        if (!report.valid() || report.originId != nodeId || report.relayPath != listOf(nodeId) ||
            report.hopCount != 0 || report.expiresAt <= now() || report.createdAt > now() + 60_000 ||
            store.get(nodeId, report.id) != null) return false
        store.save(StoredReport(nodeId, report, DeliveryStatus.PENDING))
        event(nodeId, "SOS ${report.id.take(8)} saved locally · pending")
        onJourney(JourneyEvent(nodeId = nodeId, reportId = report.id, kind = "created", at = now(), path = report.relayPath))
        return true
    }

    fun receive(nodeId: String, from: String, report: Report): ReceiveResult {
        fun rejected(reason: ReceiveResult): ReceiveResult {
            event(nodeId, "${report.id.take(8)} rejected: ${reason.name.lowercase()}")
            return reason
        }
        if (!report.valid() || report.relayPath.last() != from || report.createdAt > now() + 60_000)
            return rejected(ReceiveResult.INVALID)
        if (report.expiresAt <= now()) return rejected(ReceiveResult.EXPIRED)
        val existing = store.get(nodeId, report.id)
        if (existing != null) {
            // UUID reuse with changed immutable content is not a valid retransmission.
            if (report.copy(hopCount = existing.report.hopCount, relayPath = existing.report.relayPath) != existing.report)
                return rejected(ReceiveResult.INVALID)
            event(nodeId, "Duplicate ${report.id.take(8)} ignored")
            return ReceiveResult.DUPLICATE
        }
        if (nodeId in report.relayPath) return rejected(ReceiveResult.LOOP)
        if (report.hopCount >= report.maxHops) return rejected(ReceiveResult.HOP_LIMIT)
        val received = report.copy(hopCount = report.hopCount + 1, relayPath = report.relayPath + nodeId)
        store.save(StoredReport(nodeId, received, DeliveryStatus.RECEIVED))
        event(nodeId, "Received ${report.id.take(8)} · ${received.relayPath.joinToString(" → ")}")
        onJourney(JourneyEvent(nodeId = nodeId, reportId = report.id, kind = "received", at = now(), peerId = from, path = received.relayPath))
        return ReceiveResult.ACCEPTED
    }

    /** Local maintenance remains active when the user pauses forwarding. Never touches transport. */
    fun expire(nodeId: String) {
        for (item in store.all(nodeId)) {
            val report = item.report
            if (item.status in setOf(DeliveryStatus.UPLOADED, DeliveryStatus.EXPIRED) || store.hasDeliveryReceipt(nodeId, report.id)) continue
            if (report.expiresAt <= now()) {
                store.save(item.copy(status = DeliveryStatus.EXPIRED))
                event(nodeId, "${report.id.take(8)} expired; forwarding stopped")
                onJourney(JourneyEvent(nodeId = nodeId, reportId = report.id, kind = "expired", at = now(), path = report.relayPath))
            }
        }
    }
    /** Deterministic one-node pass. Caller serializes calls and callbacks. */
    fun relay(nodeId: String) {
        expire(nodeId)
        for (item in store.all(nodeId)) {
            val report = item.report
            if (item.status in setOf(DeliveryStatus.UPLOADED, DeliveryStatus.EXPIRED) || store.hasDeliveryReceipt(nodeId, report.id)) continue
            if (report.hopCount >= report.maxHops) continue
            for (peer in transport.peers(nodeId).sorted()) {
                val key = "$nodeId:${report.id}:$peer"
                if (peer in report.relayPath || peer in item.forwardedTo || key in sending || report.id in transport.knownReports(nodeId, peer)) continue
                sending.add(key)
                try { transport.send(nodeId, peer, report) { acknowledged ->
                    sending.remove(key)
                    if (acknowledged) {
                        val latest = store.get(nodeId, report.id) ?: return@send
                        val status = when {
                            latest.status == DeliveryStatus.UPLOADED -> DeliveryStatus.UPLOADED
                            latest.status == DeliveryStatus.EXPIRED || latest.report.expiresAt <= now() -> DeliveryStatus.EXPIRED
                            else -> DeliveryStatus.FORWARDED
                        }
                        store.save(latest.copy(status = status, forwardedTo = latest.forwardedTo + peer))
                        event(nodeId, "${report.id.take(8)} forwarded to $peer · peer stored ACK")
                        onJourney(JourneyEvent(nodeId = nodeId, reportId = report.id, kind = "peer_stored", at = now(), peerId = peer, path = report.relayPath + peer))
                    } else event(nodeId, "${report.id.take(8)} to $peer failed; retained for retry")
                } } catch (_: Exception) {
                    sending.remove(key)
                    event(nodeId, "${report.id.take(8)} transport interrupted; retained for retry")
                }
            }
        }
    }
}
