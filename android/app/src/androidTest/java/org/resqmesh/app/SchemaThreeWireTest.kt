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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SchemaThreeWireTest {
    private val now = 1_800_000_000_000L
    private fun report() = Report(UUID.randomUUID().toString(), "RQM-SCHEMA", now, now + 1_800_000,
        QUICK_SOS_MESSAGE, null, null, simulation = true, schemaVersion = 3, messageSource = "preset",
        quickNeeds = listOf("cannot_move", "cannot_speak"),
        locationContext = LocationContext("saved", now - 86_400_000, 12.9716, 77.5946, 24.5))

    @Test fun versionThreeMetadataSurvivesJsonAndDatabaseReopen() {
        val original = report()
        val json = Wire.encode(original)
        assertEquals("preset", json.getString("message_source"))
        assertEquals(now - 86_400_000, json.getJSONObject("location_context").getLong("observed_at"))
        assertEquals(original, Wire.decode(JSONObject(json.toString())))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "schema-three-${UUID.randomUUID()}.db"
        try {
            val first = Room.databaseBuilder(context, MeshDatabase::class.java, name).build()
            try { RoomReportStore(first.dao()).save(StoredReport(original.originId, original, DeliveryStatus.PENDING)) }
            finally { first.close() }
            val reopened = Room.databaseBuilder(context, MeshDatabase::class.java, name).build()
            try {
                val restored = RoomReportStore(reopened.dao()).get(original.originId, original.id)!!
                assertEquals(original, restored.report)
                assertEquals(DeliveryStatus.PENDING, restored.status)
                assertNull(restored.report.peopleAffected)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun zeroEntryWireNormalizesBlankOrOmittedMessageWithoutGuessingDetails() {
        for (text in listOf("", "  \n\t")) {
            val packet = Wire.encode(report()).put("text", text).put("message_source", "user")
                .put("quick_needs", JSONArray()).put("location_context", JSONObject())
            val decoded = Wire.decode(packet)
            assertEquals(QUICK_SOS_MESSAGE, decoded.text)
            assertEquals("preset", decoded.messageSource)
            assertEquals(LocationContext(), decoded.locationContext)
            assertTrue(decoded.quickNeeds.isEmpty())
            assertNull(decoded.peopleAffected)
        }
        val omitted = Wire.encode(report()).apply {
            remove("text"); remove("message_source"); remove("quick_needs"); remove("location_context")
        }
        assertEquals("preset", Wire.decode(omitted).messageSource)
        assertEquals(LocationContext(), Wire.decode(omitted).locationContext)
        assertRejected(omitted.put("message_source", JSONObject.NULL))
        assertRejected(omitted.put("message_source", "invented"))
    }

    @Test fun legacyWireOmitsNewMetadataAndKeepsItsOriginalEnvelope() {
        for (version in 1..2) {
            val original = report().copy(schemaVersion = version, text = "Existing SOS", messageSource = "user",
                quickNeeds = emptyList(), locationContext = LocationContext())
            val json = Wire.encode(original)
            for (key in listOf("message_source", "quick_needs", "location_context")) assertFalse(json.has(key))
            assertEquals(original, Wire.decode(JSONObject(json.toString())))
            assertEquals(version == 2, json.has("emergency_type"))
            assertRejected(json.put("quick_needs", JSONArray()))
            assertRejected(Wire.encode(original).put("text", " "))
        }
    }

    @Test fun malformedMetadataTypesAreRejectedInsteadOfCoercedOrDropped() {
        for ((key, bad) in listOf(
            "text" to 42, "message_source" to JSONObject.NULL, "quick_needs" to JSONObject.NULL,
            "quick_needs" to "cannot_move", "quick_needs" to JSONArray().put(4),
            "quick_needs" to JSONArray().put("cannot_move").put("cannot_move"),
            "quick_needs" to JSONArray().put("invented"), "location_context" to JSONObject.NULL,
            "location_context" to "device", "people_affected" to 1.5, "people_affected" to "2",
            "people_affected" to 4_294_967_296L, "created_at" to now.toString(),
            "created_at" to now.toDouble(), "hop_count" to 4_294_967_296L,
            "simulation" to "true", "relay_path" to JSONArray().put(JSONObject.NULL), "unknown_field" to true
        )) assertRejected(Wire.encode(report()).put(key, bad), key)
        for ((key, bad) in listOf(
            "source" to 7, "source" to JSONObject.NULL, "observed_at" to now.toString(), "observed_at" to now.toDouble(),
            "latitude" to "12.9716", "longitude" to "77.5946", "accuracy_m" to "24.5", "extra" to true
        )) {
            val json = Wire.encode(report())
            json.getJSONObject("location_context").put(key, bad)
            assertRejected(json, "location_context.$key")
        }
    }

    @Test fun malformedLocationFactsAndProvenanceCannotPassValidation() {
        val invalid = listOf(
            LocationContext("unknown", now), LocationContext("device", now),
            LocationContext("manual", null), LocationContext("saved", now + 300_001),
            LocationContext("device", now, 12.9, null), LocationContext("device", now, 91.0, 77.6),
            LocationContext("device", now, 12.9, -181.0), LocationContext("device", now, 12.9, 77.6, -1.0)
        )
        invalid.forEach { assertRejected(Wire.encode(report().copy(locationContext = it))) }
        assertRejected(Wire.encode(report()).put("text", "Changed text"))
        assertRejected(Wire.encode(report()).put("text", "help\u0000").put("message_source", "user"))
    }

    private fun assertRejected(json: JSONObject, label: String = "malformed report") {
        assertTrue("Rejected $label", runCatching { Wire.decode(json) }.isFailure)
    }
}
