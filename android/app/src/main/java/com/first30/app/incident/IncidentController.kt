package com.first30.app.incident

import com.first30.app.signals.MonoClock
import com.first30.app.signals.SignalRingBuffer
import com.first30.app.signals.TriggerEvent
import com.first30.app.signals.TriggerKind
import com.first30.core.engine.EngineClock
import com.first30.core.engine.Judge
import com.first30.core.engine.LocalJudge
import com.first30.core.engine.RunOpts
import com.first30.core.engine.RunOutput
import com.first30.core.engine.run
import com.first30.core.model.Ctx
import com.first30.core.model.Frame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One incident as the UI sees it: the latest engine [output] for these [frames] and [ctx].
 * [startedAtMs] = the controller's UI clock (elapsedRealtime-like) at the trigger — drive the 30 s ring from it.
 */
data class IncidentSession(
    val output: RunOutput,
    val startedAtMs: Long,
    val ctx: Ctx,
    val frames: List<Frame>,
    /** Seal saved to the store: true saved / false save failed / null nothing to save (no seal or no store). */
    val stored: Boolean? = null,
)

/**
 * Incident state holder.
 *
 * - [trigger] / [onTrigger]: snapshot the ring buffer at the trigger instant (t = 0), fix `ctx.now` once for the
 *   incident (ISO-8601 from [wallClock]), build the ctx from [contextProvider], and run the engine off the main
 *   thread on [engineDispatcher]. While an incident is open further triggers are ignored — except that an
 *   'airbag'/'impact' trigger upgrades an open 'voice'/'button'/'decel' incident (new snapshot at the impact
 *   instant, same `now` and startedAtMs, the confirm answer is cleared because the record changed).
 * - [answer]: sets `ctx.laneChangeConfirmed` and re-runs on the same frames + same `now`.
 * - [setStatement]: sets `ctx.driverStatement` and re-runs.
 * - [reset]: closes the incident (session = null).
 *
 * Runs are serialized; only the newest request of the current incident is published. Each run's seal is saved to
 * [sealStore] (if given) on [ioDispatcher]; same frames + same now ⇒ same hash, so re-runs don't multiply files.
 * [judge]: [LocalJudge] for now; later the relay-server JevJudge wrapped in [TimeLimitedJudge] (3 s → local fallback).
 */
class IncidentController(
    private val buffer: SignalRingBuffer,
    private val scope: CoroutineScope,
    private val contextProvider: IncidentContextProvider = IncidentContextProvider.UNKNOWN,
    private val judge: Judge = LocalJudge,
    private val sealStore: SealStore? = null,
    private val uiClock: MonoClock = MonoClock.SYSTEM,
    private val wallClock: EngineClock = EngineClock.SYSTEM,
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runOpts: RunOpts? = null,
) {
    private data class Incident(val id: Long, val frames: List<Frame>, val ctx: Ctx, val startedAtMs: Long, val triggerAtMs: Long)

    private val _session = MutableStateFlow<IncidentSession?>(null)
    val session: StateFlow<IncidentSession?> = _session.asStateFlow()

    private val lock = Any()
    private val runMutex = Mutex()
    private var incident: Incident? = null
    private var nextId = 1L
    private var rev = 0L

    /** Last seal-save failure message (null = none). Storage problems never block the incident flow. */
    @Volatile
    var lastSealError: String? = null
        private set

    /** UI/voice trigger now ('button', 'voice', …): the trigger instant is the buffer clock's now. */
    fun trigger(kind: String) = onTrigger(TriggerEvent(kind, buffer.clock.nowMs()))

    /** Trigger from a vehicle source with its own instant (e.g. the detected shock time for 'impact'). */
    fun onTrigger(event: TriggerEvent) {
        val (inc, r) = synchronized(lock) {
            val cur = incident
            val next = when {
                cur == null -> {
                    val c = contextProvider.current()
                    Incident(
                        id = nextId++,
                        frames = buffer.snapshot(event.atMs),
                        ctx = Ctx(
                            trigger = event.kind,
                            roadType = c.roadType,
                            navCautions = c.navCautions,
                            driverStatement = c.driverStatement,
                            laneChangeConfirmed = null,
                            occupants = c.occupants,
                            injuryReported = c.injuryReported,
                            now = wallClock.isoNow(),
                        ),
                        startedAtMs = uiClock.nowMs(),
                        triggerAtMs = event.atMs,
                    )
                }
                !TriggerKind.knowsImpactTime(cur.ctx.trigger) && TriggerKind.knowsImpactTime(event.kind) -> cur.copy(
                    frames = buffer.snapshot(event.atMs),
                    ctx = cur.ctx.copy(trigger = event.kind, laneChangeConfirmed = null),
                    triggerAtMs = event.atMs,
                )
                else -> return
            }
            incident = next
            next to ++rev
        }
        launchRun(inc, r)
    }

    fun answer(confirmed: Boolean) = updateCtx { it.copy(laneChangeConfirmed = confirmed) }

    fun setStatement(text: String) = updateCtx { it.copy(driverStatement = text) }

    fun reset() {
        synchronized(lock) {
            incident = null
            rev++
            _session.value = null
        }
    }

    private fun updateCtx(f: (Ctx) -> Ctx) {
        val (inc, r) = synchronized(lock) {
            val cur = incident ?: return
            val next = cur.copy(ctx = f(cur.ctx))
            incident = next
            next to ++rev
        }
        launchRun(inc, r)
    }

    private fun launchRun(inc: Incident, r: Long) {
        scope.launch {
            runMutex.withLock {
                if (synchronized(lock) { r != rev }) return@withLock // superseded before it started
                val out = withContext(engineDispatcher) { run(inc.frames, inc.ctx, judge, runOpts, wallClock) }
                // Save before publishing so the screen never says "봉인됨" for a seal that is not on disk.
                val seal = out.seal
                val store = sealStore
                val stored = if (seal != null && store != null) {
                    try {
                        withContext(ioDispatcher) { store.save(seal) }
                        lastSealError = null
                        true
                    } catch (e: Exception) {
                        lastSealError = e.message ?: e.toString()
                        false
                    }
                } else null
                synchronized(lock) {
                    if (r == rev && incident?.id == inc.id) _session.value = IncidentSession(out, inc.startedAtMs, inc.ctx, inc.frames, stored)
                }
            }
        }
    }
}
