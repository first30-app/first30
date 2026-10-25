package com.first30.core.engine

import com.first30.core.JsUndefined

/**
 * JS 값 모델(Map) → 엔진 결과 모델. 저장해 둔 metrics(봉인 본문의 `payload.metrics` 등)를 다시 읽거나,
 * 골든 테스트가 기록된 `m` 인자를 Kotlin 함수에 넘길 때 쓴다. 각 모델의 `toJs()` 의 역함수다.
 */
object JsInput {
    private fun d(m: Map<String, Any?>, k: String): Double? = (m[k] as? Number)?.toDouble()
    private fun s(m: Map<String, Any?>, k: String): String? = m[k] as? String
    private fun b(m: Map<String, Any?>, k: String): Boolean? = m[k] as? Boolean

    @Suppress("UNCHECKED_CAST")
    private fun obj(m: Map<String, Any?>, k: String): Map<String, Any?> = m[k] as Map<String, Any?>

    fun quality(m: Map<String, Any?>): Quality = Quality(
        frames = d(m, "frames")!!.toInt(),
        headingUpdateMs = d(m, "headingUpdateMs"),
        speedMissing = d(m, "speedMissing")!!,
        headingMissing = d(m, "headingMissing")!!,
        steeringAvailable = b(m, "steeringAvailable")!!,
        signalAvailable = b(m, "signalAvailable")!!,
        brakeAvailable = b(m, "brakeAvailable")!!,
        maxGapMs = d(m, "maxGapMs")!!,
    ).also { q ->
        q.hasSpeed = b(m, "hasSpeed") == true
        q.hasHeading = b(m, "hasHeading") == true
        q.steeringSign = s(m, "steeringSign")
        if (m.containsKey("headingLagSec")) q.headingLagSec = d(m, "headingLagSec")
    }

    fun impact(m: Map<String, Any?>): Impact = Impact(s(m, "source")!!, b(m, "known")!!, s(m, "reason"), d(m, "anchoredMs"))

    fun laneChange(m: Map<String, Any?>): LaneChange = LaneChange(
        status = s(m, "status")!!,
        detected = b(m, "detected") == true,
        direction = s(m, "direction"),
        lateralM = d(m, "lateralM"),
        maxHeadingDeg = d(m, "maxHeadingDeg"),
        windowSec = d(m, "windowSec"),
        startT = d(m, "startT"),
        endT = d(m, "endT"),
        completedSecBefore = d(m, "completedSecBefore"),
        steeringCorroborated = b(m, "steeringCorroborated"),
        steeringReturned = b(m, "steeringReturned"),
        maneuverSpeedKph = d(m, "maneuverSpeedKph"),
        reason = s(m, "reason"),
        roadCurveCompensated = b(m, "roadCurveCompensated"),
        steerActivityDeg = d(m, "steerActivityDeg"),
        steerActivityThrDeg = d(m, "steerActivityThrDeg"),
        steerRefSigmaDeg = d(m, "steerRefSigmaDeg"),
        basis = s(m, "basis"),
        riseSec = d(m, "riseSec"),
        steerThresholdDeg = d(m, "steerThresholdDeg"),
        headingHeldSec = d(m, "headingHeldSec"),
        keptLaneAfter = b(m, "keptLaneAfter"),
        oppositeMoveM = d(m, "oppositeMoveM"),
        hasDirectionCaveat = m.containsKey("directionCaveat"),
        directionCaveat = s(m, "directionCaveat"),
    )

    fun signal(m: Map<String, Any?>): SignalFacts = SignalFacts(
        status = s(m, "status")!!,
        requiredM = d(m, "requiredM")!!,
        leadM = d(m, "leadM"),
        leadSec = d(m, "leadSec"),
        marginM = d(m, "marginM"),
        onAtBufferStart = b(m, "onAtBufferStart") == true,
        stateAtImpact = s(m, "stateAtImpact"),
        recentOn = s(m, "recentOn"),
        bridged = b(m, "bridged") == true,
        note = s(m, "note"),
        full = m.containsKey("marginM"),
    )

    fun brake(m: Map<String, Any?>): BrakeFacts = BrakeFacts(
        applied = b(m, "applied"),
        onsetSecBefore = d(m, "onsetSecBefore"),
        releasedSecBefore = d(m, "releasedSecBefore"),
        onAtBufferStart = b(m, "onAtBufferStart") == true,
    )

    /** analyzeWindow 결과(= run 의 metrics) 를 다시 모델로. `road` 키가 없으면 조기 반환 모양. */
    fun windowMetrics(m: Map<String, Any?>): WindowMetrics {
        val early = !m.containsKey("road")
        val road = if (early) null else obj(m, "road").let { r ->
            RoadFacts(d(r, "yawRateDegS")!!, b(r, "curveCompensated")!!, d(r, "headingNoiseDeg")!!, d(r, "refEndSecBefore")!!)
        }
        return WindowMetrics(
            quality = quality(obj(m, "quality")),
            roadType = s(m, "roadType")!!,
            impact = impact(obj(m, "impact")),
            road = road,
            impactSpeedKph = d(m, "impactSpeedKph"),
            recentSpeedKph = d(m, "recentSpeedKph"),
            peakSpeedKph = d(m, "peakSpeedKph"),
            endSpeedKph = d(m, "endSpeedKph"),
            laneChange = laneChange(obj(m, "laneChange")),
            signal = signal(obj(m, "signal")),
            brake = brake(obj(m, "brake")),
            maxDecelKphPerS = d(m, "maxDecelKphPerS"),
            airbagDeployed = b(m, "airbagDeployed") == true,
            stoppedAtImpact = b(m, "stoppedAtImpact"),
            stoppedAtEnd = b(m, "stoppedAtEnd"),
            hazardOn = b(m, "hazardOn") == true,
            bufferSec = d(m, "bufferSec")!!,
            early = early,
        )
    }

    /** renderFacts 결과 `{ vehicle, place, driverStatement, driverConfirmation }` (driverConfirmation 키가 없으면 null). */
    @Suppress("UNCHECKED_CAST")
    fun facts(m: Map<String, Any?>): Facts = Facts(
        vehicle = (m["vehicle"] as List<Any?>).map { it as String },
        place = (m["place"] as List<Any?>).map { it as String },
        driverStatement = m["driverStatement"] as? String ?: "",
        driverConfirmation = m["driverConfirmation"].takeIf { it !== JsUndefined } as String?,
    )
}
