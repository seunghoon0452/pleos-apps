package kr.mobility.ansim.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.mobility.ansim.AnsimCopy
import kr.mobility.ansim.AnsimSession
import kr.mobility.ansim.Frame
import kr.mobility.ansim.Sensitivity
import kr.mobility.ansim.SignalAlertPolicy
import kr.mobility.ansim.SignalJudge
import kr.mobility.ansim.demo.DemoDrive
import kr.mobility.core.time.KST
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.platform.pleos.LocationFix
import kr.mobility.platform.pleos.NaviState
import kr.mobility.platform.pleos.Platform
import kr.mobility.platform.pleos.PleosListener
import kr.mobility.platform.pleos.TypedListener
import kr.mobility.platform.pleos.VehicleReading
import java.time.LocalDate
import java.time.ZonedDateTime

data class AnsimSettings(
    val voiceOn: Boolean = true,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    /** 시연 모드(F-10). 공모전 빌드는 켜진 채로 시작한다. */
    val demo: Boolean = true,
    /** 첫 실행 보조 정보 안내(R-08)를 확인했는지. */
    val disclaimerAccepted: Boolean = false,
)

data class AnsimUi(
    val settings: AnsimSettings,
    val state: DrivingState = DrivingState.PARKED,
    val frame: Frame? = null,
    val captions: List<String> = emptyList(),
    /** 마지막 운행 요약 문장 (S6). */
    val lastSummary: String? = null,
    /** 오늘 지난 주의 구간 수. 개수만 저장한다(R-09). */
    val todayCount: Int = 0,
    val demoTitle: String? = null,
    val demoStep: Int = 0,
    val demoTotal: Int = 0,
    val speedGranted: Boolean = true,
    val micGranted: Boolean = false,
    /** 빨강 안내 직후 "왜?"를 듣는 중 (F-07). */
    val listeningWhy: Boolean = false,
)

class AnsimViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ansim", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(AnsimUi(settings = loadSettings(), todayCount = loadTodayCount()))
    val ui: StateFlow<AnsimUi> = _ui.asStateFlow()

    private var platform: Platform? = null
    private var session = newSession()
    private val jobs = mutableListOf<Job>()
    private var demoJob: Job? = null
    private var whyJob: Job? = null
    private val copy = AnsimCopy()

    init {
        startPlatform()
    }

    /** 권한을 새로 받았거나 모드를 바꾸면 처음부터 다시 연결한다. */
    fun startPlatform() {
        stopPlatform()
        val s = _ui.value.settings
        val mic = _ui.value.micGranted
        // 시연에서도 "왜?"는 실제 마이크로 듣는다. 권한이 없으면 버튼만 쓴다.
        val p = if (s.demo) {
            Platform.demo(getApplication(), listener = if (mic) PleosListener(getApplication()) else TypedListener())
        } else {
            Platform.pleos(getApplication())
        }
        platform = p
        p.start()
        jobs += viewModelScope.launch { p.speaker.lines.collect { lines -> _ui.update { it.copy(captions = lines) } } }
        if (!s.demo) jobs += viewModelScope.launch { followCar(p) }
    }

    @OptIn(FlowPreview::class)
    private suspend fun followCar(p: Platform) {
        combine(p.vehicle.reading, p.navi.state, p.location.fix) { r, n, f -> Triple(r, n, f) }
            .sample(LIVE_SAMPLE_MILLIS)
            .collect { (r, n, f) -> onLive(r, n, f) }
    }

    private fun onLive(reading: VehicleReading, navi: NaviState, fix: LocationFix?) {
        val state = RoadMapping.state(reading)
        val wasMoving = _ui.value.state.isRestricted
        if (!state.isRestricted) {
            if (wasMoving) endTrip()
            _ui.update { it.copy(state = state) }
            return
        }
        show(session.update(RoadMapping.snapshot(ZonedDateTime.now(KST), reading, navi, fix), state), state)
    }

    private fun show(frame: Frame, state: DrivingState) {
        _ui.update { it.copy(frame = frame, state = state) }
        frame.voiceLine?.let { announce(it) }
    }

    /** 빨강 안내를 읽고, 이어서 5초 동안 "왜?"를 듣는다 (F-07). 마이크 권한이 없으면 버튼만 쓴다. */
    private fun announce(line: String) {
        val p = platform ?: return
        whyJob?.cancel()
        whyJob = viewModelScope.launch {
            p.speaker.speak(line)
            if (!_ui.value.micGranted) return@launch
            _ui.update { it.copy(listeningWhy = true) }
            val heard = p.listener.listenOnce(WHY_LISTEN_MILLIS)
            _ui.update { it.copy(listeningWhy = false) }
            if (copy.isWhyQuestion(heard)) p.speaker.speak(session.why())
        }
    }

    private fun speak(line: String) {
        val p = platform ?: return
        viewModelScope.launch { p.speaker.speak(line) }
    }

    /** 시연 주행: 10초 장면을 [DEMO_STEP_MILLIS]마다 넘긴다. */
    fun playDemo(drive: DemoDrive) {
        demoJob?.cancel()
        session = newSession()
        demoJob = viewModelScope.launch {
            _ui.update { it.copy(demoTitle = "${drive.id} ${drive.title}", demoTotal = drive.frames.size, frame = null) }
            drive.frames.forEachIndexed { i, snapshot ->
                _ui.update { it.copy(demoStep = i + 1) }
                show(session.update(snapshot, DrivingState.DRIVING), DrivingState.DRIVING)
                delay(DEMO_STEP_MILLIS)
            }
            endTrip()
            _ui.update { it.copy(demoTitle = null, state = DrivingState.PARKED) }
        }
    }

    fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        endTrip()
        _ui.update { it.copy(demoTitle = null, state = DrivingState.PARKED) }
    }

    /** "왜?" (F-07): 점수 계산을 그대로 읽어준다. */
    fun askWhy() {
        val p = platform ?: return
        whyJob?.cancel()
        _ui.update { it.copy(listeningWhy = false) }
        // A-59: 음성을 끈 경우 자막으로만 보여준다.
        if (_ui.value.settings.voiceOn) speak(session.why()) else p.speaker.caption(session.why())
    }

    /** 액티비티가 권한 상태를 알려준다 (F-16). */
    fun setPermissions(speed: Boolean, mic: Boolean) = _ui.update { it.copy(speedGranted = speed, micGranted = mic) }

    private fun endTrip() {
        val passed = session.summary.passedCount
        val line = session.endTrip()
        val count = loadTodayCount() + passed
        prefs.edit().putString(KEY_COUNT_DATE, LocalDate.now(KST).toString()).putInt(KEY_COUNT, count).apply()
        _ui.update { it.copy(lastSummary = line, todayCount = count, frame = null) }
    }

    fun setVoice(on: Boolean) = updateSettings { it.copy(voiceOn = on) }
    fun setSensitivity(s: Sensitivity) = updateSettings { it.copy(sensitivity = s) }
    fun acceptDisclaimer() = updateSettings { it.copy(disclaimerAccepted = true) }

    fun setDemo(on: Boolean) {
        demoJob?.cancel()
        updateSettings { it.copy(demo = on) }
        startPlatform()
    }

    private fun updateSettings(f: (AnsimSettings) -> AnsimSettings) {
        val next = f(_ui.value.settings)
        prefs.edit()
            .putBoolean(KEY_VOICE, next.voiceOn)
            .putString(KEY_SENSITIVITY, next.sensitivity.name)
            .putBoolean(KEY_DEMO, next.demo)
            .putBoolean(KEY_DISCLAIMER, next.disclaimerAccepted)
            .apply()
        _ui.update { it.copy(settings = next) }
        session = newSession()
    }

    private fun newSession(): AnsimSession {
        val s = _ui.value.settings
        return AnsimSession(SignalJudge(sensitivity = s.sensitivity), SignalAlertPolicy(voiceEnabled = s.voiceOn))
    }

    private fun loadSettings() = AnsimSettings(
        voiceOn = prefs.getBoolean(KEY_VOICE, true),
        sensitivity = runCatching { Sensitivity.valueOf(prefs.getString(KEY_SENSITIVITY, null) ?: "") }.getOrDefault(Sensitivity.NORMAL),
        demo = prefs.getBoolean(KEY_DEMO, true),
        disclaimerAccepted = prefs.getBoolean(KEY_DISCLAIMER, false),
    )

    private fun loadTodayCount(): Int =
        if (prefs.getString(KEY_COUNT_DATE, null) == LocalDate.now(KST).toString()) prefs.getInt(KEY_COUNT, 0) else 0

    private fun stopPlatform() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        platform?.release()
        platform = null
    }

    override fun onCleared() {
        stopPlatform()
    }

    private companion object {
        const val LIVE_SAMPLE_MILLIS = 1_000L
        const val DEMO_STEP_MILLIS = 2_500L
        const val WHY_LISTEN_MILLIS = 5_000L
        const val KEY_VOICE = "voice"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_DEMO = "demo"
        const val KEY_DISCLAIMER = "disclaimer"
        const val KEY_COUNT = "today_count"
        const val KEY_COUNT_DATE = "today_count_date"
    }
}
