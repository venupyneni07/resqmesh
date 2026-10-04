package org.resqmesh.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.resqmesh.app.core.*
import org.resqmesh.app.data.*
import org.resqmesh.app.transport.NearbyTransport
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MeshApplication : Application() {
    lateinit var mesh: MeshController
    override fun onCreate() { super.onCreate(); mesh = MeshController(this) }
}
private data class ProbeResult(val leaseUntil: Long, val uploads: Map<String, String>, val receipts: List<BackendReceipt>,
    val error: String?)
private class ModeStore(private val store: RoomReportStore, private val simulation: Boolean) : ReportStore, ReceiptStore {
    override fun get(nodeId: String, reportId: String) = store.get(nodeId, reportId)?.takeIf { it.report.simulation == simulation }
    override fun all(nodeId: String) = store.all(nodeId).filter { it.report.simulation == simulation }
    override fun save(stored: StoredReport) { require(stored.report.simulation == simulation); store.save(stored) }
    override fun hasDeliveryReceipt(nodeId: String, reportId: String) = receipts(nodeId).any { it.receipt.reportId == reportId }
    override fun receipt(node: String, id: String) = store.receipt(node, id)?.takeIf { it.receipt.simulation == simulation }
    override fun receipts(node: String) = store.receipts(node).filter { it.receipt.simulation == simulation }
    override fun saveReceipt(node: String, packet: ReceiptPacket) { require(packet.receipt.simulation == simulation); store.saveReceipt(node, packet) }
}

