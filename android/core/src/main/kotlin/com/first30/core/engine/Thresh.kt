package com.first30.core.engine

/** engine.js 25~73행 `THRESH` (값·이름 그대로). 바꾸려면 JS 와 DESIGN.md 3장을 함께 고칠 것. */
object Thresh {
    const val YES = 0.8
    const val MAYBE = 0.4
    const val CHART_MIN = 0.6
    const val EVIDENCE_MIN = 0.4
    const val ALT_MIN = 0.25
    const val SELF_LC = 0.6

    const val LANE_KEEP_LAT_M = 0.5
    const val LANE_KEEP_DEG = 1.0
    const val LOCAL_SEC = 8.0
    const val LANE_LAT_M = 0.9
    const val LANE_LAT_MAX_M = 4.5
    const val LANE_DEG = 1.0
    const val STEER_DEV_DEG = 2.0
    const val STEER_RUN_MS = 500.0
    const val STEER_START_DEG = 0.5
    const val STEER_RETURN_RATIO = 0.5
    const val HOLD_MIN_MS = 600.0
    const val HOLD_RATIO = 0.25
    const val EVASIVE_SEC = 2.5
    const val MIN_BUFFER_SEC = 8.0
    const val FAST_KPH = 80.0
    const val MIN_BUFFER_FAST_SEC = 14.0
    const val GUARD_SEC = 6.0
    const val GUARD_SEC_FAST = 9.0
    const val MIN_SPEED_KPH = 8.0
    const val ACTIVE_SEC = 2.0
    const val STARTING_SEC = 1.2
    const val REF_SEC = 4.0
    const val REF_STEER_SPREAD_DEG = 10.0
    const val CURVE_MIN_SEC = 6.0
    const val REF_NOISE_DEG = 1.0
    const val REF_TRIM_DEG = 0.8
    const val YAW_MIN_DEG_S = 0.3
    const val MISSING_MAX = 0.1
    const val SIGNAL_GAP_MS = 1000.0
    const val SIGNAL_MARGIN_SEC = 0.3
    const val SIGNAL_MARGIN_MIN_M = 3.0
    const val RECENT_SIGNAL_SEC = 3.0
    const val LAG_MAX_SEC = 1.3
    const val AIRBAG_SYNC_MS = 500.0
    const val STEER_ACT_DEG = 1.0
    const val STEER_ACT_CURVE_DEG = 0.8
    const val STEER_ACT_MS = 500.0
    const val PHOTO_MAX_KPH = 65.0
    const val STILL_KPH = 10.0
    const val AIRBAG_MAX_SHIFT_MS = 15000.0

    /** engine.js 75행 `IMPACT_TRIGGERS`. */
    val IMPACT_TRIGGERS: List<String> = listOf("airbag", "impact")
}
