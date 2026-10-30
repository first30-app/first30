package com.first30.app.ui

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.first30.app.speech.Speaker
import com.first30.app.speech.SpeakerState
import kotlinx.coroutines.delay

/** 한국어 줄바꿈: 어절 단위(word-break: keep-all 에 가깝게). */
private val KoText = TextStyle(lineBreak = LineBreak.Paragraph.copy(strictness = LineBreak.Strictness.Strict, wordBreak = LineBreak.WordBreak.Phrase))

/**
 * 차량 화면 (demo/src.html `.screen`): 30초 링·시계·모드/봉인 표시 + 지금 카드 + 단계 + 자막.
 *
 * - 카드는 세션 시작([IncidentUi.startedAtMs])부터 30초 타임라인의 `at` 에 맞춰 넘어간다.
 * - 카드가 바뀌거나 지금 카드의 `tts` 가 바뀌면(확인 답으로 과실 카드가 갱신) [speaker] 로 읽는다. 자막은 음성이 꺼져도 나온다.
 * - 단계를 누르면 그 카드를 보여 주고 읽는다. 타임라인이 다음 카드로 넘어가면 다시 따라간다(30초 뒤엔 누른 카드에 머문다).
 * - 확인 질문 카드는 예/아니요 버튼을 늘 화면에 둔다. 빠른 도로 모드에서도 버튼은 보이고, 음성은 카드 tts 그대로 읽는다.
 * - [demoRecord] 면 상태 표시에 "시연 · 합성 기록" 을 늘 붙인다(과실 숫자가 실제 차량 기록에서 나온 것처럼 보이지 않게).
 * - 질문 카드가 지나간 뒤에도 답이 없으면 뒤 카드(말하기·다음, 30초 뒤 포함)에 작은 예/아니요 막대를 둔다([IncidentUi.questionBarCard]).
 *   막대는 읽지 않고 타임라인도 멈추지 않는다. 막대로 답하면 지금 카드에 머물고 ≈4초 "답을 반영했어요 · 과실 카드 보기" 안내를 띄운다.
 */
