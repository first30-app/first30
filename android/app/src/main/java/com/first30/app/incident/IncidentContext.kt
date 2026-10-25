package com.first30.app.incident

import com.first30.core.scenarios.Scenario

/**
 * Non-frame inputs of the engine `ctx`, read once when an incident is triggered.
 * Kotlin null = unknown/absent (the engine treats roadType null as 'unknown' → cautious mode).
 * Later sources: NaviHelper (roadType 'urban'|'highway', navCautions), `registerSeatOccupancy` (occupants = occupied
 * seats other than the driver), SpeechToText (driverStatement), the injury question (injuryReported).
 */
data class IncidentContext(
    val roadType: String? = null,
    val navCautions: List<String>? = null,
    val occupants: Int? = null,
    val injuryReported: Boolean? = null,
    val driverStatement: String? = null,
) {
    companion object {
        /** The scenario's context minus its confirm answer (the driver gives that through [IncidentController.answer]). */
        fun fromScenario(s: Scenario) = IncidentContext(
            roadType = s.ctx.roadType,
            navCautions = s.ctx.navCautions,
            occupants = s.ctx.occupants,
            injuryReported = s.ctx.injuryReported,
            driverStatement = s.ctx.driverStatement,
        )
    }
}

/** Supplies [IncidentContext] at trigger time. Must be cheap and non-blocking. */
fun interface IncidentContextProvider {
    fun current(): IncidentContext

    companion object {
        /** Fake default until Navi/seat/STT are wired: everything unknown. */
        val UNKNOWN = IncidentContextProvider { IncidentContext() }

        fun fixed(c: IncidentContext) = IncidentContextProvider { c }
    }
}
