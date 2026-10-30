package com.first30.app.signals

import com.first30.core.model.Frame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 10 Hz sampler keeping the most recent [windowMs] (default 20 s = 200 frames) of vehicle signals
 * (CLAUDE.md "다음 작업" item 3: 고속 느린 진로변경까지 가리려면 14초 이상 — the engine uses the whole buffer it gets).
 *
 * - [update] stores the newest value of each channel (hold-last). Updates older than the stored one are ignored.
 * - [tick] snapshots the latest value of every channel into one sample. A channel never received (or reported
 *   unavailable) is sampled as **null** → JS `null` in the [Frame], never 0/false/'NONE' (the engine and seal v4
 *   rely on missing = null). Ticks before any channel was ever received are not recorded.
 * - [snapshot] returns the samples up to the trigger instant, re-timed so t = 0 is the trigger (ms, negative before),
 *   in time order. If the last sample is earlier than the trigger, one extra sample at exactly t = 0 is appended
 *   with the latest values received up to the trigger (so the impact instant always has a frame; values that arrived
 *   after a late-reported trigger are not used).
 *
 * Thread-safe. Time comes from [clock]; [runSampler] drives [tick] on the [periodMs] grid (absolute multiples of
 * [periodMs] on [clock]), or tests call [tick] directly.
 */
