package org.resqmesh.app.core

/** Stable bounded batches rotate even after failures, so one bad report cannot starve the queue. */
fun <T> rotatingBatch(items: List<T>, afterId: String?, limit: Int, id: (T) -> String): List<T> {
    require(limit > 0)
    val ordered = items.sortedBy(id)
    if (ordered.isEmpty()) return emptyList()
    val start = if (afterId == null) 0 else ordered.indexOfFirst { id(it) > afterId }.let { if (it < 0) 0 else it }
    return (ordered.drop(start) + ordered.take(start)).take(limit)
}
