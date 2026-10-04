package org.resqmesh.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.resqmesh.app.core.*
import org.resqmesh.app.data.*
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SchemaFourWireTest {
    private val now = 1_800_000_000_000L
    private fun report() = Report(UUID.randomUUID().toString(), "RQM-MEDIA", now, now + 1_800_000,
        QUICK_SOS_MESSAGE, null, null, simulation = true, schemaVersion = 4, messageSource = "preset",
        attachments = listOf(Attachment(UUID.randomUUID().toString(), "audio", "audio/mp4", 32_000, "a".repeat(64), 5000)))

    @Test fun mediaManifestSurvivesWireAndDatabaseReopen() {
        val original = report()
        assertEquals(original, Wire.decode(JSONObject(Wire.encode(original).toString())))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "schema-four-${UUID.randomUUID()}.db"
        try {
            Room.databaseBuilder(context, MeshDatabase::class.java, name).build().let { db ->
                try { RoomReportStore(db.dao()).save(StoredReport(original.originId, original, DeliveryStatus.PENDING)) }
                finally { db.close() }
            }
            Room.databaseBuilder(context, MeshDatabase::class.java, name).build().let { db ->
                try { assertEquals(original, RoomReportStore(db.dao()).get(original.originId, original.id)!!.report) }
                finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun olderVersionsRejectAnyAttachmentMetadata() {
        for (version in 1..3) {
            val legacy = report().copy(schemaVersion = version, text = "Existing SOS", messageSource = "user", attachments = emptyList())
            val json = Wire.encode(legacy)
            assertFalse(json.has("attachments"))
            assertEquals(legacy, Wire.decode(json))
            assertRejected(json.put("attachments", JSONArray()))
        }
    }

    @Test fun malformedAttachmentMetadataCannotBeCoercedOrIgnored() {
        for ((key, value) in listOf("byte_size" to "32000", "byte_size" to 32000.5, "byte_size" to 0,
            "byte_size" to 1_048_577, "duration_ms" to "5000", "duration_ms" to 0, "duration_ms" to 30_001,
            "mime_type" to "image/jpeg", "sha256" to "A".repeat(64), "kind" to "unknown", "extra" to true)) {
            val json = Wire.encode(report()); json.getJSONArray("attachments").getJSONObject(0).put(key, value)
            assertRejected(json)
        }
        assertRejected(Wire.encode(report()).put("attachments", JSONObject.NULL))
        assertRejected(Wire.encode(report()).put("attachments", "audio"))
        val duplicate = Wire.encode(report()); val items = duplicate.getJSONArray("attachments")
        items.put(items.getJSONObject(0)); assertRejected(duplicate)
    }

    @Test fun chunkWireIsBoundedAndRoundTripsUnderNearbyPayloadLimit() {
        val r = report(); val a = r.attachments.single()
        val packet = ControlPacket.MediaChunk(reportId = r.id, attachmentId = a.id, index = 0,
            dataBase64 = Base64.getEncoder().encodeToString(ByteArray(MEDIA_CHUNK_BYTES)), simulation = true,
            path = List(8) { "node$it-" + "x".repeat(70) })
        val json = Wire.control(packet)
        assertEquals(packet, Wire.control(JSONObject(json.toString())))
        assertTrue(json.toString().toByteArray(Charsets.UTF_8).size < 24_000)
        for ((key, value) in listOf("index" to "0", "index" to -1, "index" to 512,
            "index" to 4_294_967_296L, "simulation" to "true", "path" to JSONArray().put("A").put("A"),
            "data_base64" to "a".repeat(MEDIA_MAX_BASE64_CHARS + 1), "extra" to true)) {
            assertTrue("Reject chunk $key", runCatching { Wire.control(JSONObject(json.toString()).put(key, value)) }.isFailure)
        }
    }

    private fun assertRejected(json: JSONObject) { assertTrue(runCatching { Wire.decode(json) }.isFailure) }
}