class SignalRingBuffer(
    val clock: MonoClock = MonoClock.SYSTEM,
    val periodMs: Long = 100,
    val windowMs: Long = 20_000,
) {
    init { require(periodMs > 0 && windowMs >= periodMs) }

    /** Max samples kept (200 at the defaults). */
    val capacity: Int = (windowMs / periodMs).toInt()

    private class Ch<T> {
        var value: T? = null
        var atMs: Long = Long.MIN_VALUE
        var received = false

        fun set(v: T?, at: Long) {
            if (received && at < atMs) return
            value = v; atMs = at; received = true
        }

        fun clear() { value = null; atMs = Long.MIN_VALUE; received = false }
    }

    private data class Sample(
        val atMs: Long,
        val speedKph: Double?,
        val headingDeg: Double?,
        val steeringDeg: Double?,
        val turnSignal: String?,
        val brake: Boolean?,
        val airbag: Boolean?,
    )

    private val lock = Any()
    private val speed = Ch<Double>()
    private val heading = Ch<Double>()
    private val steering = Ch<Double>()
    private val turn = Ch<String>()
    private val brake = Ch<Boolean>()
    private val airbag = Ch<Boolean>()
    private val samples = ArrayDeque<Sample>(capacity + 1)

    fun update(u: SignalUpdate) {
        synchronized(lock) {
            when (u) {
                is SignalUpdate.Speed -> speed.set(u.kph, u.atMs)
                is SignalUpdate.Heading -> heading.set(u.deg, u.atMs)
                is SignalUpdate.Steering -> steering.set(u.deg, u.atMs)
                is SignalUpdate.TurnSignal -> turn.set(u.state, u.atMs)
                is SignalUpdate.Brake -> brake.set(u.pressed, u.atMs)
                is SignalUpdate.Airbag -> airbag.set(u.deployed, u.atMs)
            }
        }
    }

    private fun anyReceived() = speed.received || heading.received || steering.received || turn.received || brake.received || airbag.received

    private fun current(atMs: Long) = Sample(atMs, speed.value, heading.value, steering.value, turn.value, brake.value, airbag.value)

    /**
     * The t = 0 frame when no tick landed exactly on the trigger. A trigger can arrive late (an 'impact' callback stamped
     * with the earlier shock instant), so a channel updated **after** [triggerAtMs] must not leak its post-impact value
     * into the impact frame: it keeps its value from the last sample before the trigger ([prev]), or null if none.
     */
    private fun atTrigger(triggerAtMs: Long, prev: Sample?): Sample {
        fun <T> Ch<T>.at(fromPrev: Sample.() -> T?): T? = if (atMs <= triggerAtMs) value else prev?.fromPrev()
        return Sample(
            triggerAtMs,
            speed.at { speedKph }, heading.at { headingDeg }, steering.at { steeringDeg },
            turn.at { turnSignal }, brake.at { brake }, airbag.at { airbag },
        )
    }

    /** Records one sample at [atMs] (default: now). Ignored if not after the previous sample, or if nothing was ever received. */
    fun tick(atMs: Long = clock.nowMs()) {
        synchronized(lock) {
            if (!anyReceived()) return
            val last = samples.lastOrNull()
            if (last != null && atMs <= last.atMs) return
            samples.addLast(current(atMs))
            while (samples.size > capacity) samples.removeFirst()
        }
    }

    /** Frames for the incident triggered at [triggerAtMs]: samples in (trigger − window, trigger], t = sampleTime − trigger. */
    fun snapshot(triggerAtMs: Long = clock.nowMs()): List<Frame> = synchronized(lock) {
        val from = triggerAtMs - windowMs
        val rows = samples.filter { it.atMs in (from + 1)..triggerAtMs }.toMutableList()
        if (anyReceived() && (rows.isEmpty() || rows.last().atMs < triggerAtMs)) rows.add(atTrigger(triggerAtMs, rows.lastOrNull()))
        rows.map { s ->
            Frame.of(
                t = s.atMs - triggerAtMs,
                speedKph = s.speedKph,
                headingDeg = s.headingDeg,
                steeringDeg = s.steeringDeg,
                turnSignal = s.turnSignal,
                brake = s.brake,
                airbag = s.airbag,
            )
        }
    }

    /** Number of samples currently held. */
    val size: Int get() = synchronized(lock) { samples.size }

    /** Drops all samples and forgets every channel (they become "never received" again). */
    fun clear() {
        synchronized(lock) {
            samples.clear()
            listOf(speed, heading, steering, turn, brake, airbag).forEach { it.clear() }
        }
    }

    /**
     * Replaces the buffer content with [frames] (t ≤ 0, ms) placed so that t = 0 lands on [endAtMs], and leaves the
     * channels holding the last frame's values. For demos (instant scenario load) — the live path is [update] + [tick].
     * Frame values are read like [FakeVehicleSource] does (non-finite/other-typed → null).
     */
    fun preload(frames: List<Frame>, endAtMs: Long) {
        synchronized(lock) {
            clear()
            for ((t, f) in FakeVehicleSource.timed(frames)) {
                val at = endAtMs + t
                FakeVehicleSource.updatesOf(f, at).forEach { u ->
                    when (u) {
                        is SignalUpdate.Speed -> speed.set(u.kph, at)
                        is SignalUpdate.Heading -> heading.set(u.deg, at)
                        is SignalUpdate.Steering -> steering.set(u.deg, at)
                        is SignalUpdate.TurnSignal -> turn.set(u.state, at)
                        is SignalUpdate.Brake -> brake.set(u.pressed, at)
                        is SignalUpdate.Airbag -> airbag.set(u.deployed, at)
                    }
                }
                val last = samples.lastOrNull()
                if (last == null || at > last.atMs) samples.addLast(current(at))
                while (samples.size > capacity) samples.removeFirst()
            }
        }
    }

    /**
     * Sampling loop: ticks on the [periodMs] grid of [clock] until cancelled. Missed slots (the thread was late by
     * more than a period) are skipped, never back-filled. Run it on a background dispatcher.
     */
    suspend fun runSampler() {
        var next = alignUp(clock.nowMs())
        while (true) {
            currentCoroutineContext().ensureActive()
            clock.awaitUntil(next)
            val now = clock.nowMs()
            if (now - next >= periodMs) next = now - Math.floorMod(now, periodMs)
            tick(next)
            next += periodMs
        }
    }

    private fun alignUp(ms: Long): Long {
        val r = Math.floorMod(ms, periodMs)
        return if (r == 0L) ms else ms + (periodMs - r)
    }
}
