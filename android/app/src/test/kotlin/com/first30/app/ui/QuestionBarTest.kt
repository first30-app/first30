package com.first30.app.ui

import com.first30.core.scenarios.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 뒤 카드의 확인 질문 막대를 언제 띄우는지([IncidentUi.questionBarCard]). */
class QuestionBarTest {
    private fun card(i: Int, at: Int, confirm: Boolean = false) = CardUi(
        index = i, kind = if (confirm) "abstain" else "speech", stepLabel = if (confirm) "확인" else "말하기", atSec = at,
        title = "t$i", lines = emptyList(), tts = "",
        confirm = if (confirm) ConfirmUi("그때 오른쪽 차로로 진로를 바꾸던 중이었나요?", null, "right") else null,
    )

    private fun ui(mode: String = "urban", answer: Boolean? = null, confirmAt: Int = 2, n: Int = 5) = IncidentUi(
        mode = mode, modeLabel = mode, modeTone = Tone.NEUTRAL, sealed = true, sealHashShort = null,
        cards = List(n) { i -> card(i, listOf(0, 5, 10, 20, 25)[i], confirm = i == confirmAt) },
        startedAtMs = 0L, answer = answer,
    )

    @Test fun `shown on later cards once the confirm window has passed`() {
        val u = ui()
        assertEquals(2, u.questionBarCard(shownIndex = 3, timelineIndex = 3)?.index)
        assertEquals(2, u.questionBarCard(shownIndex = 4, timelineIndex = 4)?.index)
        // 30초 뒤(타임라인 = 마지막 카드)에도 답이 없으면 그대로
        val end = u.activeIndexAt(45.0)
        assertEquals(4, end)
        assertNotNull(u.questionBarCard(end, end))
    }

    @Test fun `not on the confirm card itself or before it`() {
        val u = ui()
        assertNull(u.questionBarCard(2, 2)) // 질문 카드(큰 버튼)
        assertNull(u.questionBarCard(0, 0))
        assertNull(u.questionBarCard(1, 1))
        // 지나간 뒤 단계를 눌러 앞 카드를 보면 막대 없음
        assertNull(u.questionBarCard(shownIndex = 2, timelineIndex = 4))
        assertNull(u.questionBarCard(shownIndex = 0, timelineIndex = 4))
        // 질문 시간 전에 뒤 카드를 미리 눌러 봐도 아직은 없음(질문 카드가 먼저 나와야 한다)
        assertNull(u.questionBarCard(shownIndex = 4, timelineIndex = 2))
    }

    @Test fun `hidden once answered`() {
        assertNull(ui(answer = true).questionBarCard(3, 3))
        assertNull(ui(answer = false).questionBarCard(3, 3))
        assertNull(ui().questionBarCard(3, 3, answeredLocally = true))
    }

    @Test fun `no confirm card or confirm card last means no bar`() {
        assertNull(ui(confirmAt = -1).questionBarCard(4, 4))
        assertNull(ui(confirmAt = 4).questionBarCard(4, 4)) // 긴급 모드처럼 과실(질문) 카드가 마지막
    }

    @Test fun `fast road modes still show the bar on screen`() {
        assertNotNull(ui(mode = "highway").questionBarCard(3, 3))
        assertNotNull(ui(mode = "caution").questionBarCard(4, 4))
    }

    @Test fun `engine scenarios`() {
        val u = previewUi("confirm_needed")
        val k = u.cards.indexOfFirst { it.confirm != null }
        assert(k in 0 until u.cards.lastIndex) { "confirm card expected before the last card" }
        val speech = u.activeIndexAt(u.cards[k + 1].atSec.toDouble())
        val bar = u.questionBarCard(speech, speech)
        assertEquals(k, bar?.index)
        assertEquals(u.cards[k].confirm!!.question, bar!!.confirm!!.question)
        assertNull(u.questionBarCard(k, k))
        // 답한 결과에는 질문 카드도 막대도 없다
        val answered = previewUi("confirm_needed", answer = true)
        assertNull(answered.questionBarCard(speech, speech))
        assertEquals(-1, answered.cards.indexOfFirst { it.confirm != null })

        // 모든 시나리오: 답 없는 결과에서 막대가 뜨는 카드는 늘 질문 카드보다 뒤
        for (sc in Scenarios.ALL) {
            val v = previewUi(sc.id)
            val c = v.cards.indexOfFirst { it.confirm != null }
            for (t in v.cards.indices) for (s in v.cards.indices) {
                val b = v.questionBarCard(s, t)
                if (b != null) {
                    assertEquals(c, b.index, sc.id)
                    assert(s > c && t > c) { sc.id }
                }
            }
            if (v.mode == "emergency" && c == v.cards.lastIndex) assertNull(v.questionBarCard(c, c), sc.id)
        }
    }
}
