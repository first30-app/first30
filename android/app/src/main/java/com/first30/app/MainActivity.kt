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

    /** 실시간 재생 + "미리 넣기": (시나리오 트리거, 넣을 답). 재생이 그 트리거에 닿아 세션이 열리면 넣고 지운다. */
    var pendingAnswer by mutableStateOf<Pair<String, Boolean>?>(null)

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
        // 시연·화면 확인용: adb shell am start -S -n com.first30.app/.MainActivity --es scenario airbag [--ez preAnswer true]
        // (-S: 앱이 이미 떠 있으면 같은 인텐트로 보고 앞으로만 가져와 extras 를 무시하므로 다시 시작한다)
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
    val answer = sc.ctx.laneChangeConfirmed?.takeIf { ui.preAnswer }
    // 재생 모드에서는 시나리오 트리거가 오기 전에 버튼 신고로 열릴 수 있고, 트리거가 오면 답이 지워진다 → 그 트리거 뒤에 넣는다.
    ui.pendingAnswer = if (ui.replay && answer != null) sc.ctx.trigger?.let { it to answer } else null
    if (!ui.replay && answer != null) vm.answer(answer)
}

@Composable
private fun App(ui: UiViewModel, vm: IncidentViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session
    LocalView.current.keepScreenOn = s != null || ui.replaying
    LaunchedEffect(s != null) { if (s != null) ui.replaying = false }
    LaunchedEffect(s?.ctx?.trigger) {
        val p = ui.pendingAnswer
        if (s != null && p != null && s.ctx.trigger == p.first) { ui.pendingAnswer = null; vm.answer(p.second) }
    }
    if (s == null) {
        if (ui.replaying) BackHandler { vm.reset(); ui.replaying = false; ui.pendingAnswer = null }
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
            ui = s.output.toUi(s.startedAtMs, s.ctx.laneChangeConfirmed, s.stored),
            speaker = ui.speaker,
            onAnswer = vm::answer,
            onReset = vm::reset,
            clock = SystemClock::elapsedRealtime,
            // 이 빌드는 시나리오 재생만 한다(Pleos 신호원이 붙으면 그 신호원으로 시작한 사고만 false).
            demoRecord = true,
        )
    }
}
