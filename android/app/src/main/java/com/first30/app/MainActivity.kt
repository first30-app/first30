package com.first30.app

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.first30.app.incident.IncidentViewModel
import com.first30.app.speech.AndroidSpeaker
import com.first30.app.speech.Speaker
import com.first30.app.ui.First30Theme
import com.first30.app.ui.IncidentScreen
import com.first30.app.ui.StartScreen
import com.first30.app.ui.toUi
import com.first30.core.scenarios.Scenarios

/** 화면 쪽 상태(고른 시나리오·데모 옵션)와 TTS. 화면 회전에도 유지된다. 사고 세션은 [IncidentViewModel] 이 가진다. */
class UiViewModel(app: Application) : AndroidViewModel(app) {
    val speaker: Speaker = AndroidSpeaker(app)
    var selectedId by mutableStateOf(Scenarios.ALL.first().id)
    var preAnswer by mutableStateOf(false)
    var replay by mutableStateOf(false)

    /** 실시간 재생을 시작했고 아직 세션이 없는 동안 true. */
    var replaying by mutableStateOf(false)

    override fun onCleared() {
        speaker.shutdown()
    }
}

class MainActivity : ComponentActivity() {
    private val ui: UiViewModel by viewModels()
    private val incident: IncidentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 시연·화면 확인용: adb shell am start -n com.first30.app/.MainActivity --es scenario airbag [--ez preAnswer true]
        if (savedInstanceState == null) {
            intent?.getStringExtra("scenario")?.let { id ->
                if (Scenarios.ALL.any { it.id == id }) {
                    ui.selectedId = id
                    ui.preAnswer = intent.getBooleanExtra("preAnswer", false)
                    start(ui, incident)
                }
            }
        }
        setContent { First30Theme { App(ui, incident) } }
    }
}

/**
 * 데모 시작: 고른 시나리오의 기록으로 사고를 일으킨다(트리거 종류는 시나리오 것 — 충격 감지·에어백·음성).
 * "미리 넣기"면 시나리오의 확인 답을 바로 넣는다(컨트롤러는 늘 답 없이 시작한다).
 */
private fun start(ui: UiViewModel, vm: IncidentViewModel) {
    val sc = Scenarios.byId(ui.selectedId)
    vm.startScenario(sc.id, realTime = ui.replay)
    ui.replaying = ui.replay
    if (!ui.replay && ui.preAnswer) sc.ctx.laneChangeConfirmed?.let(vm::answer)
}

@Composable
private fun App(ui: UiViewModel, vm: IncidentViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session
    LocalView.current.keepScreenOn = s != null || ui.replaying
    LaunchedEffect(s != null) { if (s != null) ui.replaying = false }
    if (s == null) {
        if (ui.replaying) BackHandler { vm.reset(); ui.replaying = false }
        StartScreen(
            scenarios = Scenarios.ALL,
            selectedId = ui.selectedId,
            preAnswer = ui.preAnswer,
            onPickScenario = { if (!ui.replaying) ui.selectedId = it },
            onPreAnswerChange = { ui.preAnswer = it },
            onTrigger = { start(ui, vm) },
            replay = ui.replay,
            onReplayChange = { ui.replay = it },
            replaying = ui.replaying,
            onButtonReport = { vm.trigger("button") },
        )
    } else {
        BackHandler { vm.reset() }
        IncidentScreen(
            ui = s.output.toUi(s.startedAtMs, s.ctx.laneChangeConfirmed),
            speaker = ui.speaker,
            onAnswer = vm::answer,
            onReset = vm::reset,
            clock = SystemClock::elapsedRealtime,
        )
    }
}
