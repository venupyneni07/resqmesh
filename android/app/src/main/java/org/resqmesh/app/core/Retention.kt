package org.resqmesh.app.core

/** Retention age starts at confirmed delivery, never merely at creation or expiry. */
fun eligibleForRetention(
    report: Report,
    receipt: BackendReceipt?,
    allMediaConfirmed: Boolean,
    keepDays: Int?,
    now: Long,
): Boolean {
    if (keepDays !in setOf(7, 30, 90) || receipt == null || !allMediaConfirmed) return false
    if (!receipt.consistentWith(report) || receipt.type !in setOf("backend_received", "responder_acknowledged")) return false
    val latest = maxOf(report.createdAt, receipt.timestamp)
    return latest > 0 && latest <= now && now - latest >= keepDays!!.toLong() * 86_400_000L
}
