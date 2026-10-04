package org.resqmesh.app.core

import java.util.UUID

/** A malformed ACK cannot consume the pending callback or permanently suppress a retry. */
class Acknowledgements {
    private val pending = mutableMapOf<Pair<String, String>, (Boolean) -> Unit>()
    fun register(peer: String, id: String, completed: (Boolean) -> Unit) { pending[peer to id] = completed }
    fun acknowledge(peer: String, id: Any?, stored: Any?): Boolean {
        if (id !is String || stored !is Boolean || runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false).not()) return false
        val completed = pending.remove(peer to id) ?: return false
        completed(stored)
        return true
    }
    fun timeout(peer: String, id: String, expected: (Boolean) -> Unit) {
        val key = peer to id
        if (pending[key] === expected) pending.remove(key)?.invoke(false)
    }
    fun disconnect(peer: String) {
        pending.keys.filter { it.first == peer }.toList().forEach { pending.remove(it)?.invoke(false) }
    }
    fun clear() { val callbacks = pending.values.toList(); pending.clear(); callbacks.forEach { it(false) } }
}
