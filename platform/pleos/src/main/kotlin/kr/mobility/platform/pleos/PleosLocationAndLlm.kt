package kr.mobility.platform.pleos

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.listener.ResultListener
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.llm.constant.Mode as LlmMode

/** FusedLocation SDK. 위치는 화면 표시와 계산에만 쓰고 그대로 저장하지 않는다(엔진이 300m 칸으로 바꾼다). */
class PleosLocationSource(private val context: Context) : LocationSource {
    private val _fix = MutableStateFlow<LocationFix?>(null)
    override val fix: StateFlow<LocationFix?> = _fix.asStateFlow()
    private var fused: FusedLocation? = null

    private val listener = object : FusedLocationListener {
        override fun onChanged(locationData: LocationData?) {
            if (locationData == null) return
            _fix.value = LocationFix(
                locationData.latitude,
                locationData.longitude,
                locationData.heading.toDouble(),
                locationData.timestampMillis,
            )
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "location error", e)
        }
    }

    override fun start() {
        runCatching {
            val f = FusedLocation(context).also { it.initialize() }
            fused = f
            if (f.isSupported()) {
                f.getLastKnownLocation(listener)
                f.registerFusedLocationCallback(listener)
            }
        }.onFailure { Log.w(TAG, "FusedLocation unavailable", it) }
    }

    override fun stop() {
        runCatching { fused?.unregisterFusedLocationCallback(listener) }
        runCatching { fused?.release() }
        fused = null
    }

    private companion object {
        const val TAG = "PleosLocation"
    }
}

/** LLM SDK. 음성 일정 문장을 해석할 때 먼저 묻고, 답이 없으면 규칙 해석기를 쓴다. */
class PleosTextGenerator(context: Context, mode: LlmMode = LlmMode.SERVER) : TextGenerator {
    private val llm: LLM? = runCatching { LLM(context, mode).also { it.initialize() } }
        .onFailure { Log.w(TAG, "LLM unavailable", it) }
        .getOrNull()

    override suspend fun generate(prompt: String, timeoutMillis: Long): String? {
        val l = llm ?: return null
        val done = CompletableDeferred<String?>()
        val sb = StringBuilder()
        runCatching {
            l.generateContent(prompt, object : ResultListener {
                override fun onResponse(response: String, isFinal: Boolean) {
                    sb.append(response)
                    if (isFinal) done.complete(sb.toString())
                }

                override fun onError(error: String) {
                    Log.w(TAG, "LLM error $error")
                    done.complete(null)
                }
            })
        }.onFailure { done.complete(null) }
        return withTimeoutOrNull(timeoutMillis) { done.await() }
    }

    override fun release() {
        runCatching { llm?.release() }
    }

    private companion object {
        const val TAG = "PleosLlm"
    }
}
