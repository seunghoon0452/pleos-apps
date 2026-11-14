package kr.mobility.platform.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 내비 음성과 겹치지 않게 오디오 포커스를 잠깐 받는다(정의서: 내비 음성이 나올 때는 기다렸다가 안내).
 * 포커스를 못 받으면 최대 [maxWaitMillis]까지 기다린 뒤 그래도 말한다.
 */
internal class GuidanceFocus(context: Context, private val maxWaitMillis: Long = 10_000) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    suspend fun <T> around(block: suspend () -> T): T {
        var waited = 0L
        while (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED && waited < maxWaitMillis) {
            delay(500)
            waited += 500
        }
        return try {
            block()
        } finally {
            audio?.abandonAudioFocusRequest(request)
        }
    }
}

/** Pleos TextToSpeech SDK. 초기화에 실패하면 안드로이드 기본 TTS로 대신한다. */
class PleosSpeaker(context: Context, private val fallback: Speaker = AndroidSpeaker(context)) : Speaker {
    private val focus = GuidanceFocus(context)
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var current: CompletableDeferred<Boolean>? = null
    private val tts: TextToSpeech? = runCatching { TextToSpeech(context, TtsMode.HYBRID) }
        .onFailure { Log.w(TAG, "TTS create failed", it) }.getOrNull()

    init {
        val t = tts
        if (t == null) {
            ready.complete(false)
        } else {
            runCatching {
                // SDK는 initialize 뒤에야 리스너를 받는다(먼저 붙이면 내부 객체가 없어서 실패).
                t.initialize(object : TtsInit {
                    override fun onSuccess() {
                        runCatching {
                            t.addEventListener(object : EventListener {
                                override fun onStart() {}
                                override fun onReady() {}
                                override fun onDone() { current?.complete(true) }
                                override fun onError(errorType: TtsError) { current?.complete(false) }
                                override fun onStop() { current?.complete(false) }
                                override fun onUpdatedRms(rms: Double) {}
                            })
                            t.setLocale(Locale.KOREA)
                        }.onFailure { Log.w(TAG, "TTS setup after init failed", it) }
                        Log.i(TAG, "TTS ready")
                        ready.complete(true)
                    }
                    override fun onFailure(errorType: TtsError) {
                        Log.w(TAG, "TTS init failed: $errorType")
                        ready.complete(false)
                    }
                })
            }.onFailure {
                Log.w(TAG, "TTS initialize threw", it)
                ready.complete(false)
            }
        }
    }

    override suspend fun speak(text: String): Boolean {
        val ok = withTimeoutOrNull(6_000) { ready.await() } == true
        val t = tts
        if (!ok || t == null) {
            Log.w(TAG, "Pleos TTS not ready, using Android TTS")
            return fallback.speak(text)
        }
        return focus.around {
            val done = CompletableDeferred<Boolean>()
            current = done
            runCatching { t.speak(text, false) }.onFailure { done.complete(false) }
            (withTimeoutOrNull(speakTimeout(text)) { done.await() } ?: false).also {
                current = null
                Log.i(TAG, "spoke=$it chars=${text.length}")
            }
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
        fallback.stop()
    }

    override fun release() {
        runCatching { tts?.release() }
        fallback.release()
    }

    private companion object {
        const val TAG = "PleosTts"
    }
}

// Pleos 온디바이스 TTS는 끝났다는 신호(onDone)를 주지 않을 때가 있어서, 한국어 말하기 속도(초당 약 7자)로 끝을 가늠한다.
internal fun speakTimeout(text: String): Long = 1_500L + text.length * 150L

/** 안드로이드 기본 TTS. Pleos TTS를 쓸 수 없을 때만 쓴다. */
class AndroidSpeaker(context: Context) : Speaker {
    private val focus = GuidanceFocus(context)
    private val ready = CompletableDeferred<Boolean>()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val tts = AndroidTts(context.applicationContext) { status ->
        Log.i("AndroidTts", "init status=$status")
        ready.complete(status == AndroidTts.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete(false) }
        })
    }

    override suspend fun speak(text: String): Boolean {
        if (withTimeoutOrNull(3_000) { ready.await() } != true) return false
        tts.language = Locale.KOREA
        return focus.around {
            val id = "u${System.nanoTime()}"
            val done = CompletableDeferred<Boolean>()
            pending[id] = done
            tts.speak(text, AndroidTts.QUEUE_FLUSH, null, id)
            (withTimeoutOrNull(speakTimeout(text)) { done.await() } ?: false).also { Log.i("AndroidTts", "spoke=$it") }
        }
    }

    override fun stop() {
        tts.stop()
    }

    override fun release() {
        tts.shutdown()
    }
}

/** Pleos SpeechToText SDK. 쓸 수 없으면 안드로이드 기본 음성 인식으로 대신한다. */
class PleosListener(context: Context, private val fallback: Listener = AndroidListener(context)) : Listener {
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var current: CompletableDeferred<String?>? = null
    @Volatile private var lastPartial: String? = null
    private val stt: SpeechToText? = runCatching { SpeechToText(context, SttMode.HYBRID) }
        .onFailure { Log.w(TAG, "STT create failed", it) }.getOrNull()

    private val resultListener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            lastPartial = text
            if (isFinal) current?.complete(text)
        }
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() { current?.complete(lastPartial) }
        override fun onError(errorType: SttError) {
            Log.w(TAG, "STT error $errorType")
            current?.complete(null)
        }
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        val s = stt
        if (s == null) {
            ready.complete(false)
        } else {
            runCatching {
                s.initialize(object : SttInit {
                    override fun onSuccess() {
                        runCatching {
                            s.addListener(resultListener)
                            s.setLocale(Locale.KOREA)
                        }.onFailure { Log.w(TAG, "STT setup after init failed", it) }
                        Log.i(TAG, "STT ready")
                        ready.complete(true)
                    }
                    override fun onFailure(errorType: SttError) {
                        Log.w(TAG, "STT init failed: $errorType")
                        ready.complete(false)
                    }
                })
            }.onFailure {
                Log.w(TAG, "STT initialize threw", it)
                ready.complete(false)
            }
        }
    }

    override suspend fun listenOnce(timeoutMillis: Long): String? {
        val s = stt
        if (withTimeoutOrNull(3_000) { ready.await() } != true || s == null) return fallback.listenOnce(timeoutMillis)
        val done = CompletableDeferred<String?>()
        current = done
        lastPartial = null
        runCatching { s.request() }.onFailure { done.complete(null) }
        val result = withTimeoutOrNull(timeoutMillis) { done.await() } ?: lastPartial
        runCatching { s.stop() }
        current = null
        return result?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun release() {
        runCatching { stt?.removeListener(resultListener) }
        runCatching { stt?.release() }
        fallback.release()
    }

    private companion object {
        const val TAG = "PleosStt"
    }
}

/** 안드로이드 기본 음성 인식. 메인 스레드에서만 동작한다. */
class AndroidListener(private val context: Context) : Listener {
    override suspend fun listenOnce(timeoutMillis: Long): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val done = CompletableDeferred<String?>()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                done.complete(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onError(error: Int) { done.complete(null) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        recognizer.startListening(intent)
        val result = withTimeoutOrNull(timeoutMillis) { done.await() }
        recognizer.destroy()
        result
    }

    override fun release() {}
}
