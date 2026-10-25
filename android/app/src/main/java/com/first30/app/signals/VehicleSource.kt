package com.first30.app.signals

import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * Monotonic millisecond clock (SystemClock.elapsedRealtime-like). Every timestamp in this package
 * ([SignalUpdate.atMs], [TriggerEvent.atMs], ring-buffer sample times) is on the same [MonoClock].
 *
 * [rate] = clock milliseconds per real (dispatcher) millisecond. 1.0 for a real clock; a [ScaledClock] used for
 * accelerated replay reports its speed-up so that `delay`-based loops wait the right real time.
 */
interface MonoClock {
    fun nowMs(): Long
    val rate: Double get() = 1.0

    companion object {
        /** JVM monotonic clock. On the device prefer `SystemClock::elapsedRealtime` (see IncidentViewModel). */
        val SYSTEM: MonoClock = object : MonoClock {
            override fun nowMs(): Long = System.nanoTime() / 1_000_000
        }

        fun of(nowMs: () -> Long): MonoClock = object : MonoClock {
            override fun nowMs(): Long = nowMs()
        }
    }
}

/** A clock running [rate] times faster than [base] (accelerated replay). Both the source and the buffer must share it. */
class ScaledClock(private val base: MonoClock, override val rate: Double) : MonoClock {
    init { require(rate > 0) }
    private val baseOrigin = base.nowMs()
    override fun nowMs(): Long = baseOrigin + ((base.nowMs() - baseOrigin) * rate).toLong()
}

/** Suspends until [clock] reaches [targetMs] (works with real, scaled and virtual test time). */
suspend fun MonoClock.awaitUntil(targetMs: Long) {
    while (true) {
        val now = nowMs()
        if (now >= targetMs) return
        delay(max(1L, ceil((targetMs - now) / rate).toLong()))
    }
}

/**
 * One raw channel update from the vehicle, stamped on the shared [MonoClock] ([atMs]).
 * A `null` value means the source reports the channel as unavailable right now (sampled as JS null, never as 0/false/'NONE').
 */
sealed class SignalUpdate {
    abstract val atMs: Long

    /** km/h. */
    data class Speed(val kph: Double?, override val atMs: Long) : SignalUpdate()

    /** Degrees clockwise from true north. May arrive at ~1 Hz (FusedLocation); the buffer holds the last value. */
    data class Heading(val deg: Double?, override val atMs: Long) : SignalUpdate()

    /** Steering-wheel angle in degrees, **right (clockwise) = +**. Required by the engine; flip the SDK sign if needed. */
    data class Steering(val deg: Double?, override val atMs: Long) : SignalUpdate()

    /**
     * Turn signal as an engine string: 'LEFT' | 'RIGHT' | 'NONE' | 'HAZARD' (see [com.first30.core.model.Frame]).
     * Convert SDK enums before emitting — a raw number would be treated by the engine as "no record".
     */
    data class TurnSignal(val state: String?, override val atMs: Long) : SignalUpdate()

    /** Brake pedal pressed. */
    data class Brake(val pressed: Boolean?, override val atMs: Long) : SignalUpdate()

    /** Any airbag deployed. */
    data class Airbag(val deployed: Boolean?, override val atMs: Long) : SignalUpdate()
}

/** Trigger kinds (same strings as `Ctx.trigger`). Only [AIRBAG]/[IMPACT] mean "t=0 is the impact" to the engine. */
object TriggerKind {
    const val AIRBAG = "airbag"
    const val IMPACT = "impact"
    const val DECEL = "decel"
    const val VOICE = "voice"
    const val BUTTON = "button"

    fun knowsImpactTime(kind: String?): Boolean = kind?.trim()?.lowercase().let { it == AIRBAG || it == IMPACT }
}

/** An incident trigger at [atMs] (shared [MonoClock]). For 'impact' this should be the detected shock instant, not the callback time. */
data class TriggerEvent(val kind: String, val atMs: Long)

/** Receiver of a [VehicleSource]'s output. Implementations must be thread-safe (SDK callbacks arrive on arbitrary threads). */
interface VehicleSink {
    fun onSignal(update: SignalUpdate)
    fun onTrigger(event: TriggerEvent)
}

/**
 * A source of raw vehicle channels, each at its own rate, plus incident triggers.
 *
 * Implementations: [FakeVehicleSource] (scenario replay) now; a Pleos Vehicle SDK source later. For the Pleos one,
 * see CLAUDE.md "다음 작업" item 3 (API names) and item 4 (triggers):
 * - turn signal `registerTurnSignalState` / `getCurrentTurnSignalState` → [SignalUpdate.TurnSignal] as 'LEFT'/'RIGHT'/'NONE'/'HAZARD'
 *   (check lever state vs. lamp blink; the engine bridges ≤1 s off gaps)
 * - steering `registerSteeringWheelAngle` → [SignalUpdate.Steering], right = +
 * - brake `registerBrakePedalStatus` → [SignalUpdate.Brake]
 * - airbag `registerSeatAirbagDeployed` / `hasSeatAirbagBeenDeployed` → [SignalUpdate.Airbag] and a [TriggerKind.AIRBAG] trigger
 * - seat occupancy `registerSeatOccupancy` → occupants (context provider, not a frame channel)
 * - speed: no Vehicle SDK API — AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED` (`android.car.permission.CAR_SPEED`) or FusedLocation deltas
 * - heading: `registerFusedLocationCallback` (may be ~1 Hz and/or map-matched — verify on the AVD)
 * - impact: accelerometer shock (`SensorManager` `TYPE_ACCELEROMETER`) → [TriggerKind.IMPACT] with the shock instant as [TriggerEvent.atMs]
 * Lifecycle: check…Capabilities → getCurrent… → register…(listener, area) → unregister…(same listener, area) → `Vehicle.release()`.
 */
interface VehicleSource {
    fun start(sink: VehicleSink)
    fun stop()
}

/** Wires a [VehicleSource] into a [SignalRingBuffer] and forwards triggers (typically to `IncidentController.onTrigger`). */
class SignalRecorder(
    private val buffer: SignalRingBuffer,
    private val onTrigger: (TriggerEvent) -> Unit,
) : VehicleSink {
    override fun onSignal(update: SignalUpdate) = buffer.update(update)
    override fun onTrigger(event: TriggerEvent) = onTrigger.invoke(event)
}
