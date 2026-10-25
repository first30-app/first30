package com.first30.app.incident

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.first30.app.signals.FakeVehicleSource
import com.first30.app.signals.MonoClock
import com.first30.app.signals.SignalRecorder
import com.first30.app.signals.SignalRingBuffer
import com.first30.app.signals.TriggerEvent
import com.first30.app.signals.TriggerKind
import com.first30.app.signals.VehicleSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * App-level wiring of the signal/incident layer (demo build: no Pleos source yet).
 * Owns a 10 Hz [SignalRingBuffer] on `SystemClock.elapsedRealtime`, an [IncidentController] using the local judge,
 * and seals in `filesDir/seals`. Use from Compose with `viewModel<IncidentViewModel>()`.
 */
class IncidentViewModel(app: Application) : AndroidViewModel(app) {
    private val elapsed = MonoClock.of { SystemClock.elapsedRealtime() }

    @Volatile
    private var context: IncidentContext = IncidentContext()

    val buffer = SignalRingBuffer(clock = elapsed)
    val sealStore: SealStore = FileSealStore(File(app.filesDir, "seals"))
    val controller = IncidentController(
        buffer = buffer,
        scope = viewModelScope,
        contextProvider = { context },
        sealStore = sealStore,
        uiClock = elapsed,
    )

    /** Same as [IncidentController.session]. */
    val session: StateFlow<IncidentSession?> get() = controller.session

    private val sampler: Job = viewModelScope.launch(Dispatchers.Default) { buffer.runSampler() }
    private var source: VehicleSource? = null

    /**
     * Demo: load scenario [id] (core Scenarios.ALL) and trigger with the scenario's trigger kind.
     * [realTime] = false → the scenario's record is placed into the buffer at once and triggered immediately;
     * true → it is replayed live through [FakeVehicleSource] (≈10 s of "driving", then the trigger fires).
     * The scenario's road type, nav cautions and statement become the context; the confirm answer is left to the driver.
     */
    fun startScenario(id: String, realTime: Boolean = false) {
        val s = FakeVehicleSource.scenario(id)
        stopSource()
        controller.reset()
        context = IncidentContext.fromScenario(s)
        val kind = s.ctx.trigger ?: TriggerKind.BUTTON
        if (realTime) {
            buffer.clear()
            val src = FakeVehicleSource(s.frames, kind, viewModelScope, elapsed)
            source = src
            src.start(SignalRecorder(buffer, controller::onTrigger))
        } else {
            val now = elapsed.nowMs()
            buffer.preload(s.frames, now)
            controller.onTrigger(TriggerEvent(kind, now))
        }
    }

    /** Sets the context used by the next trigger (e.g. a manual demo without a scenario). */
    fun setContext(c: IncidentContext) { context = c }

    fun trigger(kind: String) = controller.trigger(kind)
    fun answer(confirmed: Boolean) = controller.answer(confirmed)
    fun setStatement(text: String) = controller.setStatement(text)
    fun reset() {
        stopSource()
        controller.reset()
    }

    private fun stopSource() {
        source?.stop()
        source = null
    }

    override fun onCleared() {
        stopSource()
        sampler.cancel()
    }
}