@Composable
fun IncidentScreen(
    ui: IncidentUi,
    speaker: Speaker,
    onAnswer: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    /** [IncidentUi.startedAtMs] 와 같은 시계(IncidentController 는 SystemClock.elapsedRealtime). */
    clock: () -> Long = SystemClock::elapsedRealtime,
    /** 미리보기용: 시계 대신 이 경과 초로 고정. */
    frozenElapsedSec: Double? = null,
    /** 미리보기용: 막대로 답한 뒤의 안내를 이 과실 카드 번호로 띄워 둔다. */
    previewAnsweredNote: Int? = null,
    /** 실제 차량 신호가 아니라 시나리오(합성 기록) 재생으로 시작한 사고. */
    demoRecord: Boolean = false,
) {
    var nowMs by remember { mutableLongStateOf(clock()) }
    if (frozenElapsedSec == null) {
        LaunchedEffect(ui.startedAtMs) {
            while (true) {
                nowMs = clock()
                if (nowMs - ui.startedAtMs > IncidentUi.TOTAL_SEC * 1000L + 500) break
                delay(100)
            }
        }
    }
    val elapsedSec = frozenElapsedSec ?: ((nowMs - ui.startedAtMs).coerceAtLeast(0) / 1000.0)
    val timelineIndex = ui.activeIndexAt(elapsedSec)
    // (고른 카드, 고를 때의 타임라인 카드)
    var manual by rememberSaveable(ui.startedAtMs) { mutableStateOf<Pair<Int, Int>?>(null) }
    val shown = manual?.takeIf { it.second == timelineIndex }?.first ?: timelineIndex
    val card = ui.cards[shown.coerceIn(0, ui.cards.lastIndex)]

    if (frozenElapsedSec == null) {
        // 화면 회전(Activity 재생성) 뒤에는 이미 읽은 카드를 처음부터 다시 읽지 않고, 읽던 음성도 끊지 않는다.
        val speakKey = "$shown|${card.tts}"
        var spokenKey by rememberSaveable(ui.startedAtMs) { mutableStateOf<String?>(null) }
        LaunchedEffect(ui.startedAtMs, speakKey) {
            if (spokenKey != speakKey) { spokenKey = speakKey; speaker.speak(card.tts) }
        }
        val activity = LocalContext.current as? Activity
        DisposableEffect(ui.startedAtMs) { onDispose { if (activity?.isChangingConfigurations != true) speaker.stop() } }
    }
    val speakerState by speaker.state.collectAsState()

    val onStep: (Int) -> Unit = { k -> manual = k to timelineIndex }

    // 뒤 카드의 확인 질문 막대(소리 내지 않는다). 누르면 바로 숨기고(두 번 눌림 방지) 같은 답 경로로 보낸다.
    // 답이 다시 비워지면(충격 트리거로 기록이 바뀐 경우) ui.answer 키로 다시 띄운다.
    var barAnswered by rememberSaveable(ui.startedAtMs, ui.answer) { mutableStateOf(false) }
    // 막대로 답한 뒤 잠깐 보이는 안내(누르면 과실 카드). 값 = 과실 카드 번호.
    var answeredNote by rememberSaveable(ui.startedAtMs) { mutableStateOf(previewAnsweredNote) }
    val barCard = ui.questionBarCard(shown, timelineIndex, barAnswered)
    val onBarAnswer: (Boolean) -> Unit = { v ->
        if (!barAnswered && barCard != null) {
            barAnswered = true
            answeredNote = barCard.index
            onAnswer(v)
        }
    }
    if (frozenElapsedSec == null) {
        answeredNote?.let { LaunchedEffect(it) { delay(4_000); answeredNote = null } }
    }
    val onOpenFault: () -> Unit = { answeredNote?.let { k -> manual = k to timelineIndex }; answeredNote = null }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(F30.screen)
            .safeDrawingPadding(),
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        val big = maxWidth >= 1100.dp
        // 낮은 가로 화면(휴대폰 가로 ≈ 400dp): 글자·여백을 줄여 카드와 버튼이 한 화면에 들어오게
        val compact = maxHeight < 520.dp
        val narrow = maxWidth < 600.dp
        val tall = maxHeight >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(if (compact) 10.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                Column(
                    Modifier.width(if (big) 300.dp else if (compact) 200.dp else 236.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    ClockRow(elapsedSec, ringSize = if (compact) 40.dp else 56.dp, compact = compact)
                    StatusChips(ui, speakerState, vertical = true, demoRecord = demoRecord)
                    StepList(ui, shown, onStep, vertical = true, compact = compact)
                    EndButton(onReset, Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CardPanel(ui, card, onAnswer, wide = true, compact = compact, big = big, modifier = Modifier.weight(1f))
                    BarSlot(barCard, answeredNote, onBarAnswer, onOpenFault, stacked = false, showHint = !compact || big)
                    Subtitle(card.tts, compact)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClockRow(elapsedSec, ringSize = 48.dp, modifier = Modifier.weight(1f))
                    EndButton(onReset)
                }
                StatusChips(ui, speakerState, vertical = false, demoRecord = demoRecord)
                CardPanel(ui, card, onAnswer, wide = false, compact = false, big = false, modifier = Modifier.weight(1f))
                BarSlot(barCard, answeredNote, onBarAnswer, onOpenFault, stacked = narrow, showHint = tall)
                StepList(ui, shown, onStep, vertical = false)
                Subtitle(card.tts, false)
            }
        }
    }
}

