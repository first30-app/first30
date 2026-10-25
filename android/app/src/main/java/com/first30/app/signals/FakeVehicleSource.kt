package com.first30.app.signals

import com.first30.core.model.Frame
import com.first30.core.model.JsScalar
import com.first30.core.scenarios.Scenario
import com.first30.core.scenarios.Scenarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Replays a frame list (e.g. a core scenario) as live vehicle channels, then fires the trigger at t = 0 —
 * so the whole in-car path (source → [SignalRingBuffer] → snapshot → engine) is exercised.
 *
 * Timing (all on [clock], which must be the ring buffer's clock): the trigger instant T0 is the first multiple of
 * [alignMs] that leaves room for the whole replay; frame k's channel values are emitted at T0 + t_k − [leadMs].
 * With the buffer ticking on the same 100 ms grid this lands every frame in exactly one sample, so the snapshot
 * reproduces 10 Hz input frames exactly. For accelerated replay give both a [ScaledClock].
 *
 * [headingPeriodMs] (e.g. 1000) thins heading updates to mimic a ~1 Hz FusedLocation feed.
 * Frame values are mapped as: finite number → Double, string → String, boolean → Boolean, anything else → null
 * (so exotic test frames, e.g. a numeric turn-signal enum or NaN, are not reproduced bit-for-bit).
 */
class FakeVehicleSource(
    frames: List<Frame>,
    private val triggerKind: String?,
    private val scope: CoroutineScope,
    private val clock: MonoClock,
    private val headingPeriodMs: Long? = null,
    private val leadMs: Long = 50,
    private val alignMs: Long = 100,
) : VehicleSource {
    private val rows = timed(frames)
    private var job: Job? = null

    /** Trigger instant (clock ms) of the current/last replay, known once the replay starts. */
    @Volatile
    var triggerAtMs: Long? = null
        private set

    override fun start(sink: VehicleSink) {
        stop()
        job = scope.launch { replay(sink) }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** Runs one replay in the caller's coroutine and returns after the trigger has been delivered. */
    suspend fun replay(sink: VehicleSink) {
        if (rows.isEmpty()) return
        val firstT = rows.first().first
        val now = clock.nowMs()
        val t0 = alignUp(now - minOf(firstT, 0L) + leadMs)
        triggerAtMs = t0
        var lastHeadingT: Long? = null
        for ((t, f) in rows) {
            val at = t0 + t - leadMs
            clock.awaitUntil(at)
            for (u in updatesOf(f, at)) {
                if (u is SignalUpdate.Heading && headingPeriodMs != null) {
                    val lh = lastHeadingT
                    if (lh != null && t - lh < headingPeriodMs) continue
                    lastHeadingT = t
                }
                sink.onSignal(u)
            }
        }
        if (triggerKind != null) {
            clock.awaitUntil(t0)
            sink.onTrigger(TriggerEvent(triggerKind, t0))
        }
    }

    private fun alignUp(ms: Long): Long {
        val r = Math.floorMod(ms, alignMs)
        return if (r == 0L) ms else ms + (alignMs - r)
    }

    companion object {
        /** A scenario by id from [Scenarios.ALL] (throws if unknown). */
        fun scenario(id: String): Scenario = Scenarios.ALL.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown scenario: $id (have ${Scenarios.ALL.joinToString { it.id }})")

        /** Replays [Scenarios.ALL] entry [id] with its own trigger kind (ctx.trigger, else 'button'). */
        fun forScenario(id: String, scope: CoroutineScope, clock: MonoClock, headingPeriodMs: Long? = null): FakeVehicleSource {
            val s = scenario(id)
            return FakeVehicleSource(s.frames, s.ctx.trigger ?: TriggerKind.BUTTON, scope, clock, headingPeriodMs)
        }

        /** Frames with a finite t ≤ 0 as (t ms, frame), sorted by t (stable). */
        internal fun timed(frames: List<Frame>): List<Pair<Long, Frame>> =
            frames.mapNotNull { f -> f.t.finiteOrNull()?.takeIf { it <= 0 }?.let { Math.round(it) to f } }.sortedBy { it.first }

        private fun num(x: JsScalar): Double? = x.finiteOrNull()

        /** The channel updates a frame stands for, all stamped [atMs]. Undefined fields are skipped (never sent); null → unavailable. */
        internal fun updatesOf(f: Frame, atMs: Long): List<SignalUpdate> = buildList {
            if (!f.speedKph.isUndefined) add(SignalUpdate.Speed(num(f.speedKph), atMs))
            if (!f.headingDeg.isUndefined) add(SignalUpdate.Heading(num(f.headingDeg), atMs))
            if (!f.steeringDeg.isUndefined) add(SignalUpdate.Steering(num(f.steeringDeg), atMs))
            if (!f.turnSignal.isUndefined) add(SignalUpdate.TurnSignal(f.turnSignal.stringOrNull(), atMs))
            if (!f.brake.isUndefined) add(SignalUpdate.Brake(f.brake.booleanOrNull(), atMs))
            if (!f.airbag.isUndefined) add(SignalUpdate.Airbag(f.airbag.booleanOrNull(), atMs))
        }
    }
}
