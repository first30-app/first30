package com.first30.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text

/** demo/src.html `:root` 의 야간 계기판 톤. 차량 화면이라 한 가지(다크) 모습만 쓴다. */
object F30 {
    val bg = Color(0xFF0B0F13)
    val screen = Color(0xFF05080B)
    val panel = Color(0xFF121920)
    val panel2 = Color(0xFF18222B)
    val line = Color(0xFF26333E)
    val fg = Color(0xFFE7EDF2)
    val body = Color(0xFFCFD9E1)
    val muted = Color(0xFFA9B7C2)
    val dim = Color(0xFF8496A6)
    val hazard = Color(0xFFFFB020)
    val hazardInk = Color(0xFF1B1300)
    val good = Color(0xFF3ECF8E)
    val critical = Color(0xFFFF5A4F)

    fun tone(t: Tone): Color = when (t) {
        Tone.NEUTRAL -> dim
        Tone.OK -> good
        Tone.WARN -> hazard
        Tone.CRIT -> critical
    }
}

private val scheme = darkColorScheme(
    primary = F30.hazard,
    onPrimary = F30.hazardInk,
    secondary = F30.fg,
    onSecondary = F30.bg,
    background = F30.bg,
    onBackground = F30.fg,
    surface = F30.bg,
    onSurface = F30.fg,
    surfaceVariant = F30.panel2,
    onSurfaceVariant = F30.muted,
    outline = F30.line,
    error = F30.critical,
)

@Composable
fun First30Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * CSS `word-break: keep-all` 대신: 한글이 낀 어절 안에 WORD JOINER(U+2060)를 넣어 띄어쓰기에서만 줄을 바꾼다
 * (Android 줄바꿈은 한글 음절 사이에서도 끊는다). 화면 글자에만 쓰고 TTS 에는 넘기지 않는다.
 */
fun ko(text: String): String {
    if (text.length < 2) return text
    val sb = StringBuilder(text.length + text.length / 2)
    for (i in text.indices) {
        val c = text[i]
        if (i > 0) {
            val p = text[i - 1]
            if (!p.isWhitespace() && !c.isWhitespace() && (isHangul(p) || isHangul(c)) && p != '·' && c != '·' && p != '/' && p != '—' && c != '—') sb.append('⁠')
        }
        sb.append(c)
    }
    return sb.toString()
}

private fun isHangul(c: Char): Boolean = c in '가'..'힣' || c in 'ㄱ'..'ㆎ'

/** 화면 글자는 [ko] 로 어절 단위 줄바꿈, 접근성(TalkBack·UI 테스트)에는 원래 문장을 준다. */
@androidx.compose.runtime.Composable
fun KText(
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
) {
    androidx.compose.material3.Text(
        ko(text),
        modifier = modifier.clearAndSetSemantics { this.text = androidx.compose.ui.text.AnnotatedString(text) },
        color = color, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight,
        maxLines = maxLines, overflow = overflow, style = style,
    )
}