@Composable
private fun ClockRow(elapsedSec: Double, ringSize: Dp, modifier: Modifier = Modifier, compact: Boolean = false) {
    val s = elapsedSec.coerceIn(0.0, IncidentUi.TOTAL_SEC.toDouble())
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProgressRing((s / IncidentUi.TOTAL_SEC).toFloat(), Modifier.size(ringSize))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "00:" + s.toInt().toString().padStart(2, '0'),
                    color = F30.fg, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                )
                Text(" / 00:30", color = F30.dim, fontSize = if (compact) 13.sp else 16.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 3.dp))
            }
            if (s >= IncidentUi.TOTAL_SEC) KText(if (compact) "안내 끝" else "안내 끝 · 단계를 눌러 다시 보기", color = F30.dim, fontSize = 13.sp, style = KoText)
        }
    }
}

@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(F30.line, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
        drawArc(F30.hazard, -90f, 360f * progress.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(ui: IncidentUi, speakerState: SpeakerState, vertical: Boolean, demoRecord: Boolean = false) {
    val chips = buildList {
        if (demoRecord) add("시연 · 합성 기록" to Tone.WARN)
        add(ui.modeLabel to ui.modeTone)
        add((if (ui.sealed) "기록 봉인됨" else "봉인 실패 · 블랙박스 원본 보관") to (if (ui.sealed) Tone.OK else Tone.CRIT))
        when (speakerState) {
            SpeakerState.UNAVAILABLE -> add("음성 안 됨 · 자막만" to Tone.NEUTRAL)
            SpeakerState.MUTED -> add("음성 끔 · 자막만" to Tone.NEUTRAL)
            else -> Unit
        }
    }
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { (l, t) -> Chip(l, t) } }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { (l, t) -> Chip(l, t) }
        }
    }
}

@Composable
private fun Chip(label: String, tone: Tone) {
    val c = F30.tone(tone)
    Row(
        Modifier
            .border(1.dp, if (tone == Tone.NEUTRAL) F30.line else c, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).background(c, CircleShape))
        KText(label, color = if (tone == Tone.NEUTRAL) F30.muted else F30.fg, fontSize = 15.sp, style = KoText)
    }
}

