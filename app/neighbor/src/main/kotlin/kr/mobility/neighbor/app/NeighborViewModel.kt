package kr.mobility.neighbor.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.mobility.core.time.KST
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.neighbor.AlertReason
import kr.mobility.neighbor.AppealStatus
import kr.mobility.neighbor.Consent
import kr.mobility.neighbor.DemoTraffic
import kr.mobility.neighbor.DisplayItem
import kr.mobility.neighbor.NearbyVehicle
import kr.mobility.neighbor.NeighborAlertPolicy
import kr.mobility.neighbor.NeighborDisplay
import kr.mobility.neighbor.Position
import kr.mobility.neighbor.Side
import kr.mobility.platform.pleos.Platform
import kr.mobility.platform.pleos.PleosVehicleSignals
import kr.mobility.platform.pleos.TurnSignalState
import java.time.LocalDate
import java.time.ZonedDateTime

enum class Screen { MY_GRADE, APPEAL, SETTINGS, DEMO }

data class NeighborSettings(
    val introDone: Boolean = false,
    /** 동의 화면을 끝냈는지. 동의 값 자체는 [consent]. */
    val consentDone: Boolean = false,
    val consent: Consent = Consent(location = false, share = false),
    val consentDate: String? = null,
    val voiceOn: Boolean = true,
    /** 미동의 차량을 회색으로 보기 (A-71). */
    val showUnrated: Boolean = false,
    val demo: Boolean = true,
)

/** 시연 장면. 기획서 시나리오와 같은 이름. */
enum class Scene(val title: String) {
    APPROACH("S0 주변 보기와 접근 안내"),
    LANE_CHANGE("S1 고속도로 차선 변경 전 (보는 사람)"),
}

data class NeighborUi(
    val settings: NeighborSettings,
    val screen: Screen = Screen.MY_GRADE,
    val state: DrivingState = DrivingState.PARKED,
    val items: List<DisplayItem> = emptyList(),
    /** 강조할 칸과 큰 문구. 안내한 뒤 잠시 유지한다. */
    val highlight: Position? = null,
    val line: String? = null,
    val captions: List<String> = emptyList(),
    val mySignal: Side? = null,
    /** 협력 데이터가 없어 주변 정보를 못 받는 상태(실제 모드). */
    val noData: Boolean = false,
    val scene: Scene? = null,
    val step: Int = 0,
    val total: Int = 0,
    val appeal: AppealStatus = AppealStatus.NONE,
    val appealReason: String? = null,
)

/**
 * 안심 이웃 시연판. 주변 차량과 등급은 가상 데이터이고, 실제 데이터는 협력 후 연결한다.
 * 차량 쪽(기어·속도·방향지시등)과 음성은 실제 Pleos SDK를 쓴다.
 */
class NeighborViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("neighbor", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(NeighborUi(settings = load(), appeal = loadAppeal(), appealReason = prefs.getString(KEY_APPEAL_REASON, null)))
    val ui: StateFlow<NeighborUi> = _ui.asStateFlow()

    private var platform: Platform? = null
    private var car: PleosVehicleSignals? = null
    private val jobs = mutableListOf<Job>()
    private var sceneJob: Job? = null
    private val policy = NeighborAlertPolicy()
    private var alertState = NeighborAlertPolicy.State()

    init {
        start()
    }

    fun start() {
        stop()
        val p = Platform.demo(getApplication())
        platform = p
        p.start()
        val c = PleosVehicleSignals(getApplication())
        car = c
        c.start()
        jobs += viewModelScope.launch { p.speaker.lines.collect { lines -> _ui.update { it.copy(captions = lines) } } }
        jobs += viewModelScope.launch {
            c.reading.collect { r ->
                val real = DrivingState.from(r.gear, r.speedKmh ?: 0.0, r.parkingBrake == true, r.charging == true)
                val signal = when (r.turnSignal) {
                    TurnSignalState.LEFT -> Side.LEFT
                    TurnSignalState.RIGHT -> Side.RIGHT
                    TurnSignalState.OFF -> null
                }
                if (_ui.value.scene != null) {
                    // 시연 중에도 실제 방향지시등을 켜면 그쪽 안내가 나온다.
                    realSignal = signal
                } else {
                    realSignal = signal
                    // 실제 모드: 협력 데이터가 아직 없어 주변 차량을 받지 못한다.
                    _ui.update { it.copy(state = real, noData = real.isRestricted, mySignal = signal, items = emptyList()) }
                }
            }
        }
    }

    private var realSignal: Side? = null

    fun playScene(scene: Scene) {
        sceneJob?.cancel()
        alertState = NeighborAlertPolicy.State()
        val frames = when (scene) {
            Scene.APPROACH -> DemoTraffic.closing()
            Scene.LANE_CHANGE -> DemoTraffic.laneChange()
        }
        sceneJob = viewModelScope.launch {
            _ui.update { it.copy(scene = scene, total = frames.size, state = DrivingState.DRIVING, noData = false, highlight = null, line = null) }
            frames.forEachIndexed { i, frame ->
                val demoSignal = if (scene == Scene.LANE_CHANGE && i >= DemoTraffic.S1_MY_SIGNAL_FROM_STEP) Side.RIGHT else null
                showFrame(frame, demoSignal ?: realSignal, i + 1)
                delay(STEP_MILLIS)
            }
            // 안내를 끝까지 들을 수 있게 조금 더 둔다.
            delay(STEP_MILLIS)
            stopScene()
        }
    }

    private fun showFrame(frame: List<NearbyVehicle>, signal: Side?, step: Int) {
        val settings = _ui.value.settings
        val items = if (settings.consent.canViewNearby) NeighborDisplay(showUnrated = settings.showUnrated).items(frame) else emptyList()
        val now = ZonedDateTime.now(KST)
        val (alert, next) = if (settings.consent.canViewNearby) policy.decide(frame, now, alertState, signal) else null to alertState
        alertState = next
        _ui.update { it.copy(items = items, step = step, mySignal = signal) }
        if (alert == null) return
        val target = frame.first { it.tempId == alert.tempId }
        _ui.update {
            it.copy(
                highlight = Position.of(target.x, target.y),
                line = if (alert.reason == AlertReason.MY_SIGNAL) alert.line.substringAfterLast(". ").removeSuffix(".").ifBlank { alert.line } else alert.line.removeSuffix("."),
            )
        }
        val p = platform ?: return
        if (settings.voiceOn) viewModelScope.launch { p.speaker.speak(alert.line) } else p.speaker.caption(alert.line)
    }

    fun stopScene() {
        sceneJob?.cancel()
        sceneJob = null
        _ui.update { it.copy(scene = null, state = DrivingState.PARKED, items = emptyList(), highlight = null, line = null, mySignal = null, step = 0, total = 0) }
    }

    fun go(screen: Screen) = _ui.update { it.copy(screen = screen) }

    fun finishIntro() = update { it.copy(introDone = true) }

    fun finishConsent(location: Boolean, share: Boolean) = update {
        it.copy(consentDone = true, consent = Consent(location, share), consentDate = LocalDate.now(KST).toString())
    }

    fun setLocationConsent(on: Boolean) = update {
        it.copy(consent = it.consent.copy(location = on), consentDate = LocalDate.now(KST).toString())
    }

    fun setShareConsent(on: Boolean) = update {
        it.copy(consent = it.consent.copy(share = on), consentDate = LocalDate.now(KST).toString())
    }

    fun setVoice(on: Boolean) = update { it.copy(voiceOn = on) }
    fun setShowUnrated(on: Boolean) = update { it.copy(showUnrated = on) }
    fun setDemo(on: Boolean) {
        update { it.copy(demo = on) }
        if (!on) go(Screen.MY_GRADE)
    }

    /** 이의 신청 (F-08). 시연판은 협력사 처리를 버튼으로 대신한다. */
    fun submitAppeal(reason: String) {
        prefs.edit().putString(KEY_APPEAL, AppealStatus.RECEIVED.name).putString(KEY_APPEAL_REASON, reason).apply()
        _ui.update { it.copy(appeal = AppealStatus.RECEIVED, appealReason = reason) }
    }

    fun advanceAppeal() {
        val next = when (_ui.value.appeal) {
            AppealStatus.RECEIVED -> AppealStatus.CHECKING
            AppealStatus.CHECKING -> AppealStatus.DONE
            else -> AppealStatus.NONE
        }
        prefs.edit().putString(KEY_APPEAL, next.name).apply()
        _ui.update { it.copy(appeal = next) }
    }

    private fun update(f: (NeighborSettings) -> NeighborSettings) {
        val s = f(_ui.value.settings)
        prefs.edit()
            .putBoolean(KEY_INTRO, s.introDone).putBoolean(KEY_CONSENT_DONE, s.consentDone)
            .putBoolean(KEY_LOCATION, s.consent.location).putBoolean(KEY_SHARE, s.consent.share)
            .putString(KEY_CONSENT_DATE, s.consentDate).putBoolean(KEY_VOICE, s.voiceOn)
            .putBoolean(KEY_UNRATED, s.showUnrated).putBoolean(KEY_DEMO, s.demo)
            .apply()
        _ui.update { it.copy(settings = s) }
    }

    private fun load() = NeighborSettings(
        introDone = prefs.getBoolean(KEY_INTRO, false),
        consentDone = prefs.getBoolean(KEY_CONSENT_DONE, false),
        consent = Consent(prefs.getBoolean(KEY_LOCATION, false), prefs.getBoolean(KEY_SHARE, false)),
        consentDate = prefs.getString(KEY_CONSENT_DATE, null),
        voiceOn = prefs.getBoolean(KEY_VOICE, true),
        showUnrated = prefs.getBoolean(KEY_UNRATED, false),
        demo = prefs.getBoolean(KEY_DEMO, true),
    )

    private fun loadAppeal(): AppealStatus =
        runCatching { AppealStatus.valueOf(prefs.getString(KEY_APPEAL, null) ?: "") }.getOrDefault(AppealStatus.NONE)

    private fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        car?.stop()
        car = null
        platform?.release()
        platform = null
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val STEP_MILLIS = 2_500L
        const val KEY_INTRO = "intro"
        const val KEY_CONSENT_DONE = "consent_done"
        const val KEY_LOCATION = "location"
        const val KEY_SHARE = "share"
        const val KEY_CONSENT_DATE = "consent_date"
        const val KEY_VOICE = "voice"
        const val KEY_UNRATED = "unrated"
        const val KEY_DEMO = "demo"
        const val KEY_APPEAL = "appeal"
        const val KEY_APPEAL_REASON = "appeal_reason"
    }
}
