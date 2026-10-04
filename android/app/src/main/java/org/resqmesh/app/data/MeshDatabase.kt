package org.resqmesh.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.resqmesh.app.core.*

object Wire {
    private val versionThreeFields = setOf("message_source", "quick_needs", "location_context")
    private val reportFields = setOf("schema_version", "id", "origin_id", "created_at", "expires_at", "text", "building", "zone",
        "hop_count", "max_hops", "relay_path", "simulation", "emergency_type", "location_text", "floor", "room", "people_affected", "vulnerability", "attachments") + versionThreeFields
    private val locationFields = setOf("source", "observed_at", "latitude", "longitude", "accuracy_m")
    private fun nullable(j: JSONObject, key: String) = if (!j.has(key) || j.isNull(key)) null else j.getString(key)
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    fun location(context: LocationContext): JSONObject = JSONObject().put("source", context.source)
        .put("observed_at", context.observedAt ?: JSONObject.NULL).put("latitude", context.latitude ?: JSONObject.NULL)
        .put("longitude", context.longitude ?: JSONObject.NULL).put("accuracy_m", context.accuracyM ?: JSONObject.NULL)
    fun location(j: JSONObject): LocationContext {
        require(j.keys().asSequence().all { it in locationFields }) { "Unknown location field" }
        if (j.has("source")) require(j.get("source") is String) { "Location source must be text" }
        if (j.has("observed_at") && !j.isNull("observed_at")) require(isInteger(j.get("observed_at"))) { "Observation time must be an integer" }
        for (key in listOf("latitude", "longitude", "accuracy_m")) {
            if (j.has(key) && !j.isNull(key)) require(j.get(key) is Number) { "$key must be numeric" }
        }
        return LocationContext(j.optString("source", "unknown"),
            if (!j.has("observed_at") || j.isNull("observed_at")) null else j.getLong("observed_at"),
            if (!j.has("latitude") || j.isNull("latitude")) null else j.getDouble("latitude"),
            if (!j.has("longitude") || j.isNull("longitude")) null else j.getDouble("longitude"),
            if (!j.has("accuracy_m") || j.isNull("accuracy_m")) null else j.getDouble("accuracy_m"))
    }
    private fun isInteger(value: Any) = value is Int || value is Long
    fun attachment(a: Attachment): JSONObject = JSONObject().put("id", a.id).put("kind", a.kind).put("mime_type", a.mimeType)
        .put("byte_size", a.byteSize).put("sha256", a.sha256).put("duration_ms", a.durationMs ?: JSONObject.NULL)
    fun attachment(j: JSONObject): Attachment {
        require(j.keys().asSequence().all { it in setOf("id", "kind", "mime_type", "byte_size", "sha256", "duration_ms") })
        for (key in listOf("id", "kind", "mime_type", "sha256")) require(j.get(key) is String)
        require(isInteger(j.get("byte_size")))
        if (j.has("duration_ms") && !j.isNull("duration_ms")) require(isInteger(j.get("duration_ms")))
        return Attachment(j.getString("id"), j.getString("kind"), j.getString("mime_type"), j.getLong("byte_size"), j.getString("sha256"),
            if (!j.has("duration_ms") || j.isNull("duration_ms")) null else j.getLong("duration_ms")).also { require(it.valid()) }
    }
    private fun validateVersionThreeTypes(j: JSONObject) {
        require(j.keys().asSequence().all { it in reportFields }) { "Unknown SOS field" }
        for (key in listOf("schema_version", "created_at", "expires_at", "hop_count", "max_hops")) {
            require(isInteger(j.get(key))) { "$key must be an integer" }
        }
        for (key in listOf("schema_version", "hop_count", "max_hops", "people_affected")) {
            if (j.has(key) && !j.isNull(key)) {
                val value = j.get(key)
                require(isInteger(value) && (value as Number).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is not a supported integer" }
            }
        }
        for (key in listOf("id", "origin_id")) require(j.get(key) is String) { "$key must be text" }
        if (j.has("text")) require(j.get("text") is String) { "text must be text" }
        if (j.has("message_source")) require(j.get("message_source") in setOf("user", "preset")) { "message_source must be user or preset" }
        for (key in listOf("building", "zone", "emergency_type", "location_text", "floor", "room", "vulnerability")) {
            if (j.has(key) && !j.isNull(key)) require(j.get(key) is String) { "$key must be text or null" }
        }
        require(j.get("simulation") is Boolean) { "simulation must be a boolean" }
        for (key in listOf("relay_path", "quick_needs")) {
            if (key == "quick_needs" && !j.has(key)) continue
            val list = j.getJSONArray(key)
            require((0 until list.length()).all { list.get(it) is String }) { "$key must contain text" }
        }
        if (j.has("location_context")) require(j.get("location_context") is JSONObject) { "Location context must be an object" }
    }
    fun encode(r: Report): JSONObject = JSONObject().put("schema_version", r.schemaVersion).put("id", r.id)
        .put("origin_id", r.originId).put("created_at", r.createdAt).put("expires_at", r.expiresAt)
        .put("text", r.text).put("building", r.building ?: JSONObject.NULL).put("zone", r.zone ?: JSONObject.NULL)
        .put("hop_count", r.hopCount).put("max_hops", r.maxHops).put("relay_path", JSONArray(r.relayPath))
        .put("simulation", r.simulation).also { j ->
            if (r.schemaVersion >= 2) {
                j.put("emergency_type", r.emergencyType ?: JSONObject.NULL).put("location_text", r.locationText ?: JSONObject.NULL)
                    .put("floor", r.floor ?: JSONObject.NULL).put("room", r.room ?: JSONObject.NULL)
                    .put("people_affected", r.peopleAffected ?: JSONObject.NULL).put("vulnerability", r.vulnerability ?: JSONObject.NULL)
            }
            if (r.schemaVersion >= 3) {
                j.put("message_source", r.messageSource).put("quick_needs", JSONArray(r.quickNeeds))
                    .put("location_context", location(r.locationContext))
            }
            if (r.schemaVersion >= 4) j.put("attachments", JSONArray().also { items -> r.attachments.forEach { items.put(attachment(it)) } })
        }
    fun decode(j: JSONObject): Report {
        val version = j.getInt("schema_version"); require(version in 1..4)
        if (version < 4) require(!j.has("attachments")) { "Attachments require schema version 4" }
        if (version >= 3) validateVersionThreeTypes(j)
        else require(versionThreeFields.none(j::has)) { "Quick SOS metadata requires schema version 3" }
        val originalText = if (version >= 3 && !j.has("text")) "" else j.getString("text")
        val blankPreset = version >= 3 && originalText.isBlank()
        val r = Report(j.getString("id"), j.getString("origin_id"), j.getLong("created_at"), j.getLong("expires_at"),
            if (blankPreset) QUICK_SOS_MESSAGE else originalText, nullable(j, "building"), nullable(j, "zone"), j.getInt("hop_count"), j.getInt("max_hops"),
            strings(j.getJSONArray("relay_path")), j.getBoolean("simulation"), version,
            nullable(j, "emergency_type"), nullable(j, "location_text"), nullable(j, "floor"), nullable(j, "room"),
            if (!j.has("people_affected") || j.isNull("people_affected")) null else j.getInt("people_affected"), nullable(j, "vulnerability"),
            if (blankPreset) "preset" else if (version >= 3) j.optString("message_source", "user") else "user",
            if (version >= 3) strings(j.optJSONArray("quick_needs") ?: JSONArray()) else emptyList(),
            if (version >= 3) location(j.optJSONObject("location_context") ?: JSONObject()) else LocationContext(),
            if (version >= 4 && j.has("attachments")) j.getJSONArray("attachments").let { items ->
                require(items.length() <= 3)
                (0 until items.length()).map { attachment(items.getJSONObject(it)) }
            } else emptyList())
        require(r.valid()) { "Invalid SOS envelope" }; return r
    }
    fun receipt(r: BackendReceipt) = JSONObject().put("id", r.id).put("report_id", r.reportId).put("incident_id", r.incidentId)
        .put("type", r.type).put("timestamp", r.timestamp).put("gateway_id", r.gatewayId ?: JSONObject.NULL)
        .put("simulation", r.simulation).put("relay_path", JSONArray(r.relayPath)).put("issuer", r.issuer).put("trust", r.trust)
    fun receipt(j: JSONObject): BackendReceipt = BackendReceipt(j.getString("id"), j.getString("report_id"), j.getString("incident_id"),
        j.getString("type"), j.getLong("timestamp"), nullable(j, "gateway_id"), j.getBoolean("simulation"), strings(j.getJSONArray("relay_path")),
        j.getString("issuer"), j.getString("trust")).also { require(it.valid()) }
    fun packet(p: ReceiptPacket) = JSONObject().put("receipt", receipt(p.receipt)).put("path", JSONArray(p.path))
        .put("expires_at", p.expiresAt).put("forwarded_to", JSONArray(p.forwardedTo.toList()))
    fun packet(j: JSONObject) = ReceiptPacket(receipt(j.getJSONObject("receipt")), strings(j.getJSONArray("path")), j.getLong("expires_at"),
        strings(j.optJSONArray("forwarded_to") ?: JSONArray()).toSet())
    fun control(p: ControlPacket): JSONObject = JSONObject().put("id", p.id).also { j -> when (p) {
        is ControlPacket.Inventory -> j.put("type", "inventory").put("reports", JSONArray(p.reports.take(200))).put("receipts", JSONArray(p.receipts.take(200)))
        is ControlPacket.Capability -> j.put("type", "capability").put("node_id", p.nodeId).put("reachable", p.reachable).put("observed_at", p.observedAt).put("expires_at", p.expiresAt)
        is ControlPacket.Delivery -> j.put("type", "receipt").put("packet", packet(p.packet.copy(forwardedTo = emptySet())))
        is ControlPacket.MediaChunk -> j.put("type", "media_chunk").put("report_id", p.reportId).put("attachment_id", p.attachmentId)
            .put("index", p.index).put("data_base64", p.dataBase64).put("simulation", p.simulation).put("path", JSONArray(p.path))
    } }
    fun control(j: JSONObject): ControlPacket {
        val id = j.getString("id"); java.util.UUID.fromString(id)
        return when (j.getString("type")) {
            "inventory" -> { val r = strings(j.getJSONArray("reports")); val a = strings(j.getJSONArray("receipts")); require(r.size <= 200 && a.size <= 200)
                (r + a).forEach { java.util.UUID.fromString(it) }; ControlPacket.Inventory(id, r.toSet(), a.toSet()) }
            "capability" -> ControlPacket.Capability(id, j.getString("node_id"), j.getBoolean("reachable"), j.getLong("observed_at"), j.getLong("expires_at"))
            "receipt" -> ControlPacket.Delivery(id, packet(j.getJSONObject("packet")))
            "media_chunk" -> {
                require(j.keys().asSequence().all { it in setOf("id", "type", "report_id", "attachment_id", "index", "data_base64", "simulation", "path") })
                for (key in listOf("report_id", "attachment_id", "data_base64")) require(j.get(key) is String)
                for (key in listOf("report_id", "attachment_id")) require(java.util.UUID.fromString(j.getString(key)).toString() == j.getString(key).lowercase())
                require(isInteger(j.get("index")) && j.getLong("index") in 0..511L)
                require(j.get("simulation") is Boolean && j.getString("data_base64").length <= MEDIA_MAX_BASE64_CHARS)
                val path = j.getJSONArray("path")
                require(path.length() in 1..8 && (0 until path.length()).all { path.get(it) is String })
                val nodes = strings(path)
                require(nodes.distinct().size == nodes.size && nodes.all { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) })
                ControlPacket.MediaChunk(id, j.getString("report_id"), j.getString("attachment_id"), j.getInt("index"),
                    j.getString("data_base64"), j.getBoolean("simulation"), nodes)
            }
            else -> error("Unknown control packet")
        }
    }
}

