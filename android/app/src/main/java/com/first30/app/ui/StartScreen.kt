package com.first30.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.first30.core.scenarios.Scenario

private val KoText = TextStyle(lineBreak = LineBreak.Paragraph.copy(strictness = LineBreak.Strictness.Strict, wordBreak = LineBreak.WordBreak.Phrase))

/** 시나리오 한 줄에 붙이는 표시(무엇으로 사고를 감지했는지·도로). */
fun scenarioTags(s: Scenario): String {
    val trig = when (s.ctx.trigger) {
        "airbag" -> "에어백 전개"
        "impact" -> "충격 감지"
        "voice" -> "음성 신고"
        "button" -> "버튼 신고"
        "decel" -> "급감속"
        else -> "트리거 모름"
    }
    val road = when (s.ctx.roadType) {
        "highway" -> "고속도로"
        "urban" -> "일반도로"
        else -> "도로 모름"
    }
    return "$trig · $road"
}

/**
 * 데모용 시작 화면: 시나리오(합성 차량 기록) 12종 중 하나를 고르고 "사고 났어"로 30초 안내를 시작한다.
 * Pleos SDK 가 연결되면 실제 트리거(에어백·충격 감지·음성)가 이 버튼을 대신한다.
 */
@Composable
fun StartScreen(
    scenarios: List<Scenario>,
    selectedId: String,
    preAnswer: Boolean,
    onPickScenario: (String) -> Unit,
    onPreAnswerChange: (Boolean) -> Unit,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    replay: Boolean = false,
    onReplayChange: (Boolean) -> Unit = {},
    /** 주행 기록 재생 중(아직 사고 감지 전). 이때 "사고 났어"는 [onButtonReport](버튼 신고). */
    replaying: Boolean = false,
    onButtonReport: () -> Unit = {},
) {
    val options: @Composable () -> Unit = {
        if (replaying) ReplayBanner()
        else {
            Toggle("확인 질문 답을 시나리오대로 미리 넣기", "바로 예상 과실까지 보여 줍니다", "실제 앱처럼 화면에서 예/아니요를 묻습니다", preAnswer, onPreAnswerChange)
            Toggle("주행 기록을 실시간으로 재생한 뒤 감지", "약 10초 주행 후 시나리오의 트리거(충격·에어백 등)가 옵니다. 그 전에 누르면 버튼 신고", "기록을 바로 넣고 곧장 시작합니다", replay, onReplayChange)
        }
    }
    val trigger: @Composable () -> Unit = { TriggerButton(if (replaying) onButtonReport else onTrigger, Modifier.fillMaxWidth()) }
    val selected = scenarios.firstOrNull { it.id == selectedId } ?: scenarios.first()
    BoxWithConstraints(modifier.fillMaxSize().background(F30.bg).safeDrawingPadding()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        val big = maxWidth >= 1100.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Brand()
                    ScenarioList(scenarios, selected.id, onPickScenario, Modifier.weight(1f).padding(top = 12.dp))
                }
                // "사고 났어"는 오른쪽 아래에 고정, 나머지는 위에서 스크롤
                Column(Modifier.width(if (big) 420.dp else 320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SelectedSummary(selected)
                        options()
                        Note()
                    }
                    trigger()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Brand()
                ScenarioList(scenarios, selected.id, onPickScenario, Modifier.weight(1f))
                options()
                trigger()
            }
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HazardMark(Modifier.size(width = 34.dp, height = 30.dp))
        Column {
            Text("퍼스트30", color = F30.fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            KText("차로변경 사고 직후 30초 안내 · 시연용 시나리오", color = F30.muted, fontSize = 15.sp, style = KoText)
        }
    }
}

@Composable
private fun HazardMark(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val outer = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, 0f); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(outer, F30.hazard)
        val inner = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, h * 0.36f); lineTo(w * 0.73f, h * 0.82f); lineTo(w * 0.27f, h * 0.82f); close() }
        drawPath(inner, F30.bg)
    }
}

@Composable
private fun ScenarioList(scenarios: List<Scenario>, selectedId: String, onPick: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        itemsIndexed(scenarios, key = { _, s -> s.id }) { i, s ->
            val sel = s.id == selectedId
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (sel) F30.hazard.copy(alpha = 0.10f) else F30.panel, shape)
                    .border(if (sel) 2.dp else 1.dp, if (sel) F30.hazard else F30.line, shape)
                    .selectable(selected = sel, role = Role.RadioButton) { onPick(s.id) }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(22.dp).border(2.dp, if (sel) F30.hazard else F30.dim, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (sel) Box(Modifier.size(11.dp).background(F30.hazard, CircleShape)) }
                Column(Modifier.weight(1f)) {
                    KText(s.title, color = F30.fg, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, style = KoText)
                    KText(
                        "${(i + 1).toString().padStart(2, '0')} · ${scenarioTags(s)}" + (s.ctx.driverStatement?.takeIf { it.isNotEmpty() }?.let { " · “$it”" } ?: ""),
                        color = F30.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedSummary(s: Scenario) {
    Column(
        Modifier.fillMaxWidth().background(F30.panel, RoundedCornerShape(12.dp)).border(1.dp, F30.line, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("고른 장면", color = F30.muted, fontSize = 14.sp)
        KText(s.title, color = F30.fg, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, style = KoText)
        Text(scenarioTags(s), color = F30.hazard, fontSize = 15.sp)
        s.ctx.driverStatement?.takeIf { it.isNotEmpty() }?.let {
            KText("운전자 진술 “$it”", color = F30.body, fontSize = 16.sp, lineHeight = 23.sp, style = KoText)
        }
    }
}

@Composable
private fun ReplayBanner() {
    Row(
        Modifier.fillMaxWidth().border(1.dp, F30.hazard, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = F30.hazard, strokeWidth = 3.dp)
        KText("주행 기록 재생 중 — 곧 차량이 사고를 감지해요. 지금 누르면 버튼 신고로 시작합니다.", color = F30.fg, fontSize = 16.sp, lineHeight = 23.sp, style = KoText)
    }
}

@Composable
private fun Toggle(title: String, subOn: String, subOff: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            KText(title, color = F30.fg, fontSize = 16.sp, style = KoText)
            KText(
                if (checked) subOn else subOff,
                color = F30.dim, fontSize = 13.sp, style = KoText,
            )
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = F30.hazard, checkedThumbColor = F30.hazardInk, uncheckedTrackColor = F30.panel2),
        )
    }
}

@Composable
private fun TriggerButton(onTrigger: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onTrigger,
        modifier = modifier.heightIn(min = 80.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = F30.hazard, contentColor = F30.hazardInk),
    ) { Text("사고 났어", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Note() {
    KText(
        "에어백 전개나 충격 감지로 시작한 장면만 충돌 순간을 알 수 있어 과실 범위까지 계산합니다. 음성·버튼 신고는 기록 봉인과 증거 안내만 합니다.",
        color = F30.dim, fontSize = 14.sp, lineHeight = 21.sp, style = KoText,
    )
}