@Composable
private fun StepList(ui: IncidentUi, shown: Int, onStep: (Int) -> Unit, vertical: Boolean, compact: Boolean = false) {
    @Composable
    fun Step(c: CardUi, modifier: Modifier) {
        val current = c.index == shown
        val shape = RoundedCornerShape(10.dp)
        val m = modifier
            .background(if (current) F30.hazard.copy(alpha = 0.10f) else Color.Transparent, shape)
            .border(1.dp, if (current) F30.hazard else F30.line, shape)
            .clickable { onStep(c.index) }
            .heightIn(min = if (compact) 40.dp else 48.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 6.dp)
        val fg = if (current) F30.fg else F30.dim
        if (vertical) {
            Row(m, verticalAlignment = Alignment.CenterVertically) {
                Text(c.stepLabel, color = fg, fontSize = if (compact) 16.sp else 18.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                Text("${c.atSec}초", color = fg, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(c.stepLabel, color = fg, fontSize = 15.sp, maxLines = 1, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                Text("${c.atSec}초", color = fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
    if (vertical) {
        // 낮은 화면에서 레일이 스크롤될 때 지금 단계가 보이도록
        val requesters = remember(ui.cards.size) { List(ui.cards.size) { BringIntoViewRequester() } }
        LaunchedEffect(shown) { requesters.getOrNull(shown)?.bringIntoView() }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.cards.forEach { Step(it, Modifier.fillMaxWidth().bringIntoViewRequester(requesters[it.index])) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ui.cards.forEach { Step(it, Modifier.weight(1f)) } }
    }
}

@Composable
private fun EndButton(onReset: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onReset,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, F30.line),
        shape = RoundedCornerShape(10.dp),
    ) { Text("안내 끝내기", color = F30.muted, fontSize = 16.sp) }
}

@Composable
private fun CardPanel(ui: IncidentUi, card: CardUi, onAnswer: (Boolean) -> Unit, wide: Boolean, compact: Boolean, big: Boolean, modifier: Modifier = Modifier) {
    val large = wide && !compact
    val confirm = card.confirm
    // 엔진이 hint 를 첫 줄("…라고 답해 주세요")로도 넣는다 — 버튼 옆에 따로 보여 주므로 목록에서는 뺀다
    val hintLine = confirm?.hint?.let { "${it}라고 답해 주세요" }
    val lines = if (hintLine != null) card.lines.filterNot { it.text == hintLine } else card.lines
    val answerNote = if (ui.answer != null && (card.kind == "estimate" || card.kind == "abstain")) " · 답: ${if (ui.answer) "예" else "아니요"}" else ""

    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, F30.line, RoundedCornerShape(18.dp))
            .padding(horizontal = if (large) 24.dp else 16.dp, vertical = if (large) 18.dp else 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(card.header + answerNote, color = F30.hazard, fontSize = 15.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(4.dp))
        if (wide && confirm != null) {
            // 넓은 화면: 제목(=질문)은 위에 한 번, 예/아니요는 오른쪽에 고정해 목록을 내려도 늘 보이게
            CardTitle(card.title, large)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    CardLines(lines, large)
                }
                Column(Modifier.width(if (big) 360.dp else 300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    ConfirmBlock(confirm, onAnswer, stacked = big, compact = compact)
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                CardTitle(card.title, large)
                if (confirm != null) {
                    ConfirmBlock(confirm, onAnswer, stacked = false, compact = compact)
                    Spacer(Modifier.height(14.dp))
                }
                CardLines(lines, large)
            }
        }
    }
}

@Composable
private fun CardTitle(title: String, wide: Boolean) {
    KText(
        title,
        color = F30.fg,
        fontSize = if (wide) 32.sp else 26.sp,
        lineHeight = if (wide) 40.sp else 33.sp,
        fontWeight = FontWeight.Bold,
        style = KoText,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun CardLines(lines: List<LineUi>, wide: Boolean) {
    val size = if (wide) 20.sp else 17.sp
    Column(verticalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 8.dp)) {
        lines.forEach { l ->
            Row {
                Box(Modifier.padding(top = if (wide) 10.dp else 9.dp, end = 12.dp).size(10.dp), contentAlignment = Alignment.Center) { Bullet(l.style) }
                KText(l.text, color = F30.body, fontSize = size, lineHeight = size * 1.45f, style = KoText)
            }
        }
    }
}

@Composable
private fun Bullet(style: LineStyle) {
    when (style) {
        LineStyle.PLAIN -> Box(Modifier.size(7.dp).background(F30.dim, CircleShape))
        LineStyle.SAY -> Box(Modifier.size(8.dp).background(F30.good, CircleShape))
        LineStyle.AVOID -> Box(Modifier.size(8.dp).background(F30.critical, RoundedCornerShape(1.dp)))
        LineStyle.BOX -> Box(Modifier.size(10.dp).border(1.5.dp, F30.muted, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun ConfirmBlock(confirm: ConfirmUi, onAnswer: (Boolean) -> Unit, stacked: Boolean, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        confirm.hint?.let {
            KText("${it}라고 답해 주세요", color = F30.hazard, fontSize = if (stacked) 19.sp else 17.sp, lineHeight = if (stacked) 27.sp else 24.sp, style = KoText)
        }
        // 예/아니요는 같은 모양·크기·색 — 어느 답도 권하지 않도록(DESIGN.md 5). 질문 막대와 같은 모양.
        val answerButton: @Composable (String, Boolean, Modifier) -> Unit = { label, value, m ->
            OutlinedButton(
                onClick = { onAnswer(value) },
                modifier = m.heightIn(min = if (compact) 60.dp else 72.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, F30.muted),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = F30.fg),
            ) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        }
        val yes: @Composable (Modifier) -> Unit = { m -> answerButton("예", true, m) }
        val no: @Composable (Modifier) -> Unit = { m -> answerButton("아니요", false, m) }
        if (stacked) {
            yes(Modifier.fillMaxWidth())
            no(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                yes(Modifier.weight(1f))
                no(Modifier.weight(1f))
            }
        }
    }
}

/** 확인 질문 막대 자리: 질문이 남아 있으면 막대, 막대로 답한 직후면 안내, 아니면 아무것도. */
@Composable
private fun BarSlot(barCard: CardUi?, answeredNote: Int?, onAnswer: (Boolean) -> Unit, onOpenFault: () -> Unit, stacked: Boolean, showHint: Boolean) {
    val confirm = barCard?.confirm
    when {
        confirm != null -> QuestionBar(confirm, onAnswer, stacked, showHint)
        answeredNote != null -> AnsweredNote(onOpenFault)
    }
}

/**
 * 뒤 카드에 남겨 두는 작은 확인 질문(엔진 confirm.question 그대로). 소리 내어 읽지 않는다(liveRegion 도 두지 않는다).
 * 예/아니요는 같은 모양·크기·색 — 어느 답도 권하지 않도록(DESIGN.md 5).
 */
@Composable
private fun QuestionBar(confirm: ConfirmUi, onAnswer: (Boolean) -> Unit, stacked: Boolean, showHint: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    val answerButton: @Composable (String, Boolean, Modifier) -> Unit = { label, value, m ->
        OutlinedButton(
            onClick = { onAnswer(value) },
            modifier = m.heightIn(min = 56.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(2.dp, F30.muted),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = F30.fg),
        ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KText(confirm.question, color = F30.fg, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, style = KoText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showHint) confirm.hint?.let {
                KText("${it}라고 답해 주세요", color = F30.muted, fontSize = 14.sp, lineHeight = 19.sp, style = KoText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val m = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
        .border(1.5.dp, F30.hazard, shape)
        .padding(horizontal = 12.dp, vertical = 8.dp)
    if (stacked) {
        Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            text(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                answerButton("예", true, Modifier.weight(1f))
                answerButton("아니요", false, Modifier.weight(1f))
            }
        }
    } else {
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            text(Modifier.weight(1f))
            answerButton("예", true, Modifier.width(112.dp))
            answerButton("아니요", false, Modifier.width(112.dp))
        }
    }
}

/** 막대로 답한 직후 잠깐(≈4초) 보이는 안내. 소리 내지 않는다. 누르면 과실 카드로(자동으로 넘기지 않는다). */
@Composable
private fun AnsweredNote(onOpenFault: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .border(1.5.dp, F30.good, shape)
            .clickable(onClick = onOpenFault)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(9.dp).background(F30.good, CircleShape))
        KText("답을 반영했어요 · 과실 카드 보기", color = F30.fg, fontSize = 17.sp, style = KoText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Subtitle(text: String, compact: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 6.dp else 10.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(F30.line))
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = if (compact) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpeakerIcon(Modifier.padding(top = 4.dp).size(20.dp))
            KText(
                text, color = F30.muted, fontSize = if (compact) 16.sp else 18.sp, lineHeight = if (compact) 22.sp else 26.sp, style = KoText, maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SpeakerIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * 0.09f, cap = StrokeCap.Round)
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, h * 0.38f); lineTo(w * 0.31f, h * 0.38f); lineTo(w * 0.56f, h * 0.19f)
            lineTo(w * 0.56f, h * 0.81f); lineTo(w * 0.31f, h * 0.62f); lineTo(w * 0.12f, h * 0.62f); close()
        }
        drawPath(body, F30.muted, style = stroke)
        drawArc(F30.muted, -50f, 100f, false, Offset(w * 0.5f, h * 0.3f), Size(w * 0.36f, h * 0.4f), style = stroke)
    }
}
