package com.first30.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 카드 `tts` 문장을 읽는 음성 출력. 화면 자막은 음성이 안 돼도 늘 나오므로, 구현은 실패해도 조용히 넘어가야 한다.
 * [speak] 는 앞서 읽던 문장을 끊고 새 문장을 읽는다(데모의 `speechSynthesis.cancel()` + `speak()`).
 */
interface Speaker {
    /** 음성을 낼 수 있는 상태인지. 초기화 중이거나 한국어 음성이 없으면 false. */
    val isReady: Boolean

    /** 상태 표시용(화면의 "음성 꺼짐" 표시). */
    val state: StateFlow<SpeakerState>

    fun speak(text: String)

    fun stop()

    /** 화면을 닫을 때 한 번. */
    fun shutdown() {}
}

enum class SpeakerState { INITIALIZING, READY, UNAVAILABLE, MUTED }

/** 미리보기·테스트용. 읽은 문장을 기록만 한다. */
class FakeSpeaker(ready: Boolean = true) : Speaker {
    private val _state = MutableStateFlow(if (ready) SpeakerState.READY else SpeakerState.UNAVAILABLE)
    override val state: StateFlow<SpeakerState> = _state.asStateFlow()
    override val isReady: Boolean get() = _state.value == SpeakerState.READY
    val spoken = mutableListOf<String>()
    var stops = 0
        private set

    override fun speak(text: String) {
        spoken += text
    }

    override fun stop() {
        stops++
    }
}

/** 소리를 내지 않는 구현(음성 끔). */
object SilentSpeaker : Speaker {
    private val _state = MutableStateFlow(SpeakerState.MUTED)
    override val state: StateFlow<SpeakerState> = _state.asStateFlow()
    override val isReady: Boolean = false
    override fun speak(text: String) {}
    override fun stop() {}
}

/**
 * Android [TextToSpeech] 구현 (Locale.KOREAN). 초기화 전에 들어온 문장은 마지막 하나만 기억했다가 준비되면 읽는다.
 * 초기화에 실패하거나 한국어 음성이 없으면 [SpeakerState.UNAVAILABLE] 로 두고 아무것도 하지 않는다.
 * 데모와 같은 빠르기(1.05). Pleos 의 TextToSpeech 를 쓰게 되면 이 인터페이스의 다른 구현으로 바꾼다.
 */
class AndroidSpeaker(context: Context, private val rate: Float = 1.05f) : Speaker {
    private val _state = MutableStateFlow(SpeakerState.INITIALIZING)
    override val state: StateFlow<SpeakerState> = _state.asStateFlow()
    override val isReady: Boolean get() = _state.value == SpeakerState.READY

    @Volatile private var pending: String? = null
    private var seq = 0
    private val tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext) { status -> onInit(status) }
    } catch (e: Exception) {
        Log.w(TAG, "TextToSpeech 생성 실패", e)
        _state.value = SpeakerState.UNAVAILABLE
        null
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "TextToSpeech 초기화 실패: $status")
            _state.value = SpeakerState.UNAVAILABLE
            return
        }
        val lang = try { engine.setLanguage(Locale.KOREAN) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "한국어 음성 없음: $lang")
            _state.value = SpeakerState.UNAVAILABLE
            return
        }
        engine.setSpeechRate(rate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        _state.value = SpeakerState.READY
        pending?.let { pending = null; speak(it) }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        when (_state.value) {
            SpeakerState.INITIALIZING -> pending = text
            SpeakerState.READY -> try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "first30-${++seq}")
            } catch (e: Exception) {
                Log.w(TAG, "speak 실패", e)
            }
            else -> Unit
        }
    }

    override fun stop() {
        pending = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    override fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        _state.value = SpeakerState.UNAVAILABLE
    }

    private companion object {
        const val TAG = "First30Speaker"
    }
}
