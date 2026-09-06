package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [TargetAnnouncer] — the
 * console-driven target-change announcer behind ICS 4/22 (FTM Status –
 * Target Incline Changed) and ICS 4/23 (FTM Status – Target Resistance
 * Level Changed), which FTMS §4.17.1 requires the Server to notify
 * whenever the value is changed. Plain JUnit 4, no Android types.
 */
class TargetAnnouncerTest {

    // Mirrors FtmsServer's ACTIVE_MODES (private there): the WorkoutMode
    // states in which the console reports a real resistance level.
    private val activeModes = setOf(2, 3, 10, 11, 13)

    @Test
    fun `the first sample sets the baselines without announcing`() {
        val a = TargetAnnouncer()
        assertTrue(a.announcedGradeTenths == null)
        assertTrue(a.announcedResTenths == null)
        assertTrue(a.onSample(2, 50, 30, activeModes).isEmpty())
        assertEquals(50, a.announcedGradeTenths)
        assertEquals(30, a.announcedResTenths)
    }

    @Test
    fun `a changed grade is announced as 0x06 and re-baselines`() {
        val a = TargetAnnouncer()
        a.onSample(2, 50, null, activeModes)
        assertEquals(listOf(0x06 to 60), a.onSample(2, 60, null, activeModes))
    }

    @Test
    fun `a second identical sample announces nothing`() {
        val a = TargetAnnouncer()
        a.onSample(2, 50, null, activeModes)
        a.onSample(2, 60, null, activeModes)
        assertTrue(a.onSample(2, 60, null, activeModes).isEmpty())
    }

    @Test
    fun `a changed resistance in an active mode is announced as 0x07`() {
        val a = TargetAnnouncer()
        a.onSample(2, null, 30, activeModes)
        assertEquals(listOf(0x07 to 40), a.onSample(2, null, 40, activeModes))
    }

    @Test
    fun `a resistance sample outside the active modes is never announced`() {
        val a = TargetAnnouncer()
        a.onSample(2, null, 30, activeModes)
        // Mode 1 = Idle: the caller passes null (the parked raw is not a
        // target) — and even a non-null raw must be ignored, not announced.
        assertTrue(a.onSample(1, null, null, activeModes).isEmpty())
        assertTrue(a.onSample(1, null, 999, activeModes).isEmpty())
        assertEquals(30, a.announcedResTenths)
    }

    @Test
    fun `a client-pinned grade is not re-announced when the console echoes it`() {
        val a = TargetAnnouncer()
        a.clientAnnouncedGrade(50)
        assertTrue(a.onSample(2, 50, null, activeModes).isEmpty())
    }

    @Test
    fun `a sample diverging from a client-pinned grade is announced`() {
        val a = TargetAnnouncer()
        a.clientAnnouncedGrade(50)
        assertEquals(listOf(0x06 to 51), a.onSample(2, 51, null, activeModes))
    }

    @Test
    fun `a Reset pin of 0 suppresses the console echo and a later divergence announces`() {
        val a = TargetAnnouncer()
        a.onSample(2, 50, null, activeModes)   // earlier announced baseline
        a.clientAnnouncedGrade(0)               // Reset pinned the grade to 0
        assertTrue(a.onSample(2, 0, null, activeModes).isEmpty())   // console echoes our write
        // The console did not actually drop to 0 — the true value is
        // announced as a console-driven change (a truthful correction).
        assertEquals(listOf(0x06 to 15), a.onSample(2, 15, null, activeModes))
    }

    @Test
    fun `a non-active-mode null sample does not clear the resistance baseline`() {
        val a = TargetAnnouncer()
        a.onSample(2, null, 30, activeModes)
        a.onSample(1, null, null, activeModes)  // Idle: null — baseline must survive
        assertEquals(30, a.announcedResTenths)
        // Back in an active mode with the same value → still silent (the
        // baseline was NOT cleared by the Idle sample).
        assertTrue(a.onSample(2, null, 30, activeModes).isEmpty())
    }

    @Test
    fun `when both targets change in one sample 0x06 precedes 0x07`() {
        val a = TargetAnnouncer()
        a.onSample(2, 50, 30, activeModes)
        assertEquals(listOf(0x06 to 55, 0x07 to 35), a.onSample(2, 55, 35, activeModes))
    }
}
