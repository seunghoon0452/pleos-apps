package kr.mobility.platform.pleos

import kotlinx.coroutines.flow.StateFlow
import kr.mobility.core.vehicle.Gear

/**
 * 앱이 쓰는 차량·내비·음성·위치 입구. 각각 Pleos SDK 구현과 시연·테스트용 가짜 구현이 있다.
 * 엔진 모듈과 묶이지 않도록 SDK 값을 단순한 값으로만 바꿔서 넘긴다(종류 이름은 SDK 이름 그대로).
 */

enum class TurnSignalState { OFF, LEFT, RIGHT }

/** 차량 신호를 한데 모은 값. 모르는 값은 null. */
data class VehicleReading(
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Double? = null,
    val parkingBrake: Boolean? = null,
    val charging: Boolean? = null,
    val socPercent: Double? = null,
    val evRangeKm: Double? = null,
    /** 누적 전비(km/kWh). */
    val evEfficiency: Double? = null,
    val batteryCapacityKwh: Double? = null,
    val fuelPercent: Double? = null,
    /** 누적 연비(km/L). */
    val fuelEconomy: Double? = null,
    val odometerKm: Double? = null,
    val outsideTempC: Double? = null,
    val wiperOn: Boolean = false,
    val frontFogLightOn: Boolean = false,
    val washerFluidLow: Boolean = false,
    val turnSignal: TurnSignalState = TurnSignalState.OFF,
    /** 안드로이드 주행 제한(CarUxRestrictions)이 걸려 있는지. 모르면 null. */
    val uxRestricted: Boolean? = null,
) {
    val isEv: Boolean get() = socPercent != null && evRangeKm != null
}

interface VehicleSignals {
    val reading: StateFlow<VehicleReading>
    fun start()
    fun stop()
}

/** 앞 주의 지점. [type]은 SDK CautionPointType 이름. */
data class NaviCaution(val id: String, val type: String, val remainMeters: Int)

/** 돌발 정보. [type]은 SDK TrafficEventType 이름. SDK는 위치만 준다. */
data class NaviIncident(val type: String, val lat: Double, val lng: Double, val description: String?)

/** 혼잡 구간. [type]은 SDK TrafficCongestionType 이름. [offsetMeters]·[lengthMeters]는 SDK 값 그대로. */
data class NaviCongestion(val type: String, val offsetMeters: Double, val lengthMeters: Double)

data class NaviPosition(val lat: Double, val lng: Double, val heading: Double)

/** 경로 탐색 결과(TripInfo): 거리(m), 도착까지 시간(초), 도착 시 예상 배터리(%). */
data class TripEstimate(val distanceMeters: Int, val etaSeconds: Int, val estimatedSocAtDest: Int?)

data class NaviState(
    val routeGuidance: Boolean = false,
    val cautions: List<NaviCaution> = emptyList(),
    val incidents: List<NaviIncident> = emptyList(),
    val congestion: List<NaviCongestion> = emptyList(),
    val position: NaviPosition? = null,
    val speedLimitKmh: Int? = null,
)

interface NaviSource {
    val state: StateFlow<NaviState>
    fun start()
    fun stop()

    /** 목적지까지의 거리·예상 배터리를 묻는다. 답이 없으면 null. */
    suspend fun estimateTrip(lat: Double, lng: Double, name: String): TripEstimate?

    /**
     * 충전소·주유소를 내비에 넣는다. 경로 안내 중이면 경유지, 아니면 목적지로 설정한다(F-11).
     * R-13: 운전자가 "응"하거나 주차 중 버튼을 누른 뒤에만 부른다. 넣지 못했으면 false.
     */
    fun guideTo(lat: Double, lng: Double, name: String): Boolean
}

interface Speaker {
    /** 다 읽을 때까지 기다린다. 실패하면 false. */
    suspend fun speak(text: String): Boolean
    fun stop()
    fun release()
}

interface Listener {
    /** 한 번 듣고 최종 문장을 돌려준다. 못 알아들으면 null. */
    suspend fun listenOnce(timeoutMillis: Long = 8_000): String?
    fun release()
}

data class LocationFix(val lat: Double, val lng: Double, val heading: Double?, val timeMillis: Long)

interface LocationSource {
    val fix: StateFlow<LocationFix?>
    fun start()
    fun stop()
}

/** LLM 한 번 묻기. 답이 없으면 null. */
interface TextGenerator {
    suspend fun generate(prompt: String, timeoutMillis: Long = 4_000): String?
    fun release()
}
