package org.resqmesh.app

import org.resqmesh.app.core.*
import org.resqmesh.app.data.EventEntity

enum class RelayPhase { SIMULATION, NEEDS_PERMISSION, RADIOS_OFF, PLAY_SERVICES_UNAVAILABLE, STARTING, SEARCHING, ACTIVE, DISABLED, PAUSED, FAILED }
data class ConnectivityState(val networkAvailable: Boolean = false, val internetValidated: Boolean = false,
    val backendReachable: Boolean = false, val leaseExpiresAt: Long = 0, val checking: Boolean = false, val lastError: String? = null)
data class PeerView(val id: String, val phase: PeerPhase, val lastSeen: Long,
    val gatewayAvailable: Boolean = false, val gatewayExpiresAt: Long = 0)
data class SimulationLabState(val nodes: List<SimNode> = emptyList(), val links: List<Pair<String, String>> = emptyList(),
    val selectedNodeId: String = "", val archivedNodeIds: List<String> = emptyList())
data class MeshUiState(val ready: Boolean = false, val simulation: Boolean = true, val localNodeId: String = "",
    val relayEnabled: Boolean = true, val relayPhase: RelayPhase = RelayPhase.SIMULATION, val relayDetail: String? = null,
    val connectivity: ConnectivityState = ConnectivityState(), val peers: List<PeerView> = emptyList(),
    val reports: List<StoredReport> = emptyList(), val receipts: List<ReceiptPacket> = emptyList(),
    val journeys: List<JourneyEvent> = emptyList(), val backend: String = "http://10.0.2.2:8000",
    val apiKeyConfigured: Boolean = false, val lab: SimulationLabState = SimulationLabState(), val events: List<EventEntity> = emptyList(),
    val media: List<MediaView> = emptyList(), val backgroundRelayEnabled: Boolean = false,
    val backgroundRelayRunning: Boolean = false, val retentionDays: Int? = null)
data class DraftAttachment(val metadata: Attachment, val filePath: String)
data class MediaView(val reportId: String, val attachmentId: String, val kind: String, val localAvailable: Boolean,
    val backendReceived: Boolean, val filePath: String? = null)
data class SosDraft(val text: String = "", val emergencyType: String = "other", val building: String = "", val zone: String = "",
    val locationText: String = "", val floor: String = "", val room: String = "", val peopleAffected: Int? = null, val vulnerability: String = "",
    val quickNeeds: Set<String> = emptySet(), val locationContext: LocationContext = LocationContext(),
    val attachments: List<DraftAttachment> = emptyList())
data class SavedSosLocation(val building: String = "", val zone: String = "", val locationText: String = "",
    val floor: String = "", val room: String = "", val context: LocationContext = LocationContext())
