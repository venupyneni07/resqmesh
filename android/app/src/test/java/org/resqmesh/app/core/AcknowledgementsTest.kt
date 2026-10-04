package org.resqmesh.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AcknowledgementsTest {
    @Test fun `malformed ACK leaves callback for valid ACK`() {
        val a = Acknowledgements(); val id = UUID.randomUUID().toString(); val results = mutableListOf<Boolean>()
        a.register("peer", id) { results.add(it) }
        assertFalse(a.acknowledge("peer", id, null))
        assertFalse(a.acknowledge("peer", id, "true"))
        assertTrue(results.isEmpty())
        assertTrue(a.acknowledge("peer", id, true)); assertEquals(listOf(true), results)
        assertFalse(a.acknowledge("peer", id, true))
    }
    @Test fun `malformed ACK still allows timeout failure and retry`() {
        val a = Acknowledgements(); val id = UUID.randomUUID().toString(); var result: Boolean? = null
        val callback: (Boolean) -> Unit = { result = it }; a.register("peer", id, callback)
        a.acknowledge("peer", id, 1); a.timeout("peer", id, callback)
        assertEquals(false, result)
    }
    @Test fun `stale timeout cannot cancel a newer attempt`() {
        val a = Acknowledgements(); val id = UUID.randomUUID().toString(); val results = mutableListOf<Boolean>()
        val old: (Boolean) -> Unit = { results.add(it) }; val fresh: (Boolean) -> Unit = { results.add(it) }
        a.register("peer", id, old); a.timeout("peer", id, old); a.register("peer", id, fresh)
        a.timeout("peer", id, old); assertTrue(a.acknowledge("peer", id, true))
        assertEquals(listOf(false, true), results)
    }
    @Test fun `wrong endpoint cannot acknowledge another peer packet`() {
        val a = Acknowledgements(); val id = UUID.randomUUID().toString(); var result: Boolean? = null
        a.register("peer", id) { result = it }
        assertFalse(a.acknowledge("stranger", id, true)); assertNull(result)
        a.disconnect("peer"); assertEquals(false, result)
    }
}
