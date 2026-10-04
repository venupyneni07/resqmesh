package org.resqmesh.app.data

import org.resqmesh.app.core.Attachment
import org.resqmesh.app.core.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Per-device durable media. Simulated nodes get separate directories and exchange actual chunks. */
class FileMediaStore(private val root: File, private val minimumFreeBytes: Long = 64L * 1024 * 1024,
    private val cipher: LocalCipher? = null, private val previewRoot: File = File(root.parentFile, "media-preview")) : MediaStore {
    private data class Verification(val size: Long, val modified: Long, val valid: Boolean)
    private val verified = mutableMapOf<String, Verification>()
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun directory(node: String, reportId: String, attachment: Attachment): File {
        require(node.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) && attachment.valid())
        require(UUID.fromString(reportId).toString() == reportId.lowercase())
        return File(root, "${hash(node.toByteArray(Charsets.UTF_8))}/${reportId.lowercase()}/${attachment.id}/${attachment.sha256}")
    }
    private fun fingerprint(attachment: Attachment) = listOf(attachment.id, attachment.kind, attachment.mimeType,
        attachment.byteSize, attachment.sha256, attachment.durationMs).joinToString("\n")
    private fun prepare(dir: File, attachment: Attachment): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val manifest = File(dir, "manifest")
        if (manifest.exists()) return manifest.readText() == fingerprint(attachment)
        if (!hasSpace(4096)) return false
        durableWrite(manifest, fingerprint(attachment).toByteArray(Charsets.UTF_8))
        return true
    }
    private fun hasSpace(additional: Long) = root.usableSpace >= minimumFreeBytes && root.usableSpace - minimumFreeBytes >= additional
    private fun durableWrite(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, destination.name + ".writing")
        val protected = if (cipher != null && (destination.name.startsWith("chunk-") || destination.name == "complete.bin"))
            cipher.encryptBytes(bytes, destination.parentFile!!.absolutePath + "/" + destination.name) else bytes
        FileOutputStream(temporary).use { it.write(protected); it.fd.sync() }
        check(temporary.renameTo(destination)) { "Unable to commit media bytes" }
    }
    private fun plainBytes(file: File): ByteArray {
        val bytes = file.readBytes()
        return if (LocalCipher.isEncrypted(bytes)) (cipher ?: LocalCipher()).decryptBytes(bytes, file.absolutePath) else bytes
    }
    private fun validCompleted(dir: File, attachment: Attachment): Boolean {
        val manifest = File(dir, "manifest"); val completed = File(dir, "complete.bin")
        if (!completed.isFile || !manifest.isFile || manifest.readText() != fingerprint(attachment)) return false
        val cached = verified[completed.absolutePath]
        if (cached != null && cached.size == completed.length() && cached.modified == completed.lastModified()) return cached.valid
        val bytes = plainBytes(completed)
        val matches = bytes.size.toLong() == attachment.byteSize && hash(bytes) == attachment.sha256
        if (matches && cipher != null && !LocalCipher.isEncrypted(completed.readBytes())) durableWrite(completed, bytes)
        verified[completed.absolutePath] = Verification(completed.length(), completed.lastModified(), matches)
        return matches
    }
    private fun rememberVerified(completed: File) {
        verified[completed.absolutePath] = Verification(completed.length(), completed.lastModified(), true)
    }
    @Synchronized override fun available(node: String, reportId: String, attachment: Attachment): Boolean = runCatching {
        validCompleted(directory(node, reportId, attachment), attachment)
    }.getOrDefault(false)

    /** A private, expendable preview/upload copy. Durable stored bytes remain encrypted. */
    @Synchronized fun file(node: String, reportId: String, attachment: Attachment): File? = runCatching {
        val dir = directory(node, reportId, attachment)
        if (!validCompleted(dir, attachment)) return null
        val completed = File(dir, "complete.bin")
        if (!LocalCipher.isEncrypted(completed.readBytes())) return completed
        if (!previewRoot.exists()) check(previewRoot.mkdirs())
        val preview = File(previewRoot, hash(completed.absolutePath.toByteArray()) + ".bin")
        if (!preview.isFile || preview.length() != attachment.byteSize) durableWrite(preview, plainBytes(completed))
        preview
    }.getOrNull()
    @Synchronized fun clearPreviews() { previewRoot.listFiles()?.forEach { it.delete() } }

    /** Imports a bounded capture with streaming hash validation. The source remains untouched. */
    @Synchronized fun importDraft(node: String, reportId: String, attachment: Attachment, source: File): Boolean = runCatching {
        if (!attachment.valid() || !source.isFile || source.length() != attachment.byteSize) return false
        val dir = directory(node, reportId, attachment)
        if (!prepare(dir, attachment)) return false
        if (validCompleted(dir, attachment)) return true
        if (!hasSpace(attachment.byteSize)) return false
        val temporary = File(dir, "assembly.writing")
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input -> FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > attachment.byteSize) { temporary.delete(); return false }
                digest.update(buffer, 0, count); output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
        if (temporary.length() != attachment.byteSize || digest.digest().joinToString("") { "%02x".format(it) } != attachment.sha256) {
            temporary.delete(); return false
        }
        val completed = File(dir, "complete.bin")
        if (cipher == null) check(temporary.renameTo(completed))
        else { durableWrite(completed, temporary.readBytes()); temporary.delete() }
        rememberVerified(completed)
        removePartialChunks(dir, attachment)
        true
    }.getOrDefault(false)

    @Synchronized override fun readChunk(node: String, reportId: String, attachment: Attachment, index: Int): ByteArray? = runCatching {
        val length = attachment.chunkSize(index)
        if (length == 0) return null
        val dir = directory(node, reportId, attachment)
        if (!validCompleted(dir, attachment)) return null
        val bytes = plainBytes(File(dir, "complete.bin"))
        val offset = index * org.resqmesh.app.core.MEDIA_CHUNK_BYTES
        bytes.copyOfRange(offset, offset + length)
    }.getOrNull()

    @Synchronized override fun putChunk(node: String, reportId: String, attachment: Attachment, index: Int, bytes: ByteArray): Boolean = runCatching {
        if (!attachment.valid() || bytes.isEmpty() || bytes.size != attachment.chunkSize(index)) return false
        val dir = directory(node, reportId, attachment)
        if (!prepare(dir, attachment)) return false
        if (validCompleted(dir, attachment)) return readChunk(node, reportId, attachment, index)?.contentEquals(bytes) == true
        val chunk = File(dir, "chunk-$index")
        // Reject conflicting retransmissions without allowing a peer to erase already persisted chunks.
        if (chunk.exists() && !plainBytes(chunk).contentEquals(bytes)) return false
        if (!chunk.exists()) {
            if (!hasSpace(bytes.size.toLong())) return false
            durableWrite(chunk, bytes)
        }
        if ((0 until attachment.chunkCount).any { part -> (!File(dir, "chunk-$part").isFile || plainBytes(File(dir, "chunk-$part")).size != attachment.chunkSize(part)) }) return true
        if (!hasSpace(attachment.byteSize)) return false
        val temporary = File(dir, "assembly.writing")
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(temporary).use { output ->
            for (part in 0 until attachment.chunkCount) {
                val piece = plainBytes(File(dir, "chunk-$part"))
                digest.update(piece); output.write(piece)
            }
            output.fd.sync()
        }
        if (temporary.length() != attachment.byteSize || digest.digest().joinToString("") { "%02x".format(it) } != attachment.sha256) {
            temporary.delete()
            // No complete file is exposed. A sender receiving this negative ACK restarts from chunk zero.
            removePartialChunks(dir, attachment)
            return false
        }
        val completed = File(dir, "complete.bin")
        if (cipher == null) check(temporary.renameTo(completed))
        else { durableWrite(completed, temporary.readBytes()); temporary.delete() }
        rememberVerified(completed)
        removePartialChunks(dir, attachment)
        true
    }.getOrDefault(false)

    private fun removePartialChunks(dir: File, attachment: Attachment) {
        for (part in 0 until attachment.chunkCount) File(dir, "chunk-$part").delete()
    }

    /** Called only after receipt/age/media-confirmation retention checks on the serial coordinator. */
    @Synchronized fun removeReport(node: String, reportId: String): Boolean {
        require(node.matches(Regex("[A-Za-z0-9_.:-]{1,80}")))
        require(UUID.fromString(reportId).toString() == reportId.lowercase())
        val dir = File(root, "${hash(node.toByteArray(Charsets.UTF_8))}/${reportId.lowercase()}")
        verified.keys.filter { it.startsWith(dir.absolutePath + File.separator) }.forEach {
            File(previewRoot, hash(it.toByteArray()) + ".bin").delete()
        }
        verified.keys.removeAll { it.startsWith(dir.absolutePath + File.separator) }
        return !dir.exists() || dir.deleteRecursively()
    }

    /** A backend availability observation is independent of whether this node has its own complete copy. */
    @Synchronized fun uploaded(node: String, reportId: String, attachment: Attachment, backend: String): Boolean = runCatching {
        File(directory(node, reportId, attachment), "uploaded-${hash(backend.toByteArray(Charsets.UTF_8))}").isFile
    }.getOrDefault(false)

    @Synchronized fun markUploaded(node: String, reportId: String, attachment: Attachment, backend: String): Boolean = runCatching {
        val dir = directory(node, reportId, attachment)
        if (!prepare(dir, attachment)) return false
        if (!hasSpace(4096)) return false
        durableWrite(File(dir, "uploaded-${hash(backend.toByteArray(Charsets.UTF_8))}"), attachment.sha256.toByteArray(Charsets.UTF_8))
        true
    }.getOrDefault(false)
}
