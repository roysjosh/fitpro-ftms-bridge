package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [CccSubscriptions] — the
 * per-characteristic CCCD state behind FTMS notification delivery (GATT: a
 * characteristic is delivered only to clients that enabled ITS CCCD).
 * Plain JUnit 4, no Android types: centrals are address Strings and
 * descriptors are dummy [Any]/String tokens.
 */
class CccSubscriptionsTest {

    private val keyA = "feature"
    private val keyB = "indoorBike"

    @Test
    fun `enabling a descriptor's CCCD affects only its own characteristic`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        val descB = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.registerDescriptor(descB, keyB)

        ccc.setEnabled(descA, "AA:BB", true)

        assertEquals(setOf("AA:BB"), ccc.subsFor(keyA))
        assertTrue(ccc.subsFor(keyB).isEmpty())
        assertTrue(ccc.enabledFor(keyA, "AA:BB"))
        assertFalse(ccc.enabledFor(keyB, "AA:BB"))
    }

    @Test
    fun `charKeyFor resolves by instance and distinguishes descriptors`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        val descB = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.registerDescriptor(descB, keyB)

        assertEquals(keyA, ccc.charKeyFor(descA))
        assertEquals(keyB, ccc.charKeyFor(descB))
    }

    @Test
    fun `disabling removes the device from that characteristic only`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        val descB = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.registerDescriptor(descB, keyB)
        ccc.setEnabled(descA, "AA:BB", true)
        ccc.setEnabled(descB, "AA:BB", true)

        ccc.setEnabled(descA, "AA:BB", false)

        assertTrue(ccc.subsFor(keyA).isEmpty())
        assertEquals(setOf("AA:BB"), ccc.subsFor(keyB))
        assertTrue(ccc.hasAny())
    }

    @Test
    fun `clearDevice removes the central from every characteristic`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        val descB = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.registerDescriptor(descB, keyB)
        ccc.setEnabled(descA, "AA:BB", true)
        ccc.setEnabled(descB, "AA:BB", true)

        ccc.clearDevice("AA:BB")

        assertTrue(ccc.subsFor(keyA).isEmpty())
        assertTrue(ccc.subsFor(keyB).isEmpty())
        assertFalse(ccc.anyEnabledFor("AA:BB"))
        assertFalse(ccc.hasAny())
    }

    @Test
    fun `anyEnabledFor and hasAny track transitions across characteristics`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        val descB = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.registerDescriptor(descB, keyB)

        assertFalse(ccc.anyEnabledFor("AA:BB"))
        assertFalse(ccc.hasAny())

        ccc.setEnabled(descA, "AA:BB", true)
        assertTrue(ccc.anyEnabledFor("AA:BB"))
        assertTrue(ccc.hasAny())
        // hasAny() is true because of keyA, but keyB still has no subscriber.
        assertTrue(ccc.subsFor(keyB).isEmpty())
        assertFalse(ccc.anyEnabledFor("CC:DD"))

        ccc.clearDevice("AA:BB")
        assertFalse(ccc.anyEnabledFor("AA:BB"))
        assertFalse(ccc.hasAny())
    }

    @Test
    fun `an unknown descriptor resolves to null and enable falls back to all characteristics`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        val descB = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.registerDescriptor(descB, keyB)

        val unknown = "a-descriptor-instance-never-registered"
        assertNull(ccc.charKeyFor(unknown))

        ccc.setEnabled(unknown, "AA:BB", true)
        // Fallback: the write applies to every registered characteristic.
        assertTrue(ccc.subsFor(keyA).contains("AA:BB"))
        assertTrue(ccc.subsFor(keyB).contains("AA:BB"))
        assertTrue(ccc.hasAny())

        // And the fallback disable is symmetric.
        ccc.setEnabled(unknown, "AA:BB", false)
        assertTrue(ccc.subsFor(keyA).isEmpty())
        assertTrue(ccc.subsFor(keyB).isEmpty())
    }

    @Test
    fun `clearAll empties subscriptions and the descriptor registry`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        val descB = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.registerDescriptor(descB, keyB)
        ccc.setEnabled(descA, "AA:BB", true)
        ccc.setEnabled(descB, "CC:DD", true)

        ccc.clearAll()

        assertFalse(ccc.hasAny())
        assertTrue(ccc.subsFor(keyA).isEmpty())
        assertTrue(ccc.subsFor(keyB).isEmpty())
        assertFalse(ccc.anyEnabledFor("AA:BB"))
        assertNull(ccc.charKeyFor(descA))
        assertNull(ccc.charKeyFor(descB))
    }

    @Test
    fun `subsFor returns a copy — mutating the result does not touch the state`() {
        val ccc = CccSubscriptions()
        val descA = Any()
        ccc.registerDescriptor(descA, keyA)
        ccc.setEnabled(descA, "AA:BB", true)

        // The snapshot is a fresh set; removing from it must not reach the
        // live subscription state.
        (ccc.subsFor(keyA) as MutableSet<String>).remove("AA:BB")

        assertTrue(ccc.subsFor(keyA).contains("AA:BB"))
        assertTrue(ccc.hasAny())
    }

    // FTMS §4.17.1: a Machine Status updated by a CLIENT (via the Control
    // Point) is notified to the OTHER connected clients, if any — the
    // writing central is excluded. The server computes exactly this: the
    // characteristic's subscriber set minus the writer's address. (User-
    // driven updates are NOT excluded; §4.10.1 gives Training Status no such
    // exclusion at all.)

    @Test
    fun `client-driven set is the subscribers minus the writer`() {
        val key = "machineStatus"
        val ccc = CccSubscriptions()
        val desc = Any()
        ccc.registerDescriptor(desc, key)
        ccc.setEnabled(desc, "WRITER", true)
        ccc.setEnabled(desc, "OTHER", true)

        // Client-driven: the writer's own Control Point write caused it.
        val clientDriven = ccc.subsFor(key) - "WRITER"
        assertTrue(clientDriven.contains("OTHER"))
        assertFalse(clientDriven.contains("WRITER"))

        // User/console-driven: the plain subscriber set still reaches every
        // central, including the writer.
        assertEquals(setOf("WRITER", "OTHER"), ccc.subsFor(key))

        // The exclusion is per-emission, not an unsubscription: the writer
        // is still in the live set afterwards.
        assertTrue(ccc.subsFor(key).contains("WRITER"))
        assertTrue(ccc.hasAny())
    }

    @Test
    fun `client-driven set is empty when the writer is the only subscriber`() {
        val key = "machineStatus"
        val ccc = CccSubscriptions()
        val desc = Any()
        ccc.registerDescriptor(desc, key)
        ccc.setEnabled(desc, "WRITER", true)

        // "the other connected clients, if any" — with no others, the
        // notification simply goes nowhere.
        assertTrue((ccc.subsFor(key) - "WRITER").isEmpty())
    }
}
