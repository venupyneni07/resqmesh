package org.resqmesh.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.resqmesh.app.core.*
import org.resqmesh.app.data.*
import org.json.JSONObject
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    @Test fun legacyVersionOneMigratesWithoutReplacingReportsOrEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        val original = Report(UUID.randomUUID().toString(), "phone-legacy", now, now + 1_800_000,
            "Original legacy SOS", "Original building", "Basement", simulation = false)
        try {
            val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE reports (nodeId TEXT NOT NULL, reportId TEXT NOT NULL, json TEXT NOT NULL, status TEXT NOT NULL, forwardedJson TEXT NOT NULL, incidentId TEXT, PRIMARY KEY(nodeId, reportId))")
                db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, nodeId TEXT NOT NULL, description TEXT NOT NULL)")
                db.insertOrThrow("reports", null, ContentValues().apply {
                    put("nodeId", original.originId); put("reportId", original.id); put("json", Wire.encode(original).toString())
                    put("status", "FORWARDED"); put("forwardedJson", "[\"peer-legacy\"]"); putNull("incidentId")
                })
                db.execSQL("INSERT INTO events(timestamp,nodeId,description) VALUES(?,?,?)", arrayOf(now, original.originId, "Original event"))
                db.version = 1
            }
            val db = Room.databaseBuilder(context, MeshDatabase::class.java, name).addMigrations(MeshDatabase.MIGRATION_1_2).build()
            val receipt = BackendReceipt(UUID.randomUUID().toString(), original.id, UUID.randomUUID().toString(), "backend_received", now,
                original.originId, false, listOf(original.originId))
            try {
                val store = RoomReportStore(db.dao()); val restored = store.get(original.originId, original.id)!!
                assertEquals(original, restored.report); assertEquals(setOf("peer-legacy"), restored.forwardedTo)
                assertEquals(DeliveryStatus.FORWARDED, restored.status); assertEquals("Original event", db.dao().events(original.originId).single().description)
                store.saveReceipt(original.originId, ReceiptPacket(receipt, listOf(original.originId), now + 86_400_000))
                store.journey(JourneyEvent(nodeId = original.originId, reportId = original.id, kind = "backend_received", at = now, receiptId = receipt.id))
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context, MeshDatabase::class.java, name).addMigrations(MeshDatabase.MIGRATION_1_2).build()
            try {
                assertEquals(original, RoomReportStore(reopened.dao()).get(original.originId, original.id)!!.report)
                assertEquals(receipt, RoomReportStore(reopened.dao()).receipts(original.originId).single().receipt)
                assertEquals("backend_received", reopened.dao().journeys(original.originId).single().kind)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun structuredWireAndReturnedReceiptRoundTripPreserveFacts() {
        val now = System.currentTimeMillis(); val id = UUID.randomUUID().toString()
        val report = Report(id, "RQM-TEST", now, now + 1_800_000, "Water entering room", "Block 7", "North",
            simulation = true, schemaVersion = 2, emergencyType = "flood", locationText = "Beside staircase", floor = "Ground",
            room = "G04", peopleAffected = 3, vulnerability = "Elderly person")
        assertEquals(report, Wire.decode(JSONObject(Wire.encode(report).toString())))
        val receipt = BackendReceipt(UUID.randomUUID().toString(), id, UUID.randomUUID().toString(), "responder_acknowledged", now,
            null, true, listOf("RQM-TEST", "RQM-CARRIER"))
        val packet = ControlPacket.Delivery(packet = ReceiptPacket(receipt, listOf("RQM-CARRIER"), now + 86_400_000))
        assertEquals(packet, Wire.control(JSONObject(Wire.control(packet).toString())))
        val legacy = report.copy(schemaVersion = 1, emergencyType = null, locationText = null, floor = null, room = null, peopleAffected = null, vulnerability = null)
        assertEquals(legacy, Wire.decode(Wire.encode(legacy)))
        assertFalse(Wire.encode(legacy).has("emergency_type"))
    }
    @Test fun reportAndForwardingStateSurviveDatabaseReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "test-${UUID.randomUUID()}.db"
        val report = Report(UUID.randomUUID().toString(), "A", 1_800_000_000_000,
            1_800_000_100_000, "Floodwater rising", "Apartment", "Ground floor", simulation = true)
        try {
            val first = Room.databaseBuilder(context, MeshDatabase::class.java, databaseName).build()
            RoomReportStore(first.dao()).save(StoredReport("A", report, DeliveryStatus.FORWARDED, setOf("B")))
            first.close()
            val reopened = Room.databaseBuilder(context, MeshDatabase::class.java, databaseName).build()
            try {
                val restored = RoomReportStore(reopened.dao()).get("A", report.id)!!
                assertEquals(report, restored.report)
                assertEquals(DeliveryStatus.FORWARDED, restored.status)
                assertEquals(setOf("B"), restored.forwardedTo)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(databaseName) }
    }
}
