package kr.mobility.platform.pleos

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint

/** 시연 모드·테스트용 차량. 값은 [set]으로 바꾼다. */
class FakeVehicleSignals(initial: VehicleReading = VehicleReading()) : VehicleSignals {
    private val _reading = MutableStateFlow(initial)
    override val reading: StateFlow<VehicleReading> = _reading.asStateFlow()
    fun set(f: (VehicleReading) -> VehicleReading) = _reading.update(f)
    override fun start() {}
    override fun stop() {}
}

/** 시연 모드 내비. 거리 묻기는 직선거리 × 1.3으로 답한다. */
class FakeNaviSource(private val origin: () -> GeoPoint?) : NaviSource {
    private val _state = MutableStateFlow(NaviState())
    override val state: StateFlow<NaviState> = _state.asStateFlow()
    val waypoints = MutableStateFlow<List<String>>(emptyList())

    fun set(f: (NaviState) -> NaviState) = _state.update(f)
    override fun start() {}
    override fun stop() {}

    override suspend fun estimateTrip(lat: Double, lng: Double, name: String): TripEstimate? {
        val from = origin() ?: return null
        val km = Geo.estimatedRoadKm(from, GeoPoint(lat, lng))
        return TripEstimate((km * 1000).toInt(), (km / 60.0 * 3600).toInt(), null)
    }

    override fun guideTo(lat: Double, lng: Double, name: String): Boolean {
        waypoints.update { it + name }
        return true
    }
}

/** 말한 문장을 화면 자막으로 보여주기 위한 기록. 진짜 [Speaker]를 감싸서 쓴다. */
class CaptionedSpeaker(private val inner: Speaker?) : Speaker {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 소리 없이 자막만 남긴다(음성 꺼짐, A-59). */
    fun caption(text: String) {
        _lines.update { (it + text).takeLast(20) }
    }

    override suspend fun speak(text: String): Boolean {
        _lines.update { (it + text).takeLast(20) }
        return inner?.speak(text) ?: run {
            delay(speakTimeout(text) / 4)
            true
        }
    }

    override fun stop() {
        inner?.stop()
    }

    override fun release() {
        inner?.release()
    }
}

/** 마이크 대신 화면에서 문장을 넣는 듣기. 시연에서 소음 없이 같은 문장을 재현할 때 쓴다. */
class TypedListener : Listener {
    private var waiting: CompletableDeferred<String?>? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    fun submit(text: String?) {
        waiting?.complete(text)
    }

    override suspend fun listenOnce(timeoutMillis: Long): String? {
        val d = CompletableDeferred<String?>()
        waiting = d
        _listening.value = true
        return try {
            withTimeoutOrNull(timeoutMillis) { d.await() }
        } finally {
            _listening.value = false
            waiting = null
        }
    }

    override fun release() {}
}

class FixedLocationSource(initial: LocationFix?) : LocationSource {
    private val _fix = MutableStateFlow(initial)
    override val fix: StateFlow<LocationFix?> = _fix.asStateFlow()
    fun set(value: LocationFix?) {
        _fix.value = value
    }
    override fun start() {}
    override fun stop() {}
}
