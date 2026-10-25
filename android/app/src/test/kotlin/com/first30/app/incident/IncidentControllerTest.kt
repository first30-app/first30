package com.first30.app.incident

import com.first30.app.signals.FakeVehicleSource
import com.first30.app.signals.MonoClock
import com.first30.app.signals.SignalRecorder
import com.first30.app.signals.SignalRingBuffer
import com.first30.app.signals.TriggerEvent
import com.first30.core.engine.EngineClock
import com.first30.core.scenarios.Expect
import com.first30.core.scenarios.Scenario
import com.first30.core.scenarios.Scenarios
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentControllerTest {
    @TempDir
    lateinit var tmp: File

    /** A wall clock that moves 1 s on every read — proves ctx.now is read once per incident. */
    private class TickingWallClock : EngineClock {
        val reads = AtomicLong()
        override fun nowMs(): Long = 1_767_225_600_000L + reads.getAndIncrement() * 1000
    }

    private fun scenario(id: String) = Scenarios.ALL.first { it.id == id }

    private fun TestScope.controller(
        s: Scenario,
        wall: EngineClock = TickingWallClock(),
        store: SealStore? = null,
    ): Pair<IncidentController, SignalRingBuffer> {
        val clock = MonoClock.of { testScheduler.currentTime }
        val buffer = SignalRingBuffer(clock)
        val d = StandardTestDispatcher(testScheduler)
        val c = IncidentController(
            buffer = buffer,
            scope = this,
            contextProvider = IncidentContextProvider.fixed(IncidentContext.fromScenario(s)),
            sealStore = store,
            uiClock = clock,
            wallClock = wall,
            engineDispatcher = d,
            ioDispatcher = d,
        )
        return c to buffer
    }

    private fun assertMatches(e: Expect, c: IncidentController) {
        val r = c.session.value!!.output.result
        assertEquals(e.status, r.status)
        e.option?.let { assertEquals(it, r.option) }
        e.likely?.let { assertEquals(it.toDouble(), r.likely) }
        e.low?.let { assertEquals(it.toDouble(), r.low) }
        e.high?.let { assertEquals(it.toDouble(), r.high) }
        e.mode?.let { assertEquals(it, c.session.value!!.output.flow.mode) }
        if (e.confirm == true) assertNotNull(r.confirm)
    }

    @Test
    fun `now is fixed once, answer re-runs on same frames, seal hash stable`() = runTest {
        val s = scenario("confirm_needed")
        val wall = TickingWallClock()
        val store = FileSealStore(File(tmp, "seals"))
        val (c, buffer) = controller(s, wall, store)
        testScheduler.advanceTimeBy(30_000)
        val t0 = testScheduler.currentTime
        buffer.preload(s.frames, t0)
        c.trigger("impact")
        advanceUntilIdle()

        val first = c.session.value!!
        assertEquals(t0, first.startedAtMs)
        assertEquals(s.frames, first.frames)
        assertEquals("abstain", first.output.result.status)
        assertNotNull(first.output.result.confirm)
        assertNull(first.ctx.laneChangeConfirmed)
        val now = first.ctx.now!!
        assertTrue(first.output.flow.cards.any { it.confirm != null })

        c.answer(true)
        advanceUntilIdle()
        val second = c.session.value!!
        assertEquals(true, second.ctx.laneChangeConfirmed)
        assertEquals(now, second.ctx.now)
        assertTrue(second.frames === first.frames)
        assertMatches(s.expect.confirmed!!, c)
        assertEquals(first.startedAtMs, second.startedAtMs)

        c.answer(true)
        advanceUntilIdle()
        val third = c.session.value!!
        assertEquals(now, third.ctx.now)
        assertEquals(second.output.seal!!.hash, third.output.seal!!.hash)
        // the seal does not depend on the answer: same record + same now → same fingerprint for the whole incident
        assertEquals(first.output.seal!!.hash, third.output.seal!!.hash)
        assertEquals(listOf(third.output.seal!!.hash), store.list())
        assertTrue(store.verify(third.output.seal!!.hash))

        c.setStatement("왼쪽 차로로 들어가다가 부딪혔어")
        advanceUntilIdle()
        val fourth = c.session.value!!
        assertEquals("왼쪽 차로로 들어가다가 부딪혔어", fourth.ctx.driverStatement)
        assertEquals(true, fourth.ctx.laneChangeConfirmed)
        assertEquals(now, fourth.ctx.now)
        assertEquals(first.output.seal!!.hash, fourth.output.seal!!.hash)

        c.reset()
        assertNull(c.session.value)
        c.answer(false) // no incident → ignored
        advanceUntilIdle()
        assertNull(c.session.value)

        // a new incident gets a new now
        c.trigger("impact")
        advanceUntilIdle()
        assertNotEquals(now, c.session.value!!.ctx.now)
    }

    @Test
    fun `second trigger is ignored, impact upgrades a voice incident`() = runTest {
        val s = scenario("me_late_signal")
        val (c, buffer) = controller(s)
        testScheduler.advanceTimeBy(20_000)
        buffer.preload(s.frames, 20_000)
        c.onTrigger(TriggerEvent("voice", 20_000))
        advanceUntilIdle()
        val v = c.session.value!!
        assertEquals("voice", v.ctx.trigger)
        assertEquals("abstain", v.output.result.status)

        c.onTrigger(TriggerEvent("button", 20_000))
        advanceUntilIdle()
        assertEquals("voice", c.session.value!!.ctx.trigger)

        c.onTrigger(TriggerEvent("impact", 20_000))
        advanceUntilIdle()
        val up = c.session.value!!
        assertEquals("impact", up.ctx.trigger)
        assertEquals(v.ctx.now, up.ctx.now)
        assertEquals(v.startedAtMs, up.startedAtMs)
        assertNotNull(up.output.result.confirm) // movement recorded, answer not given yet
        c.answer(true)
        advanceUntilIdle()
        assertMatches(s.expect, c)
    }

    /** The whole in-car path per scenario: fake source → live buffer → trigger → controller → (answer) → engine. */
    @TestFactory
    fun `end-to-end pipeline reproduces every scenario`(): List<DynamicTest> = Scenarios.ALL.map { s ->
        DynamicTest.dynamicTest(s.id) {
            runTest {
                val (c, buffer) = controller(s)
                val clock = buffer.clock
                backgroundScope.launch { buffer.runSampler() }
                val src = FakeVehicleSource.forScenario(s.id, this, clock)
                src.start(SignalRecorder(buffer, c::onTrigger))
                advanceUntilIdle()
                assertEquals(s.frames, c.session.value!!.frames)
                val answer = s.ctx.laneChangeConfirmed
                if (answer != null) {
                    c.answer(answer)
                    advanceUntilIdle()
                    assertMatches(s.expect, c)
                } else {
                    assertMatches(s.expect, c)
                    s.expect.confirmed?.let { e ->
                        c.answer(true)
                        advanceUntilIdle()
                        assertMatches(e, c)
                    }
                }
            }
        }
    }
}
