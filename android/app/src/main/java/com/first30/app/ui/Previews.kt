package com.first30.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.first30.app.speech.FakeSpeaker
import com.first30.core.engine.EngineClock
import com.first30.core.engine.LocalJudge
import com.first30.core.engine.run
import com.first30.core.scenarios.Scenarios

/** 미리보기: 엔진을 직접 돌린 결과로 화면을 그린다(봉인 시각 고정). */
internal fun previewUi(id: String, answer: Boolean? = null, keepScenarioAnswer: Boolean = false): IncidentUi {
    val sc = Scenarios.byId(id)
    val clock = EngineClock.fixed(1_767_225_600_000L) // 2026-01-01T00:00:00.000Z
    val confirmed = if (keepScenarioAnswer) sc.ctx.laneChangeConfirmed else answer
    val ctx = sc.ctx.copy(now = clock.isoNow(), laneChangeConfirmed = confirmed)
    return run(sc.frames, ctx, LocalJudge, clock = clock).toUi(0L, confirmed)
}

@Preview(name = "head unit · confirm", widthDp = 1280, heightDp = 480)
@Composable
private fun ConfirmWidePreview() = First30Theme {
    IncidentScreen(previewUi("confirm_needed"), FakeSpeaker(), {}, {}, frozenElapsedSec = 12.0)
}

@Preview(name = "head unit · estimate", widthDp = 1280, heightDp = 480)
@Composable
private fun EstimateWidePreview() = First30Theme {
    IncidentScreen(previewUi("me_late_signal", keepScenarioAnswer = true), FakeSpeaker(), {}, {}, frozenElapsedSec = 12.0)
}

@Preview(name = "phone · highway confirm", widthDp = 400, heightDp = 860)
@Composable
private fun HighwayPhonePreview() = First30Theme {
    IncidentScreen(previewUi("highway_late_signal"), FakeSpeaker(ready = false), {}, {}, frozenElapsedSec = 11.0)
}

@Preview(name = "phone · emergency", widthDp = 400, heightDp = 860)
@Composable
private fun EmergencyPhonePreview() = First30Theme {
    IncidentScreen(previewUi("airbag", keepScenarioAnswer = true), FakeSpeaker(), {}, {}, frozenElapsedSec = 2.0)
}

@Preview(name = "start · head unit", widthDp = 1280, heightDp = 480)
@Composable
private fun StartWidePreview() = First30Theme {
    StartScreen(Scenarios.ALL, "confirm_needed", false, {}, {}, {})
}

@Preview(name = "start · phone", widthDp = 400, heightDp = 860)
@Composable
private fun StartPhonePreview() = First30Theme {
    StartScreen(Scenarios.ALL, "confirm_needed", false, {}, {}, {})
}
