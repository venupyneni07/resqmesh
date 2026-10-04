package org.resqmesh.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RetentionTest {
    private val report = Report(UUID.randomUUID().toString(), "origin", 1_000, 61_000, "Help", null, null, simulation = true)
    private val receipt = BackendReceipt(UUID.randomUUID().toString(), report.id, UUID.randomUUID().toString(), "backend_received", 10_000, "gateway", true, listOf("origin", "gateway"))
    @Test fun pendingExpiredAndMediaIncompleteArePreserved() {
        val now = 100L * 86_400_000
        assertFalse(eligibleForRetention(report, null, true, 7, now))
        assertFalse(eligibleForRetention(report, receipt, false, 7, now))
        assertFalse(eligibleForRetention(report, receipt, true, null, now))
        assertFalse(eligibleForRetention(report, receipt, true, 1, now))
    }
    @Test fun deliveryAgeBoundaryAndReceiptMismatch() {
        val boundary = receipt.timestamp + 7L * 86_400_000
        assertFalse(eligibleForRetention(report, receipt, true, 7, boundary - 1))
        assertTrue(eligibleForRetention(report, receipt, true, 7, boundary))
        assertFalse(eligibleForRetention(report, receipt.copy(reportId = UUID.randomUUID().toString()), true, 7, boundary))
        assertFalse(eligibleForRetention(report, receipt.copy(timestamp = boundary + 1), true, 7, boundary))
    }
}
