package org.resqmesh.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.resqmesh.app.core.*
import org.resqmesh.app.data.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only isolated test DB/preferences/files. Never deletes the user's history or changes live keys. */
@RunWith(AndroidJUnit4::class)
class ReliabilityStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyCredentialMigratesAndPayloadsRejectTampering() {
        val suffix = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("test-$suffix-$name", mode)
        }
        val legacy = isolated.getSharedPreferences("resqmesh", 0)
        legacy.edit().putString("apiKey", "synthetic-secret-$suffix").commit()
        val vault = CredentialStore(isolated, "test-vault-$suffix")
        try {
            assertEquals("synthetic-secret-$suffix", vault.migrate(isolated))
            assertFalse(legacy.contains("apiKey"))
            assertEquals("synthetic-secret-$suffix", CredentialStore(isolated, "test-vault-$suffix").read())
            val cipher = LocalCipher()
            val encrypted = cipher.encrypt("Synthetic private report", "test-record")
            assertFalse(encrypted.contains("Synthetic private report"))
            assertEquals("Synthetic private report", cipher.decrypt(encrypted, "test-record"))
            assertTrue(runCatching { cipher.decrypt(encrypted, "another-record") }.isFailure)
        } finally { vault.write(""); legacy.edit().clear().commit() }
    }
    @Test fun reportMigrationRetainsPendingAndMediaEncryptionSurvivesReopen() {
        val db = Room.inMemoryDatabaseBuilder(context, MeshDatabase::class.java).build()
        val root = File(context.cacheDir, "reliability-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val report = Report(UUID.randomUUID().toString(), "test-node", 1_000, 61_000, "Synthetic pending help", null, null, simulation = true)
            db.dao().put(ReportEntity(report.originId, report.id, Wire.encode(report).toString(), DeliveryStatus.PENDING.name, "[]", null))
            val store = RoomReportStore(db.dao()); store.migratePayloads()
            assertTrue(LocalCipher.isEncrypted(db.dao().get(report.originId, report.id)!!.json))
            assertEquals(report, RoomReportStore(db.dao()).get(report.originId, report.id)!!.report)
            assertFalse(eligibleForRetention(report, null, true, 7, 100L * 86_400_000))
            val bytes = ByteArray(100_000) { (it % 251).toByte() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val attachment = Attachment(UUID.randomUUID().toString(), "audio", "audio/mp4", bytes.size.toLong(), sha, 1000)
            val source = File(root, "synthetic.m4a").apply { writeBytes(bytes) }
            val durable = File(root, "durable").apply { mkdirs() }
            val media = FileMediaStore(durable, cipher = LocalCipher(), previewRoot = File(root, "preview"))
            assertTrue(media.importDraft(report.originId, report.id, attachment, source))
            val encryptedFile = durable.walkTopDown().single { it.name == "complete.bin" }
            assertTrue(LocalCipher.isEncrypted(encryptedFile.readBytes()))
            val reopened = FileMediaStore(durable, cipher = LocalCipher(), previewRoot = File(root, "preview"))
            assertArrayEquals(bytes, reopened.file(report.originId, report.id, attachment)!!.readBytes())
            val receiver = "test-receiver"
            for (index in 0 until attachment.chunkCount) {
                assertTrue(reopened.putChunk(receiver, report.id, attachment, index, reopened.readChunk(report.originId, report.id, attachment, index)!!))
            }
            assertArrayEquals(bytes, reopened.file(receiver, report.id, attachment)!!.readBytes())
            encryptedFile.writeBytes(encryptedFile.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFalse(FileMediaStore(durable, cipher = LocalCipher()).available(report.originId, report.id, attachment))
        } finally { db.close(); root.deleteRecursively() }
    }
    @Test fun duplicateSubmissionAndReconstructedJournalResolveToOneStoredReport() {
        val app = context.applicationContext as MeshApplication
        val mesh = app.mesh
        org.junit.Assume.assumeFalse("Keep an active user background session untouched", mesh.backgroundRelayEnabled())
        val journal = context.getSharedPreferences("sos-submission", 0)
        org.junit.Assume.assumeFalse("Do not touch an outstanding user submission", journal.contains("operation"))
        mesh.background()
        val id = UUID.randomUUID().toString()
        val db = Room.databaseBuilder(context, MeshDatabase::class.java, "resqmesh.db").addMigrations(MeshDatabase.MIGRATION_1_2).build()
        try {
            repeat(2) {
                val saved = java.util.concurrent.CountDownLatch(1)
                mesh.createWithSubmissionId(SosDraft("SYNTHETIC local interruption test"), id) { result, error ->
                    assertNull(error); assertEquals(id, result); saved.countDown()
                }
                assertTrue(saved.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertEquals(1, db.dao().allReports().count { it.reportId == id })
            journal.edit().putString("operation", LocalCipher().encrypt(JSONObject().put("id", id)
                .put("clearDraft", true).put("clearMedia", false).toString())).commit()
            val restored = SosSubmissionViewModel(app)
            val until = android.os.SystemClock.elapsedRealtime() + 10_000
            while (restored.state.value.pending && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(50)
            assertEquals(id, restored.state.value.reportId)
            assertTrue(restored.state.value.clearDraft)
            assertEquals(1, db.dao().allReports().count { it.reportId == id })
            restored.consumeResult()
            assertFalse(journal.contains("operation"))
        } finally {
            journal.edit().remove("operation").commit()
            db.dao().allReports().filter { it.reportId == id }.forEach { db.dao().deleteDeliveredHistory(it.nodeId, id) }
            db.close(); mesh.refresh()
        }
    }

    @Test fun exportDecodedPreservationEvidence() {
        val mesh = (context.applicationContext as MeshApplication).mesh
        val ready = java.util.concurrent.CountDownLatch(1)
        mesh.serial.execute { ready.countDown() }
        assertTrue(ready.await(30, java.util.concurrent.TimeUnit.SECONDS))
        val db = Room.databaseBuilder(context, MeshDatabase::class.java, "resqmesh.db").addMigrations(MeshDatabase.MIGRATION_1_2).build()
        try {
            val cipher = LocalCipher()
            val rows = db.dao().allReports()
            val output = JSONObject().put("reports", JSONArray(rows.map { e ->
                JSONObject().put("nodeId", e.nodeId).put("reportId", e.reportId)
                    .put("json", JSONObject(cipher.decrypt(e.json, "report:${e.nodeId}:${e.reportId}")))
                    .put("status", e.status).put("forwardedJson", JSONArray(e.forwardedJson)).put("incidentId", e.incidentId ?: JSONObject.NULL)
            }))
            val prefs = JSONObject()
            context.getSharedPreferences("resqmesh", 0).all.forEach { (key, value) ->
                prefs.put(key, if (value is String && LocalCipher.isEncrypted(value)) cipher.decrypt(value) else value)
            }
            output.put("preferences", prefs)
            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            output.put("gatewayCredentialSha256", sha(CredentialStore(context).read().toByteArray(Charsets.UTF_8)))
            val files = JSONObject()
            File(context.filesDir, "media").walkTopDown().filter { it.isFile }.forEach { file ->
                val bytes = file.readBytes()
                files.put(file.relativeTo(context.filesDir).path, sha(if (LocalCipher.isEncrypted(bytes)) cipher.decryptBytes(bytes, file.absolutePath) else bytes))
            }
            output.put("mediaHashes", files)
            val dir = File(context.getExternalFilesDir(null), "local-completion-state").apply { mkdirs() }
            File(dir, "preservation-decoded.json").writeText(output.toString(2))
            assertTrue(rows.all { LocalCipher.isEncrypted(it.json) })
            assertTrue("Migration compaction completed", context.getSharedPreferences("resqmesh", 0).getBoolean("payloadStorageCompacted", false))
        } finally { db.close() }
    }

}