/** App-scoped serialized coordinator; UI consumes immutable local-knowledge StateFlow only. */
class MeshController(private val app: Application) {
    val serial = Executors.newSingleThreadScheduledExecutor()
    private val networkExecutor = Executors.newFixedThreadPool(3)
    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private var mediaBusy = false
    private val requestedPreviews = mutableSetOf<Triple<String, String, String>>()
    private val nextMediaProbe = mutableMapOf<String, Long>()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("resqmesh", 0)
    private val database = Room.databaseBuilder(app, MeshDatabase::class.java, "resqmesh.db").addMigrations(MeshDatabase.MIGRATION_1_2).build()
    private val dao = database.dao()
    private val store = RoomReportStore(dao)
    private val localCipher = LocalCipher()
    private val mediaStore = FileMediaStore(File(app.filesDir, "media"), cipher = localCipher, previewRoot = File(app.cacheDir, "media-preview"))
    private val _state = MutableStateFlow(MeshUiState())
    val state: StateFlow<MeshUiState> = _state.asStateFlow()
    private var simulation = prefs.getBoolean("simulation", true)
    private var relayEnabled = prefs.getBoolean("relayEnabled", true)
    private var backend = prefs.getString("backend", "http://10.0.2.2:8000")!!
    private val credentials = CredentialStore(app)
    private var apiKey = runCatching { credentials.migrate(app) }.getOrDefault("")
    private var activityVisible = false
    @Volatile private var serviceRunning = false
    private var nextRetention = 0L
    private val nodeId = prefs.getString("nodeId", null) ?: newNodeId().also { prefs.edit().putString("nodeId", it).apply() }
    private val simHome = prefs.getString("simHome", null) ?: newNodeId().also { prefs.edit().putString("simHome", it).apply() }
    private var selectedSim = prefs.getString("selectedSim", simHome)!!
    private val environment = SimulationEnvironment()
    private var transport: Transport = SimulationTransport(environment)
    private var scoped = ModeStore(store, simulation)
    private var engine = RelayEngine(scoped, transport, System::currentTimeMillis, ::log, ::journey)
    private var receipts = ReceiptRelay(scoped, transport, System::currentTimeMillis, ::journey)
    private var mediaRelay = MediaRelay(scoped, mediaStore, transport, System::currentTimeMillis, simulation) { requestPump() }
    private var generation = 0
    private var active = false
    private var pumpQueued = false
    private var ticker: ScheduledFuture<*>? = null
    private var nearbyStarted = false
    private var relayPhase = RelayPhase.SIMULATION
    private var relayDetail: String? = null
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val links = mutableMapOf<String, ConnectivityState>()
    private val capabilities = mutableMapOf<Pair<String, String>, ControlPacket.Capability>()
    private val probing = mutableSetOf<String>()
    private val nextProbe = mutableMapOf<String, Long>()
    private val uploadCursor = mutableMapOf<String, String>()
    private val receiptCursor = mutableMapOf<String, String>()
    private val mediaCursor = mutableMapOf<String, String>()
    private val receiptKnownByPeer = mutableMapOf<Pair<String, String>, Set<String>>()
    var onAuthentication: ((String, String, (Boolean) -> Unit) -> Unit)? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { serial.execute { networkChanged() } }
        override fun onLost(network: Network) { serial.execute { networkChanged() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { serial.execute { networkChanged() } }
    }
    init {
        serial.execute {
            mediaStore.clearPreviews()
            store.migratePayloads()
            compactMigratedPayloads()
            // Also convert completed historical media before the UI can preview it.
            dao.allReports().map(store::from).forEach { saved -> saved.report.attachments.forEach {
                mediaStore.available(saved.nodeId, saved.report.id, it)
            } }
            restoreEnvironment(); configureTransport()
            runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            publish()
        }
    }
    private fun compactMigratedPayloads() {
        if (prefs.getBoolean("payloadStorageCompacted", false)) return
        // Rebuild SQLite pages after logical encryption. This is not a secure erase of OS snapshots.
        runCatching {
            val db = database.openHelper.writableDatabase
            db.query("PRAGMA secure_delete=ON").use { require(it.moveToFirst()) }
            fun checkpoint() { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { require(it.moveToFirst() && it.getInt(0) == 0) } }
            checkpoint(); db.execSQL("VACUUM"); checkpoint()
            prefs.edit().putBoolean("payloadStorageCompacted", true).commit()
        }.onFailure { log(local(), "Encrypted storage compaction will retry next launch (${it.javaClass.simpleName})") }
    }
    private fun newNodeId() = "RQM-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
    private fun local() = if (simulation) selectedSim else nodeId
    private fun nodes() = if (simulation) environment.nodes.keys.toList() else listOf(nodeId)
    private fun log(node: String, text: String) { dao.event(EventEntity(timestamp = System.currentTimeMillis(), nodeId = node, description = text)); dao.trimEvents() }
    private fun journey(event: JourneyEvent) { store.journey(event); requestPump() }
    private fun restoreEnvironment() {
        runCatching {
            val json = JSONObject(prefs.getString("simulationEnvironment", "{}")!!)
            val n = json.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until n.length()) { val item = n.getJSONObject(i); val id = item.getString("id")
                if (id.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) environment.nodes[id] = SimNode(id, item.optBoolean("internet", false)) }
            val e = json.optJSONArray("links") ?: JSONArray()
            for (i in 0 until e.length()) { val edge = e.getJSONArray(i); val a = edge.getString(0); val b = edge.getString(1)
                if (a != b && a in environment.nodes && b in environment.nodes) environment.links.add(setOf(a, b)) }
        }
        if (environment.nodes.isEmpty()) environment.nodes[simHome] = SimNode(simHome)
        if (selectedSim !in environment.nodes) selectedSim = environment.nodes.keys.first()
    }
    private fun persistEnvironment() {
        val json = JSONObject().put("nodes", JSONArray(environment.nodes.values.map { JSONObject().put("id", it.id).put("internet", it.internet) }))
            .put("links", JSONArray(environment.links.map { JSONArray(it.toList()) }))
        prefs.edit().putString("simulationEnvironment", json.toString()).putString("selectedSim", selectedSim).apply()
    }
    private fun configureTransport() {
        generation++; nearbyStarted = false
        transport.eventsWith {}; transport.close(); capabilities.clear(); links.clear(); nextProbe.clear(); probing.clear()
        scoped = ModeStore(store, simulation)
        transport = if (simulation) SimulationTransport(environment) else NearbyTransport(app, nodeId, serial, { log(nodeId, it) }) { peer, code, answer ->
            main.post { onAuthentication?.invoke(peer, code, answer) ?: answer(false) }
        }
        engine = RelayEngine(scoped, transport, System::currentTimeMillis, ::log, ::journey)
        receipts = ReceiptRelay(scoped, transport, System::currentTimeMillis, ::journey)
        mediaRelay = MediaRelay(scoped, mediaStore, transport, System::currentTimeMillis, simulation) { requestPump() }
        transport.eventsWith(::transportEvent)
        transport.controlWith(::receiveControl)
        relayPhase = if (simulation) RelayPhase.SIMULATION else RelayPhase.PAUSED
        relayDetail = null
        requestPump()
    }
    private fun transportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerChanged -> {
                if (event.peer.phase == PeerPhase.CONNECTED && relayEnabled) exchangeState(event.local, event.peer.id)
                if (event.peer.phase in setOf(PeerPhase.LOST, PeerPhase.REJECTED)) {
                    capabilities.remove(event.local to event.peer.id); receiptKnownByPeer.remove(event.local to event.peer.id)
                }
            }
            is TransportEvent.EnvironmentChanged -> {
                nextProbe[event.nodeId] = 0
                if (environment.nodes[event.nodeId]?.internet != true) {
                    links[event.nodeId] = ConnectivityState(); announceCapability(event.nodeId)
                }
                persistEnvironment()
            }
            is TransportEvent.StateChanged -> if (!simulation) {
                relayPhase = when (event.state) { "active" -> RelayPhase.ACTIVE; "searching" -> RelayPhase.SEARCHING; "starting" -> RelayPhase.STARTING
                    "failed" -> RelayPhase.FAILED; else -> if (relayEnabled && active) RelayPhase.FAILED else RelayPhase.PAUSED }
                relayDetail = event.detail
                if (event.state in setOf("failed", "stopped")) nearbyStarted = false
            }
        }
        requestPump(); publish()
    }
    private fun exchangeState(node: String, peer: String) {
        transport.sendControl(node, peer, ControlPacket.Inventory(reports = scoped.all(node).take(200).map { it.report.id }.toSet(),
            receipts = scoped.receipts(node).take(200).map { it.receipt.id }.toSet())) {}
        transport.sendControl(node, peer, capability(node)) {}
    }
    private fun capability(node: String): ControlPacket.Capability {
        val now = System.currentTimeMillis(); val status = links[node]
        return ControlPacket.Capability(nodeId = node, reachable = status?.backendReachable == true && status.leaseExpiresAt > now,
            observedAt = now, expiresAt = if (status?.backendReachable == true) status.leaseExpiresAt else now + 15_000)
    }
    private fun announceCapability(node: String) { if (relayEnabled) transport.peers(node).forEach { transport.sendControl(node, it, capability(node)) {} } }
    private fun receiveControl(node: String, from: String, packet: ControlPacket): Boolean {
        val now = System.currentTimeMillis()
        return when (packet) {
            is ControlPacket.Inventory -> { receiptKnownByPeer[node to from] = packet.receipts; requestPump(); true }
            is ControlPacket.Capability -> {
                if (packet.nodeId != from || packet.observedAt < now - 180_000 || packet.observedAt > now + 60_000 || packet.expiresAt > now + 180_000 || packet.expiresAt <= now) false
                else {
                    if (packet.observedAt >= (capabilities[node to from]?.observedAt ?: 0)) capabilities[node to from] = packet
                    publish(); true
                }
            }
            is ControlPacket.Delivery -> {
                if (packet.packet.receipt.simulation != simulation) false else receipts.receive(node, from, packet.packet).also { if (it) requestPump() }
            }
            is ControlPacket.MediaChunk -> mediaRelay.receive(node, from, packet).also { if (it) requestPump() }
        }
    }
    fun foreground() {
        serial.execute { activityVisible = true; updateActivity(); requestPump() }
        if (backgroundRelayEnabled() && !serviceRunning) setBackgroundRelayEnabled(true) {}
    }
    fun background() { serial.execute { activityVisible = false; requestedPreviews.clear(); updateActivity(); if (!mediaBusy) mediaStore.clearPreviews(); publish() } }
    private fun updateActivity() {
        active = activityVisible || serviceRunning
        if (active && ticker == null) ticker = serial.scheduleWithFixedDelay({
            runCatching { pump() }.onFailure { log(local(), "Coordinator error: ${it.javaClass.simpleName}") }
        }, 0, 2, TimeUnit.SECONDS)
        if (!active) {
            ticker?.cancel(false); ticker = null
            if (!simulation) { transport.close(); nearbyStarted = false; relayPhase = RelayPhase.PAUSED }
        }
    }
    fun backgroundRelayEnabled() = prefs.getBoolean("backgroundRelayEnabled", false)
    fun backgroundRelayRunning() = serviceRunning
    /** Must be enabled by a visible UI action; platform permission errors are shown to the user. */
    fun setBackgroundRelayEnabled(enabled: Boolean, completed: (String?) -> Unit) {
        if (enabled && Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            completed("Allow notifications so background relay has a visible status and Stop action."); return
        }
        if (enabled && !prefs.getBoolean("simulation", true) && Build.VERSION.SDK_INT >= 31 &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            completed("Allow Nearby devices before starting background relay."); return
        }
        prefs.edit().putBoolean("backgroundRelayEnabled", enabled).commit()
        if (enabled) {
            runCatching { app.startForegroundService(Intent(app, RelayService::class.java)) }.onFailure {
                prefs.edit().putBoolean("backgroundRelayEnabled", false).commit()
                serial.execute { publish() }; completed("Android could not start the relay. Open the app and try again."); return
            }
        } else { app.stopService(Intent(app, RelayService::class.java)); relayServiceStopped() }
        serial.execute { publish() }; completed(null)
    }
    internal fun relayServiceStarted() { serial.execute { serviceRunning = true; updateActivity(); requestPump(); publish() } }
    internal fun relayServiceStopped(reason: String? = null) { serial.execute {
        serviceRunning = false; if (reason != null) relayDetail = reason
        updateActivity(); publish()
    } }
    /** Only an explicit visible-screen action materializes a saved attachment into private cache. */
    fun requestMediaPreview(reportId: String, attachmentId: String, completed: (String?) -> Unit) { serial.execute {
        if (!activityVisible) { main.post { completed(null) }; return@execute }
        val node = local()
        val attachment = scoped.get(node, reportId)?.report?.attachments?.firstOrNull { it.id == attachmentId }
        val file = attachment?.let { mediaStore.file(node, reportId, it) }
        if (file != null) requestedPreviews.add(Triple(node, reportId, attachmentId))
        publish()
        main.post { completed(file?.absolutePath) }
    } }
    fun retentionDays(): Int? = prefs.getInt("retentionDays", 0).takeIf { it in setOf(7, 30, 90) }
    fun setRetentionDays(days: Int?) {
        require(days == null || days in setOf(7, 30, 90))
        prefs.edit().putInt("retentionDays", days ?: 0).commit()
        serial.execute { nextRetention = 0; publish() }
    }
    fun cleanupDeliveredHistory(completed: (Int) -> Unit) { serial.execute {
        val removed = cleanupHistory(); publish(); main.post { completed(removed) }
    } }
    private fun cleanupHistory(): Int {
        val days = retentionDays() ?: return 0
        // Do not delete a file currently being streamed to the server.
        if (mediaBusy || probing.isNotEmpty()) return 0
        val now = System.currentTimeMillis(); var removed = 0
        dao.allReports().map(store::from).forEach { saved ->
            val report = saved.report
            val receipt = store.receipts(saved.nodeId).filter { it.receipt.reportId == report.id }
                .map { it.receipt }.maxByOrNull { it.timestamp }
            val mediaConfirmed = report.attachments.all { mediaStore.uploaded(saved.nodeId, report.id, it, backend) }
            if (eligibleForRetention(report, receipt, mediaConfirmed, days, now) && mediaStore.removeReport(saved.nodeId, report.id)) {
                dao.deleteDeliveredHistory(saved.nodeId, report.id); removed++
            }
        }
        return removed
    }
    fun refresh() { serial.execute { nextProbe.remove(local()); ensureNearby(); requestPump(); publish() } }
    fun setRelayEnabled(enabled: Boolean) { serial.execute {
        relayEnabled = enabled; prefs.edit().putBoolean("relayEnabled", enabled).apply()
        if (!enabled && !simulation) { transport.close(); nearbyStarted = false }
        requestPump(); publish()
    } }
    fun switchMode(useSimulation: Boolean) { serial.execute {
        if (simulation != useSimulation) { simulation = useSimulation; prefs.edit().putBoolean("simulation", simulation).commit(); configureTransport()
            if (serviceRunning) main.post { app.startForegroundService(Intent(app, RelayService::class.java)) } }
        requestPump(); publish()
    } }
    private fun networkChanged() {
        if (!simulation) {
            val previous = links[nodeId] ?: ConnectivityState()
            val current = physicalConnectivity()
            links[nodeId] = current.copy(backendReachable = current.networkAvailable && previous.backendReachable,
                leaseExpiresAt = if (current.networkAvailable) previous.leaseExpiresAt else 0, lastError = previous.lastError)
            nextProbe[nodeId] = 0
            if (!current.networkAvailable) announceCapability(nodeId)
        }
        requestPump(); publish()
    }
    private fun physicalConnectivity(): ConnectivityState {
        val network = connectivity.activeNetwork
        val caps = connectivity.getNetworkCapabilities(network)
        return ConnectivityState(networkAvailable = network != null,
            internetValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }
    private fun ensureNearby() {
        if (simulation) { relayPhase = if (relayEnabled) RelayPhase.SIMULATION else RelayPhase.DISABLED; return }
        if (!active || !relayEnabled) { relayPhase = if (active) RelayPhase.DISABLED else RelayPhase.PAUSED; return }
        val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES)
            else if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val phase = when {
            permissions.any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } -> RelayPhase.NEEDS_PERMISSION
            GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS -> RelayPhase.PLAY_SERVICES_UNAVAILABLE
            else -> runCatching {
                val bluetooth = app.getSystemService(BluetoothManager::class.java).adapter
                val wifi = app.getSystemService(WifiManager::class.java)
                val location = Build.VERSION.SDK_INT > 32 || app.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)
                if (bluetooth?.isEnabled == true && wifi.isWifiEnabled && location) null else RelayPhase.RADIOS_OFF
            }.getOrDefault(RelayPhase.RADIOS_OFF)
        }
        if (phase != null) {
            if (nearbyStarted) { transport.close(); nearbyStarted = false }
            relayPhase = phase; return
        }
        if (!nearbyStarted) {
            nearbyStarted = true
            runCatching { (transport as NearbyTransport).start() }.onFailure {
                nearbyStarted = false; relayPhase = RelayPhase.FAILED; relayDetail = "Nearby could not start. Check permissions and radios."
            }
        }
    }
    private fun requestPump() {
        if (!active || pumpQueued) return
        pumpQueued = true
        serial.execute { pumpQueued = false; runCatching { pump() }.onFailure { log(local(), "Coordinator error: ${it.javaClass.simpleName}"); publish() } }
    }
    private fun pump() {
        if (!active) return
        ensureNearby()
        val now = System.currentTimeMillis()
        if (now >= nextRetention) { nextRetention = now + 86_400_000L; cleanupHistory() }
        capabilities.entries.removeAll { it.value.expiresAt <= now }
        nodes().forEach { node ->
            engine.expire(node)
            // Small alerts and delivery receipts always get their pass before attachment chunks.
            if (relayEnabled) { receipts.relay(node); engine.relay(node); mediaRelay.relay(node) }
            val networkAvailable = if (simulation) environment.nodes[node]?.internet == true else physicalConnectivity().networkAvailable
            val before = links[node] ?: ConnectivityState()
            if (!networkAvailable) {
                if (before.backendReachable) { links[node] = ConnectivityState(); announceCapability(node) }
                else links[node] = before.copy(networkAvailable = false, internetValidated = false, backendReachable = false, leaseExpiresAt = 0)
            } else {
                links[node] = before.copy(networkAvailable = true, internetValidated = if (simulation) true else physicalConnectivity().internetValidated,
                    backendReachable = before.backendReachable && before.leaseExpiresAt > now)
                if (node !in probing && now >= (nextProbe[node] ?: 0)) probeAndUpload(node)
                if (!mediaBusy && links[node]?.backendReachable == true && now >= (nextMediaProbe[node] ?: 0)) transferMedia(node)
            }
        }
        publish()
    }
    private fun probeAndUpload(node: String) {
        probing.add(node); nextProbe[node] = System.currentTimeMillis() + 10_000
        links[node] = (links[node] ?: ConnectivityState()).copy(checking = true)
        val destination = backend; val key = apiKey; val mode = simulation; val epoch = generation
        val reports = scoped.all(node)
        val known = scoped.receipts(node)
        val alreadyDelivered = known.map { it.receipt.reportId }.toSet()
        val acknowledged = known.filter { it.receipt.type == "responder_acknowledged" }.map { it.receipt.reportId }.toSet()
        val now = System.currentTimeMillis()
        val uploadsDue = rotatingBatch(reports.filter { it.report.id !in alreadyDelivered && it.status != DeliveryStatus.UPLOADED && it.report.expiresAt > now }, uploadCursor[node], 32) { it.report.id }
        val pollsDue = rotatingBatch(reports.filter { it.report.id !in acknowledged }, receiptCursor[node], 32) { it.report.id }
        uploadsDue.lastOrNull()?.let { uploadCursor[node] = it.report.id }
        pollsDue.lastOrNull()?.let { receiptCursor[node] = it.report.id }
        networkExecutor.execute {
            val result = runCatching {
                val heartbeat = http(destination, key, "POST", "/api/gateways/$node/heartbeat", JSONObject().put("simulation", mode), node)
                require(heartbeat.getString("node_id") == node && heartbeat.getBoolean("online"))
                val lease = heartbeat.getLong("expires_at"); require(lease > System.currentTimeMillis())
                val uploads = mutableMapOf<String, String>(); val collected = mutableListOf<BackendReceipt>(); var issue: String? = null
                uploadsDue.forEach { stored ->
                    val r = stored.report
                    if (r.id !in alreadyDelivered && stored.status != DeliveryStatus.UPLOADED && r.expiresAt > System.currentTimeMillis()) {
                        runCatching {
                            val accepted = http(destination, key, "POST", "/api/reports", Wire.encode(r), node)
                            require(accepted.getString("report_id") == r.id && accepted.getString("status") == "accepted")
                            val receipt = Wire.receipt(accepted.getJSONObject("receipt")); require(receipt.consistentWith(r) &&
                                receipt.type == "backend_received" && accepted.getString("incident_id") == receipt.incidentId)
                            uploads[r.id] = accepted.getString("incident_id"); collected.add(receipt)
                        }.onFailure { issue = "Upload retained for retry: ${it.message ?: "backend unavailable"}" }
                    }
                }
                pollsDue.forEach { stored ->
                    val r = stored.report
                    runCatching {
                        val feed = http(destination, key, "GET", "/api/receipts?report_id=${r.id}", node = node)
                        val list = feed.getJSONArray("receipts")
                        for (i in 0 until list.length()) {
                            val receipt = Wire.receipt(list.getJSONObject(i)); require(receipt.consistentWith(r))
                            collected.add(receipt)
                        }
                    }.onFailure { if (issue == null) issue = "Receipt check will retry" }
                }
                ProbeResult(lease, uploads, collected.distinctBy { it.id }, issue)
            }
            serial.execute complete@{
                if (epoch != generation) return@complete
                probing.remove(node)
                if (simulation && environment.nodes[node]?.internet != true) { links[node] = ConnectivityState(); publish(); return@complete }
                result.onSuccess { answer ->
                    val current = links[node] ?: ConnectivityState()
                    links[node] = current.copy(backendReachable = answer.leaseUntil > System.currentTimeMillis(), checking = false, leaseExpiresAt = answer.leaseUntil, lastError = answer.error)
                    answer.uploads.forEach { (id, incident) -> scoped.get(node, id)?.let { scoped.save(it.copy(status = DeliveryStatus.UPLOADED, incidentId = incident)) } }
                    answer.receipts.forEach { receipt -> receipts.installFromBackend(node, receipt) }
                    announceCapability(node)
                }.onFailure {
                    links[node] = (links[node] ?: ConnectivityState()).copy(backendReachable = false, checking = false, leaseExpiresAt = 0,
                        lastError = "Response system unreachable: ${it.message ?: "connection failed"}")
                    announceCapability(node)
                }
                requestPump(); publish()
            }
        }
    }
    /** Large media cannot occupy alert workers or postpone installing an accepted SOS receipt. */
    private fun transferMedia(node: String) {
        val destination = backend; val key = apiKey; val epoch = generation
        val reports = scoped.all(node)
        val alreadyDelivered = scoped.receipts(node).map { it.receipt.reportId }.toSet() +
            reports.filter { it.status == DeliveryStatus.UPLOADED }.map { it.report.id }
        val mediaDue = rotatingBatch(reports.filter { it.report.id in alreadyDelivered }.flatMap { item -> item.report.attachments.map { item.report to it } }
            .filter { (report, attachment) -> !mediaStore.uploaded(node, report.id, attachment, destination) },
            mediaCursor[node], 8) { (report, attachment) -> "${report.id}:${attachment.id}" }
        mediaDue.lastOrNull()?.let { (report, attachment) -> mediaCursor[node] = "${report.id}:${attachment.id}" }
        val mediaFiles = mediaDue.associate { (report, attachment) -> (report.id to attachment.id) to mediaStore.file(node, report.id, attachment) }
        if (mediaDue.isEmpty()) return
        nextMediaProbe[node] = System.currentTimeMillis() + 10_000
        mediaBusy = true
        mediaExecutor.execute {
            val mediaReceived = mutableSetOf<Pair<String, String>>()
            var issue: String? = null
            // A report receipt confirms only the alert. Attachments are independently retried and checked.
            var mediaUploads = 0
            for ((report, attachment) in mediaDue) {
                if (report.id !in alreadyDelivered) continue
                runCatching {
                    val items = http(destination, key, "GET", "/api/reports/${report.id}/attachments", node = node).getJSONArray("attachments")
                    val remote = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.getString("id") == attachment.id }
                    fun matches(item: JSONObject) = item.optString("status") == "available" &&
                        item.optString("id") == attachment.id && item.optString("sha256") == attachment.sha256 &&
                        item.optLong("byte_size", -1) == attachment.byteSize
                    if (remote != null && matches(remote)) mediaReceived.add(report.id to attachment.id)
                    else if (mediaUploads < 2) mediaFiles[report.id to attachment.id]?.let { file ->
                        mediaUploads++
                        val received = uploadMedia(destination, key, node, report.id, attachment, file)
                        require(matches(received)) { "Attachment confirmation did not match" }
                        mediaReceived.add(report.id to attachment.id)
                    }
                    Unit
                }.onFailure { if (issue == null) issue = "SOS receipt is separate; attachment upload will retry" }
            }
            serial.execute mediaComplete@{
                mediaBusy = false
                if (!activityVisible) mediaStore.clearPreviews()
                if (epoch != generation) { requestPump(); return@mediaComplete }
                if (simulation && environment.nodes[node]?.internet != true) { requestPump(); return@mediaComplete }
                val received = mediaReceived
                received.forEach { (reportId, attachmentId) ->
                    scoped.get(node, reportId)?.report?.attachments?.firstOrNull { it.id == attachmentId }?.let {
                        mediaStore.markUploaded(node, reportId, it, destination)
                    }
                }
                if (issue != null) links[node]?.let { links[node] = it.copy(lastError = issue) }
                // Start retry backoff after completion so a slow failing gateway cannot
                // repeatedly take the shared media worker before other gateways get a turn.
                nextMediaProbe[node] = if (mediaReceived.isNotEmpty()) 0 else System.currentTimeMillis() + 10_000
                publish(); requestPump()
            }
        }
    }
    private fun http(origin: String, token: String, method: String, path: String, json: JSONObject? = null, node: String? = null): JSONObject {
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 4000; connection.readTimeout = 7000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) connection.setRequestProperty("X-API-Key", token)
            if (node != null) connection.setRequestProperty("X-ResQMesh-Node-ID", node)
            if (json != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toString().toByteArray()) } }
            val code = connection.responseCode
            require(code in 200..299) { if (code == 401 || code == 403) "check the API key (HTTP $code)" else "HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }; require(body.length <= 1_000_000)
            return JSONObject(body)
        } finally { connection.disconnect() }
    }
    private fun uploadMedia(origin: String, token: String, node: String, reportId: String, attachment: Attachment, file: File): JSONObject {
        require(file.isFile && file.length() == attachment.byteSize) { "Attachment file unavailable" }
        val connection = URL("$origin/api/reports/$reportId/attachments/${attachment.id}").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"; connection.connectTimeout = 4000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", attachment.mimeType)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-ResQMesh-Node-ID", node)
            if (token.isNotEmpty()) connection.setRequestProperty("X-API-Key", token)
            connection.setFixedLengthStreamingMode(attachment.byteSize)
            file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
            require(connection.responseCode in 200..299) { "Attachment upload HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }; require(body.length <= 100_000)
            return JSONObject(body)
        } finally { connection.disconnect() }
    }
    fun saveSettings(url: String, newApiKey: String? = null, completed: (String?) -> Unit) {
        val cleaned = url.trim().trimEnd('/')
        val valid = runCatching { URI(cleaned).let { it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.fragment == null && (it.path.isNullOrEmpty() || it.path == "/") } }.getOrDefault(false)
        if (!valid) { completed("Use an http:// or https:// server origin without a path or credentials"); return }
        if (newApiKey != null && (newApiKey.length > 4096 || newApiKey.any { it.code < 32 || it.code > 126 })) { completed("API key must contain printable ASCII characters"); return }
        serial.execute {
            // Do not silently send a previously saved credential to a different server.
            val candidateKey = newApiKey ?: if (backend != cleaned) "" else apiKey
            try { credentials.write(candidateKey) } catch (_: Exception) {
                main.post { completed("Could not securely save the gateway credential. Try again.") }; return@execute
            }
            backend = cleaned; apiKey = candidateKey
            prefs.edit().putString("backend", backend).remove("apiKey").apply()
            generation++; probing.clear(); nextProbe.clear(); links.clear(); capabilities.clear()
            nodes().forEach(::announceCapability)
            requestPump(); publish(); main.post { completed(null) }
        }
    }
    fun create(draft: SosDraft, completed: (String?, String?) -> Unit = { _, _ -> }) =
        createWithSubmissionId(draft, UUID.randomUUID().toString(), completed)
    /** A UI submission ID is persisted before saving; retry after interruption reuses that ID. */
    fun createWithSubmissionId(draft: SosDraft, submissionId: String, completed: (String?, String?) -> Unit) { serial.execute {
        try {
            require(UUID.fromString(submissionId).toString() == submissionId.lowercase())
            val previous = dao.allReports().firstOrNull { it.reportId == submissionId }
            if (previous != null) { main.post { completed(submissionId, null) }; return@execute }
            fun optional(value: String) = value.trim().ifEmpty { null }
            val now = System.currentTimeMillis(); val origin = local()
            val message = sosMessage(draft.text)
            val location = if (draft.locationContext.source == "unknown" &&
                listOf(draft.building, draft.zone, draft.locationText, draft.floor, draft.room).any { it.isNotBlank() })
                LocationContext(source = "manual", observedAt = now) else draft.locationContext
            val report = Report(submissionId, origin, now, now + 30 * 60_000, message.text, optional(draft.building), optional(draft.zone),
                simulation = simulation, schemaVersion = if (draft.attachments.isEmpty()) 3 else 4, emergencyType = draft.emergencyType, locationText = optional(draft.locationText),
                floor = optional(draft.floor), room = optional(draft.room), peopleAffected = draft.peopleAffected, vulnerability = optional(draft.vulnerability),
                messageSource = message.source, quickNeeds = draft.quickNeeds.sorted(), locationContext = location,
                attachments = draft.attachments.map { it.metadata })
            require(report.valid()) { "Check the SOS details and attachment size" }
            // Durable private copies exist before the alert advertises their immutable manifests.
            draft.attachments.forEach { item ->
                val source = File(item.filePath).canonicalFile
                require(source.path.startsWith(File(app.filesDir, "media-drafts").canonicalPath + File.separator))
                require(mediaStore.importDraft(origin, report.id, item.metadata, source)) { "Could not save attachment" }
            }
            val saved = engine.originate(origin, report)
            if (saved) { nextProbe[origin] = 0; nextMediaProbe.remove(origin); requestPump(); publish() }
            main.post { completed(if (saved) report.id else null, if (saved) null else "Check the optional details and location. Your SOS was not saved.") }
        } catch (_: Exception) {
            // A later publish/log failure must not make an already committed SOS look unsaved.
            val committed = runCatching { dao.allReports().any { it.reportId == submissionId } }.getOrDefault(false)
            main.post { completed(submissionId.takeIf { committed }, if (committed) null else
                "Could not save this SOS with its attachment. Your draft is kept; retry or send without media.") }
        }
    } }
    fun recoverSubmission(id: String, completed: (Boolean) -> Unit) { serial.execute {
        val found = dao.allReports().any { it.reportId == id }
        main.post { completed(found) }
    } }
    /** User-saved context retains its observation time, including when it is reused days later. */
    fun savedSosLocation(): SavedSosLocation? = runCatching {
        val json = prefs.getString("savedSosLocation", null) ?: return null
        val decoded = localCipher.decrypt(json)
        if (!LocalCipher.isEncrypted(json)) prefs.edit().putString("savedSosLocation", localCipher.encrypt(decoded)).commit()
        val j = JSONObject(decoded)
        SavedSosLocation(j.optString("building"), j.optString("zone"), j.optString("location_text"),
            j.optString("floor"), j.optString("room"), Wire.location(j.getJSONObject("context")).copy(source = "saved"))
            .takeIf { validSavedLocation(it, System.currentTimeMillis()) }
    }.getOrNull()

    /** Only an explicit user save updates this preference; ordinary SOS sends never do. */
    fun saveSosLocation(location: SavedSosLocation?): Boolean {
        if (location == null) return prefs.edit().remove("savedSosLocation").commit()
        val now = System.currentTimeMillis()
        val context = if (location.context.source == "unknown") LocationContext("manual", now) else location.context
        val cleaned = location.copy(building = location.building.trim(), zone = location.zone.trim(),
            locationText = location.locationText.trim(), floor = location.floor.trim(), room = location.room.trim(), context = context)
        if (!validSavedLocation(cleaned, now)) return false
        val j = JSONObject().put("building", cleaned.building).put("zone", cleaned.zone).put("location_text", cleaned.locationText)
            .put("floor", cleaned.floor).put("room", cleaned.room).put("context", Wire.location(cleaned.context))
        return prefs.edit().putString("savedSosLocation", localCipher.encrypt(j.toString())).commit()
    }
    private fun validSavedLocation(location: SavedSosLocation, now: Long): Boolean =
        location.context.source != "unknown" && location.context.validAt(now) &&
            location.building.length <= 120 && location.zone.length <= 120 && location.locationText.length <= 240 &&
            location.floor.length <= 40 && location.room.length <= 40 &&
            (listOf(location.building, location.zone, location.locationText, location.floor, location.room).any { it.isNotBlank() } ||
                location.context.latitude != null)
    fun simAddNode() { serial.execute { (transport as? SimulationTransport)?.addNode(SimNode(newNodeId())); publish() } }
    fun simRemoveNode(id: String) { serial.execute {
        if (!simulation || environment.nodes.size <= 1) return@execute
        (transport as SimulationTransport).removeNode(id); links.remove(id)
        if (selectedSim == id) selectedSim = environment.nodes.keys.first()
        persistEnvironment(); requestPump(); publish()
    } }
    fun simSelectNode(id: String) { serial.execute { if (id in environment.nodes) { selectedSim = id; persistEnvironment(); publish() } } }
    fun simSetInternet(id: String, available: Boolean) { serial.execute { (transport as? SimulationTransport)?.setInternet(id, available); publish() } }
    fun simSetLink(a: String, b: String, connected: Boolean) { serial.execute {
        if (simulation && a != b && a in environment.nodes && b in environment.nodes) (transport as SimulationTransport).setLink(a, b, connected)
        persistEnvironment(); requestPump(); publish()
    } }
    fun restoreSavedSimulationNodes() { serial.execute {
        if (simulation) dao.allReports().map(store::from).filter { it.report.simulation }.map { it.nodeId }.distinct().forEach {
            if (it !in environment.nodes) (transport as SimulationTransport).addNode(SimNode(it))
        }
        persistEnvironment(); publish()
    } }
    fun loadSimulationExamples() {
        if (!_state.value.simulation) return
        create(SosDraft("Ground floor lo water fast ga vastundi. Three people trapped. One elderly person.", "flood", "Demo apartment", "Ground floor", peopleAffected = 3, vulnerability = "One elderly person"))
        create(SosDraft("Basement water level increasing. Elderly person needs help.", "flood", "Demo apartment", "Basement", vulnerability = "Elderly person"))
    }
    private fun publish() {
        val node = local(); val now = System.currentTimeMillis()
        val own = scoped.all(node); val receiptList = scoped.receipts(node).filter { r -> own.any { it.report.id == r.receipt.reportId } }
        val paths = dao.journeys(node).filter { e -> own.any { it.report.id == e.reportId } }.map { e ->
            val a = JSONArray(e.pathJson); JourneyEvent(e.id, e.nodeId, e.reportId, e.kind, e.at, e.peerId, (0 until a.length()).map { a.getString(it) }, e.receiptId)
        }
        val peers = transport.peerStates(node).map { p ->
            val cap = capabilities[node to p.id]
            PeerView(p.id, p.phase, p.lastSeen, p.phase == PeerPhase.CONNECTED && cap?.reachable == true && cap.expiresAt > now, cap?.expiresAt ?: 0)
        }
        val archived = dao.allReports().map(store::from).filter { it.report.simulation }.map { it.nodeId }.distinct().filter { it !in environment.nodes }
        _state.value = MeshUiState(true, simulation, node, relayEnabled, relayPhase, relayDetail,
            links[node] ?: if (!simulation) physicalConnectivity() else ConnectivityState(), peers, own, receiptList, paths,
            backend, apiKey.isNotEmpty(), SimulationLabState(environment.nodes.values.toList(), environment.links.map { it.toList().let { p -> p[0] to p[1] } }, selectedSim, archived), dao.events(node),
            backgroundRelayEnabled = backgroundRelayEnabled(), backgroundRelayRunning = serviceRunning, retentionDays = retentionDays(),
            media = own.flatMap { stored -> stored.report.attachments.map { attachment ->
                val available = mediaStore.available(node, stored.report.id, attachment)
                val file = if (activityVisible && available && Triple(node, stored.report.id, attachment.id) in requestedPreviews)
                    mediaStore.file(node, stored.report.id, attachment) else null
                MediaView(stored.report.id, attachment.id, attachment.kind, available,
                    mediaStore.uploaded(node, stored.report.id, attachment, backend), file?.absolutePath)
            } })
    }
}
