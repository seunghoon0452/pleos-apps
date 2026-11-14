package kr.mobility.mirichaeum.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.text.Korean
import kr.mobility.core.time.KST
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.core.vehicle.Gear
import kr.mobility.core.voice.ParseIssue
import kr.mobility.mirichaeum.AlertChannel
import kr.mobility.mirichaeum.AlertTrigger
import kr.mobility.mirichaeum.Answer
import kr.mobility.mirichaeum.CalendarView
import kr.mobility.mirichaeum.Charger
import kr.mobility.mirichaeum.ChargingStatus
import kr.mobility.mirichaeum.ColdStart
import kr.mobility.mirichaeum.Copywriter
import kr.mobility.mirichaeum.EnergySnapshot
import kr.mobility.mirichaeum.DistanceSource
import kr.mobility.mirichaeum.FuelStation
import kr.mobility.mirichaeum.FuelType
import kr.mobility.mirichaeum.GearEvent
import kr.mobility.mirichaeum.MiriChaeum
import kr.mobility.mirichaeum.MiriInput
import kr.mobility.mirichaeum.MiriResult
import kr.mobility.mirichaeum.OdometerReading
import kr.mobility.mirichaeum.PlaceCandidate
import kr.mobility.mirichaeum.PlaceRoles
import kr.mobility.mirichaeum.PlaceSearch
import kr.mobility.mirichaeum.RecordRetention
import kr.mobility.mirichaeum.PrefillRecord
import kr.mobility.mirichaeum.ReminderPlanner
import kr.mobility.mirichaeum.RoadDistance
import kr.mobility.mirichaeum.RouteCharger
import kr.mobility.mirichaeum.SavingsCalculator
import kr.mobility.mirichaeum.SavingsReport
import kr.mobility.mirichaeum.SentAlert
import kr.mobility.mirichaeum.Solution
import kr.mobility.mirichaeum.SolutionKind
import kr.mobility.mirichaeum.TripRecorder
import kr.mobility.mirichaeum.VoiceStep
import kr.mobility.mirichaeum.VoiceTrip
import kr.mobility.mirichaeum.VoiceTripBook
import kr.mobility.mirichaeum.VoiceTripRegistrar
import kr.mobility.mirichaeum.YesNo
import kr.mobility.mirichaeum.demo.DemoPersona
import kr.mobility.mirichaeum.demo.DemoPlaces
import kr.mobility.platform.pleos.FakeVehicleSignals
import kr.mobility.platform.pleos.FixedLocationSource
import kr.mobility.platform.pleos.LocationFix
import kr.mobility.platform.pleos.Platform
import kr.mobility.platform.pleos.TypedListener
import kr.mobility.platform.pleos.VehicleReading
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime

enum class Screen { HOME, SOLUTIONS, DETAIL, CALENDAR, REPORT, SETTINGS }

/** SC-13 일정 추가·수정 (F-23). 주차 중에만 연다. */
data class TripForm(
    /** 고치는 일정. 새 일정이면 null. */
    val editing: VoiceTrip?,
    val date: LocalDate,
    val time: LocalTime,
    val weekly: Boolean,
    val query: String,
    val candidates: List<PlaceCandidate> = emptyList(),
    val chosen: PlaceCandidate? = null,
    val searched: Boolean = false,
    val error: String? = null,
)

/** 시연 장면(F-15). 정의서 5장 시나리오와 같은 이름. */
enum class DemoScene(val title: String) {
    S1("S1 금요일 퇴근길 (전기차)"),
    S3("S3 월요일 아침 (충분)"),
    S4("S4 목요일 퇴근길 (내연기관)"),
}

sealed interface VoiceUi {
    data object Idle : VoiceUi
    data class Listening(val hint: String, val examples: List<String>) : VoiceUi
    data class Message(val text: String) : VoiceUi
}

data class MiriUi(
    val settings: MiriSettings,
    val screen: Screen = Screen.HOME,
    val state: DrivingState = DrivingState.PARKED,
    val result: MiriResult? = null,
    val snapshot: EnergySnapshot? = null,
    /** 판정을 못 할 때 이유("차량 정보를 읽는 중" 등). */
    val status: String? = null,
    val voice: VoiceUi = VoiceUi.Idle,
    val captions: List<String> = emptyList(),
    val voiceTrips: List<VoiceTrip> = emptyList(),
    val pickPlaceFor: Pair<VoiceTrip, List<PlaceCandidate>>? = null,
    val report: SavingsReport? = null,
    val reportLine: String? = null,
    val charging: ChargingStatus? = null,
    val scene: DemoScene? = null,
    val notice: String? = null,
    /** SC-04에서 보는 후보. */
    val detail: Solution? = null,
    /** '모두 지우기' 확인 창 (A-42). */
    val confirmClear: Boolean = false,
    /** SC-12 마이크 권한 안내 (A-34). */
    val micPrompt: Boolean = false,
    val speedGranted: Boolean = true,
    val micGranted: Boolean = true,
    /** SC-07 캘린더 (F-22). 판정할 때마다 다시 만든다. */
    val calendar: CalendarView? = null,
    val calendarMonth: YearMonth? = null,
    val selectedDate: LocalDate? = null,
    val form: TripForm? = null,
)

class MiriViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MiriStore(app)
    private val demoStore = MiriStore(app, "miri-demo")
    private val engine = MiriChaeum()
    private val copy = Copywriter()
    private val recorder = TripRecorder()
    private val savings = SavingsCalculator()

    private val _ui = MutableStateFlow(MiriUi(settings = store.settings))
    val ui: StateFlow<MiriUi> = _ui.asStateFlow()

    private var platform: Platform? = null
    private val jobs = mutableListOf<Job>()
    private var voiceJob: Job? = null

    // 시연 모드에서 쓰는 가짜 차량·위치·시계
    private val demoVehicle = FakeVehicleSignals()
    private val demoLocation = FixedLocationSource(null)
    private val typed = TypedListener()
    private var demoInput: MiriInput? = null
    private var clockOffset: Duration = Duration.ZERO

    // 캘린더(F-22)를 다시 그릴 때 쓰는 마지막 판정. init보다 먼저 선언해야 초기화 순서에 지워지지 않는다.
    private var lastInput: MiriInput? = null
    private var lastResult: MiriResult? = null

    // 실제 데이터 (키가 있을 때만). 없으면 시연 목록으로 찾고 충전소·주유소는 비어 있다.
    private val live = LiveData(BuildConfig.KAKAO_REST_KEY, BuildConfig.DATA_GO_KR_KEY, BuildConfig.OPINET_KEY)
    private var liveChargers: List<Charger> = emptyList()
    private var liveRouteChargers: List<RouteCharger> = emptyList()
    private var liveStations: List<FuelStation> = emptyList()
    private var liveFetchedAt: ZonedDateTime? = null
    private var liveFetchedNear: GeoPoint? = null
    private var liveFetching = false

    // 충전 시작 때 정한 목표. 충전이 진행되면 계획이 바뀌므로 고정한다.
    private var chargingTarget: Double? = null
    private var chargingAnnounced = false

    private val placeSearch: PlaceSearch
        get() = if (!_ui.value.settings.demo && live.hasPlaces) live.places else DemoPlaces

    private fun now(): ZonedDateTime = ZonedDateTime.now(KST).plus(clockOffset)
    private val activeStore: MiriStore get() = if (_ui.value.settings.demo) demoStore else store

    init {
        start()
    }

    /** 권한을 새로 받았거나 모드를 바꾸면 처음부터 다시 연결한다. */
    fun start() {
        stop()
        val s = _ui.value.settings
        val p = if (s.demo) {
            Platform.demo(getApplication(), vehicle = demoVehicle, location = demoLocation, listener = typed)
        } else {
            Platform.pleos(getApplication())
        }
        platform = p
        p.start()
        jobs += viewModelScope.launch { p.speaker.lines.collect { lines -> _ui.update { it.copy(captions = lines) } } }
        _ui.update { it.copy(voiceTrips = activeStore.voiceTrips, report = monthlyReport(), reportLine = copy.monthly(monthlyReport())) }
        if (s.demo) {
            selectScene(_ui.value.scene ?: DemoScene.S1)
        } else {
            clockOffset = Duration.ZERO
            jobs += viewModelScope.launch { followCar(p) }
            jobs += viewModelScope.launch {
                while (isActive) {
                    delay(60_000)
                    tickRecorder()
                    evaluate(trigger = null)
                }
            }
        }
    }

    // ---------- 실제 차량 ----------

    private suspend fun followCar(p: Platform) {
        var first = true
        p.vehicle.reading
            .distinctUntilChangedBy { listOf(it.gear, it.charging, it.socPercent?.toInt(), it.fuelPercent?.toInt(), (it.speedKmh ?: 0.0) > 1.0) }
            .collect { r ->
                val previous = _ui.value.state
                val state = DrivingState.from(r.gear, r.speedKmh ?: 0.0, r.parkingBrake == true, r.charging == true)
                _ui.update { it.copy(state = state) }
                if (previous.isRestricted != state.isRestricted) closePopups()
                recordGear(r, wasParked = !previous.isRestricted, nowParked = !state.isRestricted)
                if (first && r.odometerKm != null) {
                    store.odometer = store.odometer + OdometerReading(now(), r.odometerKm!!)
                }
                // 시동을 켜고 아직 주차 상태일 때 = 운행 시작 판정 (R-10 ①)
                val trigger = if (first && state == DrivingState.PARKED) AlertTrigger.TRIP_START else null
                if (snapshotFrom(r) != null) first = false
                evaluate(trigger)
                if (previous.isRestricted && state == DrivingState.PARKED) {
                    p.location.fix.value?.let { arrived(PlaceCell.of(GeoPoint(it.lat, it.lng))) }
                }
            }
    }

    private fun recordGear(r: VehicleReading, wasParked: Boolean, nowParked: Boolean) {
        if (!_ui.value.settings.recording || wasParked == nowParked || _ui.value.settings.demo) return
        val fix = platform?.location?.fix?.value
        val event = GearEvent(now(), nowParked, fix?.let { GeoPoint(it.lat, it.lng) }, r.odometerKm, r.outsideTempC)
        val out = recorder.handle(store.recorder, event)
        store.recorder = out.state
        saveRecords(out.trips, out.dwells)
    }

    /** 새 기록을 더하면서 보관 기간(A-44)이 지난 것을 버린다. */
    private fun saveRecords(trips: List<kr.mobility.mirichaeum.TripRecord>, dwells: List<kr.mobility.mirichaeum.DwellRecord>) {
        val cutoff = RecordRetention.cutoffLogs(now())
        if (trips.isNotEmpty()) store.trips = (store.trips + trips).filter { it.end.isAfter(cutoff) }
        if (dwells.isNotEmpty()) store.dwells = (store.dwells + dwells).filter { it.depart.isAfter(cutoff) }
    }

    private fun tickRecorder() {
        if (_ui.value.settings.demo) return
        val out = recorder.tick(store.recorder, now())
        store.recorder = out.state
        saveRecords(out.trips, emptyList())
    }

    /** 차량 값 → 엔진 입력. 모르는 값이 많으면 null(판정 보류). */
    private fun snapshotFrom(r: VehicleReading): EnergySnapshot? {
        val at = now()
        if (r.isEv) {
            val soc = r.socPercent ?: return null
            val range = r.evRangeKm ?: return null
            // 전비·용량을 모르면 가안 값(전비 5.0km/kWh, 용량 = 주행가능거리로 역산)
            val eff = r.evEfficiency ?: DEFAULT_EV_EFFICIENCY
            val capacity = r.batteryCapacityKwh ?: if (soc > 0) range / eff / (soc / 100.0) else return null
            return EnergySnapshot(FuelType.EV, range, soc.coerceIn(0.0, 100.0), eff, capacity, r.outsideTempC, at, r.odometerKm)
        }
        val fuel = r.fuelPercent ?: return null
        val economy = r.fuelEconomy?.takeIf { it > 0 } ?: DEFAULT_ICE_ECONOMY
        val tank = _ui.value.settings.tankLiters
        val range = r.evRangeKm ?: (tank * fuel / 100.0 * economy)
        return EnergySnapshot(FuelType.ICE, range, fuel.coerceIn(0.0, 100.0), economy, tank, r.outsideTempC, at, r.odometerKm)
    }

    private fun liveInput(trigger: AlertTrigger?): MiriInput? {
        val p = platform ?: return null
        val r = p.vehicle.reading.value
        val snapshot = snapshotFrom(r) ?: return null
        val fix = p.location.fix.value
        return MiriInput(
            now = now(),
            state = _ui.value.state,
            snapshot = snapshot,
            trips = store.trips,
            dwells = store.dwells,
            voiceTrips = store.voiceTrips,
            currentPlace = fix?.let { PlaceCell.of(GeoPoint(it.lat, it.lng)) },
            userDailyKm = _ui.value.settings.dailyKm,
            odometerReadings = store.odometer,
            sentAlerts = store.sentAlerts,
            trigger = trigger,
            homeCharging = _ui.value.settings.homeCharging,
            skipDays = skipDays(store),
            nearbyChargers = liveChargers,
            routeChargers = liveRouteChargers,
            stations = liveStations,
        )
    }

    // ---------- 시연 모드 ----------

    fun selectScene(scene: DemoScene) {
        val input = when (scene) {
            DemoScene.S1 -> DemoPersona.s1FridayEvening()
            DemoScene.S3 -> DemoPersona.s3MondayMorning()
            DemoScene.S4 -> DemoPersona.s4IceThursday()
        }
        stopVoice()
        chargingTarget = null
        chargingAnnounced = false
        demoInput = input
        clockOffset = Duration.between(ZonedDateTime.now(KST), input.now)
        demoStore.sentAlerts = emptyList()
        // A-86: 장면마다 시연 캘린더를 처음 상태(예시 일정 2개)로 돌린다.
        demoStore.voiceTrips = DemoPersona.calendarSeeds()
        demoStore.skipDays = emptySet()
        val s = input.snapshot
        demoVehicle.set {
            VehicleReading(
                gear = Gear.PARK,
                speedKmh = 0.0,
                socPercent = s.levelPercent.takeIf { s.fuelType == FuelType.EV },
                evRangeKm = s.rangeKm,
                evEfficiency = s.efficiency.takeIf { s.fuelType == FuelType.EV },
                batteryCapacityKwh = s.capacity.takeIf { s.fuelType == FuelType.EV },
                fuelPercent = s.levelPercent.takeIf { s.fuelType == FuelType.ICE },
                fuelEconomy = s.efficiency.takeIf { s.fuelType == FuelType.ICE },
                outsideTempC = s.outsideTempC,
            )
        }
        val here = if (input.currentPlace == DemoPersona.home) DemoPersona.homePoint else DemoPersona.workPoint
        demoLocation.set(LocationFix(here.lat, here.lng, null, System.currentTimeMillis()))
        _ui.update {
            it.copy(
                scene = scene, state = DrivingState.PARKED, charging = null, screen = Screen.HOME, voiceTrips = demoStore.voiceTrips,
                calendarMonth = null, selectedDate = null, form = null,
            )
        }
        evaluate(AlertTrigger.TRIP_START)
    }

    /** 시연: 출발·주차·충전 시작을 흉내 낸다. 주행하다 주차하면 체류 후보 장소에 도착한 것으로 본다(S1 4단계). */
    fun demoSetState(state: DrivingState) {
        val wasMoving = _ui.value.state.isRestricted
        closePopups()
        demoVehicle.set {
            when (state) {
                DrivingState.DRIVING -> it.copy(gear = Gear.DRIVE, speedKmh = 40.0, charging = false)
                DrivingState.CHARGING -> it.copy(gear = Gear.PARK, speedKmh = 0.0, charging = true)
                else -> it.copy(gear = Gear.PARK, speedKmh = 0.0, charging = false)
            }
        }
        _ui.update { it.copy(state = state) }
        evaluate(trigger = null)
        if (wasMoving && state == DrivingState.PARKED) {
            _ui.value.result?.plan?.solutions?.firstOrNull { it.kind == SolutionKind.DWELL }?.dwell?.place?.let { arrived(it) }
        }
    }

    /** 시연: 충전을 목표까지 건너뛴다. 값이 바뀌면 목표 도달 안내가 나온다. */
    fun demoChargeToTarget() {
        val base = demoInput ?: return
        val target = chargingTarget ?: return
        val s = base.snapshot
        demoInput = base.copy(snapshot = s.copy(rangeKm = s.rangeKm / s.levelPercent * target, levelPercent = target))
        evaluate(trigger = null)
    }

    private fun demoInputNow(trigger: AlertTrigger?): MiriInput? {
        val base = demoInput ?: return null
        return base.copy(
            now = now(),
            state = _ui.value.state,
            voiceTrips = demoStore.voiceTrips,
            sentAlerts = demoStore.sentAlerts,
            trigger = trigger,
            homeCharging = _ui.value.settings.homeCharging,
            skipDays = skipDays(demoStore),
        ).let { input ->
            if (input.snapshot.fuelType == FuelType.ICE) {
                input.copy(snapshot = input.snapshot.copy(capacity = _ui.value.settings.tankLiters))
            } else {
                input
            }
        }
    }

    // ---------- 판정 ----------

    private fun evaluate(trigger: AlertTrigger?) {
        val demo = _ui.value.settings.demo
        val input = (if (demo) demoInputNow(trigger) else liveInput(trigger))
        if (input == null) {
            _ui.update { it.copy(status = "차량 정보를 읽는 중이에요", result = null) }
            return
        }
        val result = engine.evaluate(input)
        val s = activeStore
        if (result.voiceTrips.size != input.voiceTrips.size) s.voiceTrips = result.voiceTrips
        val charging = if (_ui.value.state == DrivingState.CHARGING) {
            val power = if (demo) DemoPersona.martCharger.powerKw else DEFAULT_CHARGER_KW
            engine.chargingStatus(input.snapshot, result, power, input.now, fixedTarget = chargingTarget, powerKnown = demo).also { st ->
                chargingTarget = st.targetPercent
                if (st.reached && !chargingAnnounced) {
                    chargingAnnounced = true
                    speak(st.line)
                }
            }
        } else {
            chargingTarget = null
            chargingAnnounced = false
            null
        }
        lastInput = input
        lastResult = result
        _ui.update {
            it.copy(
                result = result, snapshot = input.snapshot, status = null, charging = charging,
                voiceTrips = VoiceTripBook.upcoming(result.voiceTrips, input.now),
            )
        }
        refreshCalendar()
        val alert = result.alert
        val line = result.voiceLine
        if (alert?.channel == AlertChannel.VOICE && line != null && _ui.value.settings.onboarded) {
            s.sentAlerts = s.sentAlerts + SentAlert(input.now, result.alertKey)
            announce(line, result.askingSolution)
        }
        if (!demo && result.judgement.verdict != kr.mobility.mirichaeum.Verdict.ENOUGH) refreshLive(input)
    }

    /** 노랑·빨강 안내를 읽고, 물을 해결책이 있으면 15초 동안 "응"을 듣는다 (F-10, R-13). */
    private fun announce(line: String, asking: Solution?) {
        val p = platform ?: return
        stopVoice()
        voiceJob = viewModelScope.launch {
            p.speaker.speak(line)
            val s = _ui.value
            if (asking == null || !(s.settings.demo || s.micGranted)) return@launch
            val prompt = if (asking.kind == SolutionKind.DWELL) "그곳으로 안내할까요?" else "경유지로 넣을까요?"
            _ui.update { it.copy(voice = VoiceUi.Listening(prompt, if (s.settings.demo) listOf("응", "아니") else emptyList())) }
            val answer = p.listener.listenOnce(ANSWER_MILLIS)?.let(YesNo::classify)
            // 대답이 없거나 아니면 아무 동작도 하지 않고 화면 카드만 남긴다.
            _ui.update { it.copy(voice = VoiceUi.Idle) }
            if (answer == Answer.YES) acceptSolution(asking)
        }
    }

    /** 체류 충전 후보 장소에 도착하면 한 번 더 알려준다 (R-10 ②). */
    private fun arrived(place: PlaceCell) {
        val result = _ui.value.result ?: return
        val store = activeStore
        val (decision, line) = engine.onArrival(result, place, DrivingState.PARKED, now(), store.sentAlerts) ?: return
        if (decision.channel != AlertChannel.VOICE) return
        store.sentAlerts = store.sentAlerts + SentAlert(now(), MiriChaeum.arrivalKey(place))
        speak(line)
    }

    private fun speak(line: String) {
        val p = platform ?: return
        viewModelScope.launch { p.speaker.speak(line) }
    }

    private fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
        typed.submit(null)
        platform?.speaker?.stop()
        _ui.update { it.copy(voice = VoiceUi.Idle) }
    }

    /** 상황이 바뀌면 터치로 답해야 하는 창을 닫는다. 진행 중인 음성 대화는 이어간다. */
    private fun closePopups() = _ui.update { it.copy(pickPlaceFor = null, confirmClear = false, micPrompt = false, form = null) }

    // ---------- 실제 충전소·주유소 ----------

    /** 판정이 노랑·빨강일 때만, 10분이 지났거나 3km 넘게 움직였으면 다시 찾는다. */
    private fun refreshLive(input: MiriInput) {
        val fix = platform?.location?.fix?.value ?: return
        val here = GeoPoint(fix.lat, fix.lng)
        val isEv = input.snapshot.fuelType == FuelType.EV
        if (liveFetching || !(if (isEv) live.hasChargers else live.hasStations)) return
        val fresh = liveFetchedAt?.let { Duration.between(it, input.now) < LIVE_REFRESH } == true
        val near = liveFetchedNear?.let { kr.mobility.core.geo.Geo.distanceMeters(it, here) < LIVE_MOVE_METERS } == true
        if (fresh && near) return
        liveFetching = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (isEv) {
                    val (all, route) = live.chargers(here, input.now)
                    liveChargers = all
                    liveRouteChargers = route
                } else {
                    liveStations = live.stations(here)
                }
            }
            liveFetchedAt = input.now
            liveFetchedNear = here
            liveFetching = false
            evaluate(trigger = null)
        }
    }

    // ---------- 해결책 실행 (R-13: "응" 또는 주차 중 버튼) ----------

    fun acceptSolution(solution: Solution) {
        if (solution.kind == SolutionKind.HOME) return
        val p = platform ?: return
        if (!p.navi.guideTo(solution.location.lat, solution.location.lng, solution.placeName)) {
            // A-41: 넣지 못했으면 기록도 남기지 않는다.
            val failed = "내비에 넣지 못했어요. 차를 세운 뒤 다시 해 주세요."
            _ui.update { it.copy(notice = failed) }
            speak(failed)
            return
        }
        val record = PrefillRecord(now(), solution.kind, solution.amount, solution.unitPrice, solution.extraMinutes)
        val cutoff = RecordRetention.cutoffPrefills(now())
        activeStore.prefills = (activeStore.prefills + record).filter { it.at.isAfter(cutoff) }
        val report = monthlyReport()
        val line = if (solution.kind == SolutionKind.DWELL) {
            "${Korean.euroRo(solution.placeName)} 안내할게요."
        } else {
            "${Korean.eulReul(solution.placeName)} 경유지로 넣었어요."
        }
        _ui.update { it.copy(notice = line, report = report, reportLine = copy.monthly(report)) }
        speak(line)
    }

    // ---------- 음성 일정 (F-04) ----------

    fun startVoice() {
        val s = _ui.value
        if (!s.settings.demo && !s.micGranted) {
            _ui.update { it.copy(micPrompt = true) }
            return
        }
        stopVoice()
        val p = platform ?: return
        voiceJob = viewModelScope.launch {
            val examples = if (_ui.value.settings.demo) VOICE_EXAMPLES else emptyList()
            _ui.update { it.copy(voice = VoiceUi.Listening("평소와 다른 이동 일정을 말해 주세요", examples)) }
            val text = p.listener.listenOnce(LISTEN_MILLIS)
            if (text == null) {
                showMessage("잘 듣지 못했어요. 다시 말해 주세요.")
                return@launch
            }
            val origin = p.location.fix.value?.let { GeoPoint(it.lat, it.lng) }
            val registrar = VoiceTripRegistrar(placeSearch, road = naviDistance())
            handleStep(withContext(Dispatchers.IO) { registrar.interpret(text, now(), origin) }, text, registrar, origin, canAskAgain = true)
        }
    }

    private suspend fun handleStep(step: VoiceStep, text: String, registrar: VoiceTripRegistrar, origin: GeoPoint?, canAskAgain: Boolean) {
        val p = platform ?: return
        when (step) {
            is VoiceStep.AskMissing -> {
                // A-33: 빠진 항목은 한 번만 되묻는다.
                val answer = if (canAskAgain) {
                    p.speaker.speak(step.prompt)
                    _ui.update { it.copy(voice = VoiceUi.Listening(step.prompt, followUpExamples(step.missing))) }
                    p.listener.listenOnce(LISTEN_MILLIS)
                } else {
                    null
                }
                if (answer == null) {
                    speakMessage("등록하지 않았어요. 다시 말해 주세요.")
                } else {
                    val next = withContext(Dispatchers.IO) { registrar.interpretFollowUp(text, answer, now(), origin) }
                    handleStep(next, "$text $answer", registrar, origin, canAskAgain = false)
                }
            }
            is VoiceStep.PickPlaceLater -> {
                saveTrip(step.draft)
                speakMessage(step.prompt)
            }
            is VoiceStep.Confirm -> confirm(step.draft, step.prompt)
        }
    }

    private fun followUpExamples(missing: ParseIssue): List<String> = if (!_ui.value.settings.demo) emptyList() else when (missing) {
        ParseIssue.NO_DATE -> listOf("내일", "다음 주 화요일")
        ParseIssue.NO_TIME -> listOf("오후 3시", "아침 9시")
        else -> listOf("대전", "부산")
    }

    private suspend fun confirm(draft: VoiceTrip, prompt: String) {
        val p = platform ?: return
        p.speaker.speak(prompt)
        val examples = if (_ui.value.settings.demo) listOf("응", "아니") else emptyList()
        _ui.update { it.copy(voice = VoiceUi.Listening(prompt, examples)) }
        val answer = p.listener.listenOnce(ANSWER_MILLIS)?.let(YesNo::classify) ?: Answer.UNKNOWN
        if (answer != Answer.YES) {
            speakMessage(if (answer == Answer.NO) "등록하지 않았어요. 다시 말해 주세요." else "대답을 듣지 못해서 등록하지 않았어요.")
            return
        }
        saveTrip(draft)
        val result = _ui.value.result
        val roles = PlaceRoles.infer(if (_ui.value.settings.demo) demoInput?.dwells.orEmpty() else store.dwells)
        val reminder = result?.let { ReminderPlanner.plan(draft, it.patterns, roles) }
        evaluate(trigger = null)
        speakMessage(copy.voiceTripRegistered(draft, reminder, _ui.value.result?.tripTargets?.get(draft.id)))
    }

    private suspend fun speakMessage(line: String) {
        val p = platform ?: return
        showMessage(line)
        p.speaker.speak(line)
        autoCloseWhileDriving()
    }

    private suspend fun showMessage(line: String) {
        _ui.update { it.copy(voice = VoiceUi.Message(line)) }
        autoCloseWhileDriving()
    }

    /** A-45: 주행 중에는 '확인' 버튼이 없으므로 잠시 뒤 저절로 닫는다. */
    private suspend fun autoCloseWhileDriving() {
        if (!_ui.value.state.isRestricted) return
        delay(MESSAGE_MILLIS)
        _ui.update { it.copy(voice = VoiceUi.Idle) }
    }

    /** 시연 모드에서 마이크 대신 화면 버튼으로 문장을 넣는다. */
    fun submitTyped(text: String) = typed.submit(text)

    fun dismissVoice() = stopVoice()

    /** R-06 ①: 내비 경로 거리. 답이 없으면 호출한 쪽이 직선 × 1.3을 쓴다. IO 스레드에서 불린다. */
    private fun naviDistance() = RoadDistance { _, to ->
        val p = platform
        p?.let { runBlocking { it.navi.estimateTrip(to.lat, to.lng, "목적지") } }?.let { it.distanceMeters / 1000.0 to DistanceSource.NAVI }
    }

    private fun saveTrip(trip: VoiceTrip) {
        val s = activeStore
        s.voiceTrips = VoiceTripBook.upsert(VoiceTripBook.prune(s.voiceTrips, now()), trip)
        _ui.update { it.copy(voiceTrips = VoiceTripBook.upcoming(s.voiceTrips, now())) }
    }

    fun deleteTrip(id: String) {
        val s = activeStore
        s.voiceTrips = VoiceTripBook.remove(s.voiceTrips, id)
        _ui.update { it.copy(voiceTrips = VoiceTripBook.upcoming(s.voiceTrips, now())) }
        evaluate(trigger = null)
    }

    // ---------- 캘린더 (F-22~F-25) ----------

    /** 지난 날짜는 저장해 두지 않는다. */
    private fun skipDays(s: MiriStore): Set<LocalDate> {
        val today = now().toLocalDate()
        return s.skipDays.filterTo(mutableSetOf()) { !it.isBefore(today) }
    }

    /** 보이는 달을 일요일부터 6주(42칸)로 그린다. */
    private fun refreshCalendar() {
        val input = lastInput ?: return
        val result = lastResult ?: return
        val month = _ui.value.calendarMonth ?: YearMonth.from(input.now)
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val view = engine.calendar(input, result, start, start.plusDays(CALENDAR_CELLS - 1))
        _ui.update { it.copy(calendar = view, calendarMonth = month, selectedDate = it.selectedDate ?: input.now.toLocalDate()) }
    }

    fun shiftMonth(delta: Long) {
        _ui.update { s -> s.copy(calendarMonth = (s.calendarMonth ?: YearMonth.from(now())).plusMonths(delta)) }
        refreshCalendar()
    }

    fun calendarToday() {
        val today = now().toLocalDate()
        _ui.update { it.copy(calendarMonth = YearMonth.from(today), selectedDate = today) }
        refreshCalendar()
    }

    fun selectDate(date: LocalDate) {
        val month = YearMonth.from(date)
        val moved = month != _ui.value.calendarMonth
        _ui.update { it.copy(selectedDate = date, calendarMonth = month) }
        if (moved) refreshCalendar()
    }

    /** F-25: 이날 평소 이동을 예측에서 빼거나 되돌린다. */
    fun toggleSkipDay(date: LocalDate) {
        val s = activeStore
        val current = skipDays(s)
        s.skipDays = if (date in current) current - date else current + date
        evaluate(trigger = null)
    }

    /** F-23: 터치로 새 일정. 주차 중에만 (주행 제한). */
    fun openNewTrip(date: LocalDate) {
        if (_ui.value.state.isRestricted) return
        _ui.update { it.copy(form = TripForm(editing = null, date = date, time = DEFAULT_FORM_TIME, weekly = false, query = ""), notice = null) }
    }

    fun openEditTrip(trip: VoiceTrip) {
        if (_ui.value.state.isRestricted) return
        val place = trip.destination?.let { PlaceCandidate(trip.label, it) }
        val date = trip.nextAt(now())?.toLocalDate() ?: trip.at.toLocalDate()
        _ui.update {
            it.copy(
                form = TripForm(trip, date, trip.at.toLocalTime(), trip.weekly, trip.placeQuery, listOfNotNull(place), place),
                notice = null,
            )
        }
    }

    private fun updateForm(f: (TripForm) -> TripForm) = _ui.update { s -> s.form?.let { s.copy(form = f(it).copy(error = null)) } ?: s }

    fun formShiftDate(days: Long) = updateForm { it.copy(date = it.date.plusDays(days)) }
    fun formShiftTime(minutes: Long) = updateForm { it.copy(time = it.time.plusMinutes(minutes)) }
    fun formSetWeekly(weekly: Boolean) = updateForm { it.copy(weekly = weekly) }
    fun formSetQuery(text: String) = updateForm { it.copy(query = text) }
    fun formChoose(place: PlaceCandidate) = updateForm { it.copy(chosen = place) }
    fun cancelForm() = _ui.update { it.copy(form = null) }

    /** 장소 검색. 후보가 하나면 바로 고른다. */
    fun formSearch(query: String? = null) {
        val f = _ui.value.form ?: return
        val q = (query ?: f.query).trim()
        if (q.isEmpty()) return
        val near = platform?.location?.fix?.value?.let { GeoPoint(it.lat, it.lng) }
        val search = placeSearch
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { search.search(q, near) }.take(MAX_CANDIDATES)
            updateForm { it.copy(query = q, candidates = found, chosen = found.singleOrNull(), searched = true) }
        }
    }

    fun saveForm() {
        val f = _ui.value.form ?: return
        val place = f.chosen ?: return setFormError("장소를 골라 주세요")
        val at = f.date.atTime(f.time).atZone(KST)
        // 한 번짜리 일정은 지난 시각으로 넣지 않는다. 매주 일정은 다음 주부터 들어간다.
        if (!f.weekly && !at.isAfter(now())) return setFormError("지난 시각이에요. 날짜나 시각을 바꿔 주세요")
        val p = platform ?: return
        viewModelScope.launch {
            val origin = p.location.fix.value?.let { GeoPoint(it.lat, it.lng) }
            val registrar = VoiceTripRegistrar(placeSearch, road = naviDistance())
            val trip = withContext(Dispatchers.IO) { registrar.fromForm(at, place, f.query, f.weekly, origin, f.editing) }
            saveTrip(trip)
            _ui.update { it.copy(form = null, selectedDate = f.date, calendarMonth = YearMonth.from(f.date)) }
            evaluate(trigger = null)
            val roles = PlaceRoles.infer(if (_ui.value.settings.demo) demoInput?.dwells.orEmpty() else store.dwells)
            val reminder = _ui.value.result?.let { ReminderPlanner.plan(trip, it.patterns, roles) }
            val line = copy.voiceTripRegistered(trip, reminder, _ui.value.result?.tripTargets?.get(trip.id))
            _ui.update { it.copy(notice = if (f.editing != null) line.replaceFirst("등록했어요", "고쳤어요") else line) }
        }
    }

    fun deleteFromForm() {
        val trip = _ui.value.form?.editing ?: return
        _ui.update { it.copy(form = null, notice = "일정을 지웠어요") }
        deleteTrip(trip.id)
    }

    private fun setFormError(message: String) = _ui.update { s -> s.form?.let { s.copy(form = it.copy(error = message)) } ?: s }

    /** 장소를 정차 후 고르기 (S2-4). 내비 경로 거리를 먼저 묻는다(R-06 ①). */
    fun startPickPlace(trip: VoiceTrip) {
        val search = placeSearch
        val near = platform?.location?.fix?.value?.let { GeoPoint(it.lat, it.lng) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { search.search(trip.placeQuery, near) }
            _ui.update { it.copy(pickPlaceFor = trip to found) }
        }
    }

    fun pickPlace(trip: VoiceTrip, place: PlaceCandidate) {
        val p = platform ?: return
        viewModelScope.launch {
            val origin = p.location.fix.value?.let { GeoPoint(it.lat, it.lng) }
            val estimate = p.navi.estimateTrip(place.location.lat, place.location.lng, place.name)
            val road = RoadDistance { _, _ -> estimate?.let { it.distanceMeters / 1000.0 to DistanceSource.NAVI } }
            saveTrip(VoiceTripRegistrar(placeSearch, road).withPlace(trip, place, origin))
            _ui.update { it.copy(pickPlaceFor = null) }
            evaluate(trigger = null)
        }
    }

    fun cancelPickPlace() = _ui.update { it.copy(pickPlaceFor = null) }

    // ---------- 리포트·설정 ----------

    private fun monthlyReport(): SavingsReport {
        val month = YearMonth.from(now())
        val records = if (_ui.value.settings.demo) DemoPersona.prefillRecords(month) + demoStore.prefills else store.prefills
        return savings.monthly(records, month)
    }

    fun go(screen: Screen) = _ui.update { it.copy(screen = screen, notice = null) }
    fun openDetail(solution: Solution) = _ui.update { it.copy(screen = Screen.DETAIL, detail = solution, notice = null) }
    fun clearNotice() = _ui.update { it.copy(notice = null) }

    fun finishOnboarding(dailyKm: Double?) {
        updateSettings { it.copy(onboarded = true, dailyKm = dailyKm ?: ColdStart.DEFAULT_DAILY_KM) }
        // 시작 화면 동안 미룬 운행 시작 안내 (R-10 ①)
        evaluate(AlertTrigger.TRIP_START)
    }
    fun setHomeCharging(on: Boolean) {
        updateSettings { it.copy(homeCharging = on) }
        evaluate(trigger = null)
    }

    /** A-37: 탱크 용량 30~80L, 5L 단위. */
    fun changeTank(deltaLiters: Double) {
        updateSettings { it.copy(tankLiters = (it.tankLiters + deltaLiters).coerceIn(30.0, 80.0)) }
        evaluate(trigger = null)
    }

    /** 액티비티가 권한 상태를 알려준다 (F-21). */
    fun setPermissions(speed: Boolean, mic: Boolean) = _ui.update { it.copy(speedGranted = speed, micGranted = mic, micPrompt = it.micPrompt && !mic) }
    fun closeMicPrompt() = _ui.update { it.copy(micPrompt = false) }
    fun askClearRecords() = _ui.update { it.copy(confirmClear = true) }
    fun cancelClearRecords() = _ui.update { it.copy(confirmClear = false) }
    fun setRecording(on: Boolean) = updateSettings { it.copy(recording = on) }

    fun setDemo(on: Boolean) {
        updateSettings { it.copy(demo = on) }
        start()
    }

    fun clearRecords() {
        activeStore.clearRecords()
        _ui.update { it.copy(voiceTrips = emptyList(), notice = "기록을 모두 지웠어요", confirmClear = false) }
        evaluate(trigger = null)
    }

    private fun updateSettings(f: (MiriSettings) -> MiriSettings) {
        val next = f(_ui.value.settings)
        store.settings = next
        _ui.update { it.copy(settings = next) }
    }

    private fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        voiceJob?.cancel()
        platform?.release()
        platform = null
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val DEFAULT_EV_EFFICIENCY = 5.0
        const val DEFAULT_ICE_ECONOMY = 12.0
        const val DEFAULT_CHARGER_KW = 7.0
        const val LISTEN_MILLIS = 12_000L
        const val ANSWER_MILLIS = 15_000L
        const val MESSAGE_MILLIS = 5_000L
        const val LIVE_MOVE_METERS = 3_000.0
        const val CALENDAR_CELLS = 42L
        const val MAX_CANDIDATES = 5
        val DEFAULT_FORM_TIME: LocalTime = LocalTime.of(9, 0)
        val LIVE_REFRESH: Duration = Duration.ofMinutes(10)
        val VOICE_EXAMPLES = listOf(
            "다음 주 화요일 오후 3시에 대전 출장 있어",
            "내일 대전 출장",
            "내일 오후 2시 카페 약속",
            "모레 아침 9시 인천공항",
            "매주 목요일 오후 7시 수영장",
        )
    }
}
