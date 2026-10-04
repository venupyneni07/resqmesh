package org.resqmesh.app.core

import java.util.Base64
import java.util.UUID

const val MEDIA_CHUNK_BYTES = 16 * 1024
const val MEDIA_MAX_TOTAL_BYTES = 10L * 1024 * 1024
const val MEDIA_MAX_BASE64_CHARS = ((MEDIA_CHUNK_BYTES + 2) / 3) * 4

/** The immutable manifest travels with the small alert, before the media bytes. */
data class Attachment(val id: String, val kind: String, val mimeType: String, val byteSize: Long,
    val sha256: String, val durationMs: Long? = null) {
    fun valid(): Boolean = runCatching {
        UUID.fromString(id).toString() == id && sha256.matches(Regex("[a-f0-9]{64}")) &&
            when (kind) {
                "image" -> mimeType == "image/jpeg" && byteSize in 1..1_048_576L && durationMs == null
                "audio" -> mimeType == "audio/mp4" && byteSize in 1..1_048_576L && (durationMs == null || durationMs in 1..30_000L)
                "video" -> mimeType == "video/mp4" && byteSize in 1..8_388_608L && (durationMs == null || durationMs in 1..15_000L)
                else -> false
            }
    }.getOrDefault(false)
    val chunkCount: Int get() = ((byteSize + MEDIA_CHUNK_BYTES - 1) / MEDIA_CHUNK_BYTES).toInt()
    fun chunkSize(index: Int): Int = if (index !in 0 until chunkCount) 0
        else minOf(MEDIA_CHUNK_BYTES.toLong(), byteSize - index.toLong() * MEDIA_CHUNK_BYTES).toInt()
}

fun validAttachments(items: List<Attachment>) = items.size <= 3 && items.map { it.id }.distinct().size == items.size &&
    items.all { it.valid() } && items.sumOf { it.byteSize } <= MEDIA_MAX_TOTAL_BYTES

/** Storage must acknowledge only durable bytes, and expose a complete file only after its hash matches. */
interface MediaStore {
    fun available(node: String, reportId: String, attachment: Attachment): Boolean
    fun readChunk(node: String, reportId: String, attachment: Attachment, index: Int): ByteArray?
    fun putChunk(node: String, reportId: String, attachment: Attachment, index: Int, bytes: ByteArray): Boolean
}

/**
 * Caller serializes access and transport callbacks, as with RelayEngine. An alert is sent first;
 * receipt arrival does not suppress media. Each transfer has one outstanding chunk and retries
 * failed chunks from zero with bounded backoff. Receivers retain valid partial chunks across restarts.
 */
class MediaRelay(private val reports: ReportStore, private val media: MediaStore,
    private val transport: Transport, private val now: () -> Long, private val simulation: Boolean,
    private val onProgress: () -> Unit = {}) {
    private data class Key(val node: String, val report: String, val attachment: String, val peer: String)
    private data class Transfer(var next: Int = 0, var failures: Int = 0, var retryAt: Long = 0, var sending: Boolean = false)
    private val transfers = mutableMapOf<Key, Transfer>()

    fun receive(node: String, from: String, packet: ControlPacket.MediaChunk): Boolean {
        if (packet.simulation != simulation || from == node || from !in transport.peers(node) ||
            packet.dataBase64.length > MEDIA_MAX_BASE64_CHARS) return false
        val report = reports.get(node, packet.reportId)?.report ?: return false
        if (!report.valid() || report.schemaVersion < 4 || report.simulation != simulation ||
            report.expiresAt <= now() || report.createdAt > now() + 60_000 || report.relayPath.last() != node ||
            packet.path.size !in 1..report.maxHops || packet.path.first() != report.originId || packet.path.last() != from ||
            packet.path.distinct().size != packet.path.size || node in packet.path ||
            packet.path.any { !it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) }) return false
        val attachment = report.attachments.singleOrNull { it.id == packet.attachmentId } ?: return false
        if (attachment.chunkSize(packet.index) == 0) return false
        val bytes = runCatching { Base64.getDecoder().decode(packet.dataBase64) }.getOrNull() ?: return false
        if (bytes.size != attachment.chunkSize(packet.index) || Base64.getEncoder().encodeToString(bytes) != packet.dataBase64) return false
        return runCatching { media.putChunk(node, report.id, attachment, packet.index, bytes) }.getOrDefault(false)
    }

    /** Issues at most four transfers per pass. True means work was issued, not that another immediate pass is needed. */
    fun relay(node: String): Boolean {
        var issued = 0
        for (stored in reports.all(node).sortedBy { it.report.id }) {
            val report = stored.report
            if (!report.valid() || report.schemaVersion < 4 || report.simulation != simulation ||
                report.expiresAt <= now() || report.createdAt > now() + 60_000 || report.hopCount >= report.maxHops ||
                report.relayPath.last() != node) continue
            for (peer in transport.peers(node).sorted()) {
                if (peer in report.relayPath || (peer !in stored.forwardedTo && report.id !in transport.knownReports(node, peer))) continue
                for (attachment in report.attachments) {
                    if (!media.available(node, report.id, attachment)) continue
                    val key = Key(node, report.id, attachment.id, peer)
                    val transfer = transfers.getOrPut(key) { Transfer() }
                    if (transfer.sending || transfer.next >= attachment.chunkCount || transfer.retryAt > now()) continue
                    val index = transfer.next
                    val bytes = media.readChunk(node, report.id, attachment, index) ?: continue
                    transfer.sending = true
                    issued++
                    val packet = ControlPacket.MediaChunk(reportId = report.id, attachmentId = attachment.id, index = index,
                        dataBase64 = Base64.getEncoder().encodeToString(bytes), simulation = simulation, path = report.relayPath)
                    // Some transports can throw after calling their callback; process a completion only once.
                    var completed = false
                    val finish: (Boolean) -> Unit = finish@ { ok ->
                        if (completed) return@finish
                        completed = true
                        transfer.sending = false
                        if (ok) {
                            transfer.next = index + 1
                            transfer.failures = 0
                            transfer.retryAt = 0
                            onProgress()
                        } else {
                            transfer.next = 0
                            transfer.failures = minOf(transfer.failures + 1, 6)
                            transfer.retryAt = now() + minOf(30_000L, 1_000L shl (transfer.failures - 1))
                        }
                    }
                    try { transport.sendControl(node, peer, packet, finish) } catch (_: Exception) { finish(false) }
                    if (issued >= 4) return true
                }
            }
        }
        return issued > 0
    }
}