@Entity(tableName = "reports", primaryKeys = ["nodeId", "reportId"])
data class ReportEntity(val nodeId: String, val reportId: String, val json: String,
    val status: String, val forwardedJson: String, val incidentId: String?)
@Entity(tableName = "events")
data class EventEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val timestamp: Long, val nodeId: String, val description: String)
@Entity(tableName = "receipts", primaryKeys = ["nodeId", "receiptId"])
data class ReceiptEntity(val nodeId: String, val receiptId: String, val reportId: String, val json: String, val receivedAt: Long)
@Entity(tableName = "journeys")
data class JourneyEntity(@PrimaryKey val id: String, val nodeId: String, val reportId: String, val kind: String,
    val at: Long, val peerId: String?, val pathJson: String, val receiptId: String?)
@Dao interface MeshDao {
    @Query("SELECT * FROM reports WHERE nodeId = :node AND reportId = :id") fun get(node: String, id: String): ReportEntity?
    @Query("SELECT * FROM reports WHERE nodeId = :node ORDER BY rowid DESC") fun all(node: String): List<ReportEntity>
    @Query("SELECT * FROM reports ORDER BY rowid DESC") fun allReports(): List<ReportEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: ReportEntity)
    @Insert fun event(event: EventEntity)
    @Query("SELECT * FROM events WHERE nodeId = :node ORDER BY id DESC LIMIT 80") fun events(node: String): List<EventEntity>
    @Query("SELECT * FROM events ORDER BY id DESC LIMIT 100") fun events(): List<EventEntity>
    @Query("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 300)") fun trimEvents()
    @Query("SELECT * FROM receipts WHERE nodeId = :node ORDER BY receivedAt DESC") fun receipts(node: String): List<ReceiptEntity>
    @Query("SELECT * FROM receipts WHERE nodeId = :node AND receiptId = :id") fun receipt(node: String, id: String): ReceiptEntity?
    @Query("SELECT COUNT(*) FROM receipts WHERE nodeId = :node AND reportId = :reportId") fun receiptCount(node: String, reportId: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putReceipt(receipt: ReceiptEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun journey(event: JourneyEntity)
    @Query("SELECT * FROM journeys WHERE nodeId = :node ORDER BY at ASC") fun journeys(node: String): List<JourneyEntity>
    @Query("DELETE FROM reports WHERE nodeId = :node AND reportId = :reportId") fun deleteReport(node: String, reportId: String)
    @Query("DELETE FROM receipts WHERE nodeId = :node AND reportId = :reportId") fun deleteReportReceipts(node: String, reportId: String)
    @Query("DELETE FROM journeys WHERE nodeId = :node AND reportId = :reportId") fun deleteReportJourneys(node: String, reportId: String)
    @Transaction fun deleteDeliveredHistory(node: String, reportId: String) {
        deleteReportJourneys(node, reportId); deleteReportReceipts(node, reportId); deleteReport(node, reportId)
    }
}
@Database(entities = [ReportEntity::class, EventEntity::class, ReceiptEntity::class, JourneyEntity::class], version = 2, exportSchema = false)
abstract class MeshDatabase : RoomDatabase() { abstract fun dao(): MeshDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS receipts (nodeId TEXT NOT NULL, receiptId TEXT NOT NULL, reportId TEXT NOT NULL, json TEXT NOT NULL, receivedAt INTEGER NOT NULL, PRIMARY KEY(nodeId, receiptId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS journeys (id TEXT NOT NULL PRIMARY KEY, nodeId TEXT NOT NULL, reportId TEXT NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL, peerId TEXT, pathJson TEXT NOT NULL, receiptId TEXT)")
            }
        }
    }
}
class RoomReportStore(private val dao: MeshDao, private val cipher: LocalCipher = LocalCipher()) : ReportStore, ReceiptStore {
    // Relay passes read the same immutable payload often. Avoid a Keystore operation for every UI tick.
    private val decodedReports = object : LinkedHashMap<String, Pair<String, Report>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Report>>?) = size > 1024
    }
    private val decodedReceipts = object : LinkedHashMap<String, Pair<String, ReceiptPacket>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, ReceiptPacket>>?) = size > 2048
    }
    fun from(entity: ReportEntity): StoredReport {
        val peers = JSONArray(entity.forwardedJson)
        val key = "report:${entity.nodeId}:${entity.reportId}"
        val cached = decodedReports[key]
        val report = if (cached?.first == entity.json) cached.second else
            Wire.decode(JSONObject(cipher.decrypt(entity.json, key))).also { decodedReports[key] = entity.json to it }
        return StoredReport(entity.nodeId, report, DeliveryStatus.valueOf(entity.status),
            (0 until peers.length()).map { peers.getString(it) }.toSet(), entity.incidentId)
    }
    override fun get(nodeId: String, reportId: String) = dao.get(nodeId, reportId)?.let(::from)
    override fun all(nodeId: String) = dao.all(nodeId).map(::from)
    override fun save(stored: StoredReport) = dao.put(ReportEntity(stored.nodeId, stored.report.id,
        cipher.encrypt(Wire.encode(stored.report).toString(), "report:${stored.nodeId}:${stored.report.id}"), stored.status.name, JSONArray(stored.forwardedTo.toList()).toString(), stored.incidentId))
    override fun hasDeliveryReceipt(nodeId: String, reportId: String) = dao.receiptCount(nodeId, reportId) > 0
    private fun packet(entity: ReceiptEntity): ReceiptPacket {
        val key = "receipt:${entity.nodeId}:${entity.receiptId}"
        val cached = decodedReceipts[key]
        return if (cached?.first == entity.json) cached.second else
            Wire.packet(JSONObject(cipher.decrypt(entity.json, key))).also { decodedReceipts[key] = entity.json to it }
    }
    override fun receipt(node: String, id: String) = dao.receipt(node, id)?.let(::packet)
    override fun receipts(node: String) = dao.receipts(node).map(::packet)
    override fun saveReceipt(node: String, packet: ReceiptPacket) {
        val old = dao.receipt(node, packet.receipt.id)
        dao.putReceipt(ReceiptEntity(node, packet.receipt.id, packet.receipt.reportId, cipher.encrypt(Wire.packet(packet).toString(), "receipt:$node:${packet.receipt.id}"), old?.receivedAt ?: System.currentTimeMillis()))
    }
    /** Logical migration preserves all IDs, statuses, paths and original receipt receive times. */
    fun migratePayloads() {
        dao.allReports().forEach { entity ->
            if (!LocalCipher.isEncrypted(entity.json)) dao.put(entity.copy(json = cipher.encrypt(entity.json, "report:${entity.nodeId}:${entity.reportId}")))
        }
        dao.allReports().map { it.nodeId }.distinct().forEach { node ->
            dao.receipts(node).forEach { entity ->
                if (!LocalCipher.isEncrypted(entity.json)) dao.putReceipt(entity.copy(json = cipher.encrypt(entity.json, "receipt:${entity.nodeId}:${entity.receiptId}")))
            }
        }
    }
    fun journey(event: JourneyEvent) = dao.journey(JourneyEntity(event.id, event.nodeId, event.reportId, event.kind, event.at, event.peerId, JSONArray(event.path).toString(), event.receiptId))
}
