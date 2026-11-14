package kr.mobility.mirichaeum.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mobility.core.text.Korean
import kr.mobility.core.ui.CaptionBar
import kr.mobility.core.ui.CarButton
import kr.mobility.core.ui.CarColors
import kr.mobility.core.ui.CarDimens
import kr.mobility.core.ui.CarTheme
import kr.mobility.core.ui.DrivingLine
import kr.mobility.core.ui.InfoRow
import kr.mobility.core.ui.MicButton
import kr.mobility.core.ui.Panel
import kr.mobility.core.ui.Tone
import kr.mobility.core.ui.TopBar
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.mirichaeum.Copywriter
import kr.mobility.mirichaeum.FuelType
import kr.mobility.mirichaeum.GuideSource
import kr.mobility.mirichaeum.Solution
import kr.mobility.mirichaeum.SolutionKind
import kr.mobility.mirichaeum.TripGuide
import kr.mobility.mirichaeum.Verdict
import kr.mobility.mirichaeum.VoiceTrip
import java.time.ZonedDateTime
import kotlin.math.roundToInt

private val copy = Copywriter()
private const val STALE_STATUS_MINUTES = 10

internal fun Verdict?.tone(): Tone = when (this) {
    Verdict.ENOUGH -> Tone.GOOD
    Verdict.TIGHT -> Tone.WARN
    Verdict.SHORT -> Tone.DANGER
    null -> Tone.NEUTRAL
}

private fun SolutionKind.label(): String = when (this) {
    SolutionKind.DWELL -> "어차피 머무는 곳"
    SolutionKind.ON_ROUTE -> "가는 길"
    SolutionKind.DETOUR -> "잠깐 들르기"
    SolutionKind.HOME -> "오늘 밤 집에서"
}

private fun Solution.actionLabel(): String = if (kind == SolutionKind.DWELL) "그곳으로 안내" else "경유지로 넣기"

private fun Solution.amountText(fuel: FuelType?, percent: Double?): String = if (fuel == FuelType.ICE) {
    "${amount.roundToInt()}L"
} else {
    "${percent?.roundToInt() ?: 0}% · 약 ${"%.1f".format(amount)}kWh"
}

private fun VoiceTrip.line(target: TripGuide?): String {
    val km = roundTripKm?.let { " · 왕복 약 ${copy.approxKm(it)}" } ?: " · 장소를 골라주세요"
    val day = if (weekly) "매주 ${Korean.dayName(at.dayOfWeek)}" else Korean.spokenDate(at.toLocalDate())
    val need = target?.let { if (it.recommended.overFull) " · 가는 길 충전 필요" else " · 최소 ${copy.levelText(it.minimum)} 권장 ${copy.levelText(it.recommended)}" } ?: ""
    return "$day ${Korean.spokenTime(at.toLocalTime())} · $label$km$need"
}

private fun GuideSource.word(): String = when (this) {
    GuideSource.ROUTINE -> "평소 이동"
    GuideSource.PLANNED -> "캘린더 일정"
    GuideSource.ESTIMATE -> "하루 평균"
}

/** "토요일 오전 10시 · 양평 · 왕복 약 120km · 평소 이동" */
private fun TripGuide.header(): String =
    "${Korean.spokenDate(at.toLocalDate())} ${Korean.spokenTime(at.toLocalTime())} · $title · 왕복 약 ${copy.approxKm(roundTripKm)} · ${source.word()}"

private fun Verdict.statusWord(): String = when (this) {
    Verdict.ENOUGH -> "충분"
    Verdict.TIGHT -> "권장 미만"
    Verdict.SHORT -> "최소 미만"
}

/** 최소·권장 기준 설명 (R-08). */
private fun basisText(fuel: FuelType): String = if (fuel == FuelType.ICE) {
    "최소: 다녀와서 30km를 더 갈 수 있는 양 · 권장: 15% 여유를 더하고 50km가 남는 양"
} else {
    "최소: 다녀와서 10%가 남는 양 · 권장: 15% 여유를 더하고 15%가 남는 양 · 시간은 급속 100kW, 완속 7kW 기준"
}

/** SC-02 홈의 일정별 충전 안내 (F-26). */
@Composable
private fun GuideBlock(g: TripGuide, correctedRangeKm: Double, others: List<TripGuide>, now: ZonedDateTime) {
    InfoRow("일정", g.header())
    InfoRow("지금 잔량", "${g.currentPercent.roundToInt()}% · 갈 수 있는 거리 ${Korean.km(correctedRangeKm)}")
    InfoRow(
        "최소 충전량 ${copy.levelText(g.minimum)}",
        copy.targetDetail(g, g.minimum),
        valueColor = if (g.minimum.reached) CarColors.green else CarColors.red,
    )
    InfoRow(
        "권장 충전량 ${copy.levelText(g.recommended)}",
        copy.targetDetail(g, g.recommended),
        valueColor = if (g.recommended.reached) CarColors.green else CarColors.yellow,
    )
    others.take(3).forEach { o ->
        Text(
            "${copy.guideWhen(o, now)} · 왕복 약 ${copy.approxKm(o.roundTripKm)} · 최소 ${copy.levelText(o.minimum)} 권장 ${copy.levelText(o.recommended)} · ${o.verdict.statusWord()}",
            style = MaterialTheme.typography.bodyLarge,
            color = o.verdict.tone().color,
        )
    }
    Text(basisText(g.fuelType), style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
}

@Composable
fun MiriRoot(vm: MiriViewModel, onRequestMic: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    CarTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when {
                    !ui.settings.onboarded -> Onboarding(vm::finishOnboarding)
                    ui.state.isRestricted -> DrivingScreen(ui, vm)
                    ui.state == DrivingState.CHARGING -> ChargingScreen(ui, vm)
                    else -> ParkedScreens(ui, vm)
                }
                if (ui.voice != VoiceUi.Idle) VoiceOverlay(ui.voice, ui.state.isRestricted, vm, Modifier.align(Alignment.BottomCenter))
                if (ui.pickPlaceFor != null || ui.micPrompt || ui.confirmClear || ui.form != null) {
                    // 창 뒤의 버튼이 눌리지 않게 막는다.
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    )
                }
                ui.pickPlaceFor?.let { (trip, candidates) -> PickPlace(trip, candidates, vm, Modifier.align(Alignment.Center)) }
                if (ui.micPrompt) MicPrompt(vm, onRequestMic, Modifier.align(Alignment.Center))
                if (ui.confirmClear) ConfirmClear(vm, Modifier.align(Alignment.Center))
                ui.form?.let { TripFormPanel(it, ui.settings.demo, vm, Modifier.align(Alignment.Center)) }
            }
        }
    }
}

/** SC-01 시작·동의 + 첫 질문 (R-15). */
@Composable
private fun Onboarding(onDone: (Double?) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CarDimens.pad, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("미리 채움", style = MaterialTheme.typography.displayLarge)
        Text(
            "요일·시간마다 반복해서 가는 목적지와 캘린더에 등록한 일정의 목적지를 보고, 그 일정 전에 최소·권장 충전량과 걸리는 시간을 미리 알려드려요.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(max = 1000.dp),
        )
        Panel(Modifier.widthIn(max = 1000.dp), title = "무엇을 기록하나요") {
            Text("· 출발·도착 위치 (동네 단위, 약 300m)\n· 차량 상태 (주행거리, 배터리·연료, 주차 여부, 기온)\n· 말로 남긴 이동 일정 (날짜·시간·장소만)", style = MaterialTheme.typography.bodyLarge)
            Text("기록은 차 안에만 두고 밖으로 보내지 않아요. 설정에서 언제든 끄거나 지울 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        }
        Text("하루에 보통 몇 km 타세요?", style = MaterialTheme.typography.headlineMedium)
        Text("2주 동안은 이 값으로 판단하고, 그 뒤에는 운행 패턴으로 바뀌어요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(20.0, 40.0, 60.0, 80.0, 120.0).forEach { km ->
                CarButton("${km.roundToInt()}km", { onDone(km) }, Modifier.width(160.dp))
            }
            CarButton("잘 모르겠어요", { onDone(null) }, primary = false)
        }
    }
}

/** SC-05 주행 중: 판정 색 + 한 줄 + 마이크 버튼만. */
@Composable
private fun DrivingScreen(ui: MiriUi, vm: MiriViewModel) {
    val r = ui.result
    Column(Modifier.fillMaxSize().padding(CarDimens.pad), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        if (ui.settings.demo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시연: 주행 중 화면", style = MaterialTheme.typography.titleLarge, color = CarColors.yellow, modifier = Modifier.weight(1f))
                CarButton("주차하기", { vm.demoSetState(DrivingState.PARKED) }, primary = false)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrivingLine(r?.judgement?.verdict.tone(), r?.cardLine ?: ui.status ?: "", sub = r?.reason, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(CarDimens.pad))
            MicButton(listening = ui.voice is VoiceUi.Listening, onClick = vm::startVoice, size = 140.dp)
        }
        Spacer(Modifier.weight(1f))
        CaptionBar(ui.captions)
    }
}

/** SC-08 충전 중 (S5). */
@Composable
private fun ChargingScreen(ui: MiriUi, vm: MiriViewModel) {
    val c = ui.charging
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(CarDimens.pad, Alignment.CenterVertically)) {
        Text("충전 중", style = MaterialTheme.typography.titleLarge, color = CarColors.textDim)
        Text(c?.line ?: "충전 정보를 계산하는 중이에요", style = MaterialTheme.typography.displayLarge)
        ui.snapshot?.let { s -> Text("지금 ${s.levelPercent.roundToInt()}% · 갈 수 있는 거리 ${Korean.km(s.rangeKm)}", style = MaterialTheme.typography.headlineMedium) }
        if (ui.settings.demo) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (c?.reached == false) CarButton("목표까지 건너뛰기 (시연)", vm::demoChargeToTarget, Modifier.widthIn(min = 320.dp))
                CarButton("충전 끝내기", { vm.demoSetState(DrivingState.PARKED) }, Modifier.widthIn(min = 320.dp), primary = false)
            }
        }
        CaptionBar(ui.captions)
    }
}

@Composable
private fun ParkedScreens(ui: MiriUi, vm: MiriViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar("미리 채움", ui.settings.demo) {
            listOf(Screen.HOME to "홈", Screen.CALENDAR to "캘린더", Screen.REPORT to "리포트", Screen.SETTINGS to "설정").forEach { (s, label) ->
                CarButton(label, { vm.go(s) }, primary = ui.screen == s)
            }
        }
        if (!ui.speedGranted && !ui.settings.demo) {
            Text("차량 속도 권한이 없어 주행 화면이 나오지 않아요", style = MaterialTheme.typography.bodyLarge, color = CarColors.yellow, modifier = Modifier.padding(horizontal = CarDimens.pad))
        }
        ui.notice?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, color = CarColors.green, modifier = Modifier.padding(horizontal = CarDimens.pad))
        }
        Box(Modifier.fillMaxSize().padding(CarDimens.pad)) {
            when (ui.screen) {
                Screen.HOME -> Home(ui, vm)
                Screen.SOLUTIONS -> Solutions(ui, vm)
                Screen.DETAIL -> Detail(ui, vm)
                Screen.CALENDAR -> CalendarScreen(ui, vm)
                Screen.REPORT -> Report(ui)
                Screen.SETTINGS -> Settings(ui, vm)
            }
        }
    }
}

/** SC-02 홈: 48시간 계획. */
@Composable
private fun Home(ui: MiriUi, vm: MiriViewModel) {
    val r = ui.result
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Panel(Modifier.fillMaxWidth(), title = "다음 일정 충전 안내 (앞으로 48시간)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(r?.judgement?.verdict.tone().color))
                    Spacer(Modifier.width(20.dp))
                    Text(r?.cardLine ?: ui.status ?: "", style = MaterialTheme.typography.headlineLarge)
                }
                if (r != null) {
                    val j = r.judgement
                    val focus = r.focusGuide
                    if (focus != null) {
                        GuideBlock(focus, j.correctedRangeKm, r.guides.filter { it !== focus }, r.demand.from)
                    } else {
                        InfoRow("예상 거리", Korean.km(j.needKm))
                        InfoRow("갈 수 있는 거리 (겨울 보정)", "${Korean.km(j.correctedRangeKm)}  (표시 ${Korean.km(j.rangeKm)})")
                    }
                    Text("왜: ${r.reason}", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
                    if (r.askDailyKm) Text("기록이 2주가 안 돼서 하루 평균으로 계산했어요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
                }
            }
            r?.plan?.best?.let { best ->
                Panel(Modifier.fillMaxWidth(), title = "이렇게 채우세요") {
                    SolutionSummary(best, r.plan?.fuelType, r.plan?.percent)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (best.kind != SolutionKind.HOME) CarButton(best.actionLabel(), { vm.acceptSolution(best) }, Modifier.weight(1f))
                        CarButton("다른 방법 보기", { vm.go(Screen.SOLUTIONS) }, Modifier.weight(1f), primary = false)
                    }
                }
            }
            CaptionBar(ui.captions)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Panel(Modifier.fillMaxWidth(), title = "다가오는 일정") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("평소와 다른 일정만 말로 알려주세요", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    MicButton(listening = ui.voice is VoiceUi.Listening, onClick = vm::startVoice)
                }
                if (ui.voiceTrips.isEmpty()) {
                    Text("등록된 일정이 없어요", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
                } else {
                    ui.voiceTrips.take(3).forEach { Text(it.line(r?.tripTargets?.get(it.id)), style = MaterialTheme.typography.bodyLarge) }
                }
                ui.calendar?.let { Text(it.line, style = MaterialTheme.typography.bodyLarge, color = it.needVerdict.tone().color) }
                CarButton("캘린더 보기", { vm.go(Screen.CALENDAR) }, Modifier.fillMaxWidth(), primary = false)
            }
            if (ui.settings.demo) DemoPanel(ui, vm)
        }
    }
}

@Composable
private fun SolutionSummary(s: Solution, fuel: FuelType?, percent: Double?) {
    Text(if (s.kind == SolutionKind.HOME) s.kind.label() else "${s.kind.label()} · ${s.placeName}", style = MaterialTheme.typography.headlineMedium)
    val extra = if (s.extraMinutes < 0.5) "추가 시간 0분" else "추가 약 ${Korean.minutes(s.extraMinutes)}"
    Text(
        "${s.amountText(fuel, percent)} · $extra · 약 ${Korean.won(s.costWon)}",
        style = MaterialTheme.typography.bodyLarge,
        color = CarColors.textDim,
    )
}

@Composable
private fun DemoPanel(ui: MiriUi, vm: MiriViewModel) {
    Panel(Modifier.fillMaxWidth(), title = "시연 장면 (가상 운전자 3주 기록)") {
        DemoScene.entries.forEach { s ->
            CarButton(s.title, { vm.selectScene(s) }, Modifier.fillMaxWidth(), primary = ui.scene == s)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarButton("출발 (주행 화면)", { vm.demoSetState(DrivingState.DRIVING) }, Modifier.weight(1f), primary = false)
            CarButton("충전 시작", { vm.demoSetState(DrivingState.CHARGING) }, Modifier.weight(1f), primary = false)
        }
    }
}

/** SC-03 해결책 목록: 체류 → 경로상 → 우회 순서 (R-11). */
@Composable
private fun Solutions(ui: MiriUi, vm: MiriViewModel) {
    val plan = ui.result?.plan
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CarDimens.gap)) {
        if (plan == null) {
            Text("지금은 채우지 않아도 충분해요.", style = MaterialTheme.typography.headlineMedium)
            return@Column
        }
        Text(
            "필요한 양: ${if (plan.fuelType == FuelType.ICE) "${plan.amount.roundToInt()}L" else "${plan.percent.roundToInt()}% (약 ${"%.1f".format(plan.amount)}kWh)"} · 약 ${copy.approxKm(plan.addKm)} 더 갈 수 있어요",
            style = MaterialTheme.typography.titleLarge,
        )
        plan.deadline?.let { Text("${Korean.spokenDate(it.toLocalDate())} ${Korean.spokenTime(it.toLocalTime())} 출발 전까지", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim) }
        plan.solutions.take(3).forEach { s ->
            Panel(Modifier.fillMaxWidth()) {
                SolutionSummary(s, plan.fuelType, plan.percent)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.kind != SolutionKind.HOME) {
                        CarButton(s.actionLabel(), { vm.acceptSolution(s) })
                        CarButton("자세히", { vm.openDetail(s) }, primary = false)
                    }
                }
            }
        }
        Text("충전기 상태 정보는 틀릴 때가 있어서 후보를 3곳까지 보여드려요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
    }
}

/** SC-04 충전소·주유소 상세. 지도 SDK가 없어 위치는 주소 문구로 보여준다. */
@Composable
private fun Detail(ui: MiriUi, vm: MiriViewModel) {
    val s = ui.detail ?: return
    val plan = ui.result?.plan
    val isIce = plan?.fuelType == FuelType.ICE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CarDimens.gap)) {
        CarButton("← 뒤로", { vm.go(Screen.SOLUTIONS) }, primary = false)
        Text(s.placeName, style = MaterialTheme.typography.headlineLarge)
        val detour = s.detourMinutes?.let { " · 경로에서 약 ${Korean.minutes(it)}" } ?: ""
        Text("${s.kind.label()}$detour", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
        Panel(Modifier.fillMaxWidth(), title = if (isIce) "이 주유소" else "이 충전소") {
            s.address?.let { InfoRow("위치", it) }
            if (isIce) {
                InfoRow("가격", "${s.unitPrice.roundToInt()}원/L")
                plan?.averageUnitPrice?.let { avg ->
                    val diff = (avg - s.unitPrice).roundToInt()
                    InfoRow("주변 평균 대비", if (diff >= 0) "${diff}원 저렴" else "${-diff}원 비쌈", if (diff >= 0) CarColors.green else CarColors.yellow)
                }
            } else {
                if (s.available != null) InfoRow("사용 가능", s.total?.let { t -> "${s.available}대 / 전체 ${t}대" } ?: "${s.available}대")
                s.powerKw?.let { InfoRow("충전 속도", "${it.roundToInt()}kW") }
                InfoRow("가격", "${"%.1f".format(s.unitPrice)}원/kWh")
            }
            InfoRow("이번에 채울 양", "${s.amountText(plan?.fuelType, plan?.percent)} · 약 ${Korean.minutes(s.fillMinutes)}")
            InfoRow("예상 비용", "약 ${Korean.won(s.costWon)}")
            s.statusAgeMinutes?.let { age ->
                // A-35: 공공 데이터 상태는 틀릴 수 있어 기준 시각을 항상 보여준다.
                val stale = age > STALE_STATUS_MINUTES
                Text(
                    if (stale) "상태 정보 기준: ${age}분 전 · 상태 확인 필요" else "상태 정보 기준: ${age}분 전",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stale) CarColors.yellow else CarColors.textDim,
                )
            }
        }
        CarButton(s.actionLabel(), { vm.acceptSolution(s) }, Modifier.fillMaxWidth())
    }
}

/** SC-09 월간 리포트 (F-13). */
@Composable
private fun Report(ui: MiriUi) {
    val r = ui.report
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Text(ui.reportLine ?: "", style = MaterialTheme.typography.headlineLarge)
        if (r != null) {
            Panel(Modifier.widthIn(max = 900.dp), title = "${r.month.monthValue}월") {
                InfoRow("미리 채운 횟수", "${r.count}번")
                InfoRow("줄인 급속 우회", "${r.avoidedDetours}번")
                InfoRow("아낀 시간", Korean.minutes(r.savedMinutes), CarColors.green)
                InfoRow("아낀 돈", Korean.won(r.savedWon), CarColors.green)
            }
            Text("비교 기준: 출발 직전에 초급속 충전소로 우회했을 때 (우회 10분, 393.1원/kWh)", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        }
    }
}

/** SC-10 설정. */
@Composable
private fun Settings(ui: MiriUi, vm: MiriViewModel) {
    val s = ui.settings
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Toggle("시연 모드", "가상 운전자의 3주 기록으로 '3주 뒤' 모습을 보여줘요.", s.demo, vm::setDemo, onLabel = "켜기", offLabel = "끄기 (실제 차량)")
            Toggle("운행 기록", "끄면 새 운행을 기록하지 않아요.", s.recording, vm::setRecording)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Toggle("집 충전", "집에서 충전할 수 있으면 밤사이 충전을 먼저 생각해요.", s.homeCharging, vm::setHomeCharging, onLabel = "가능", offLabel = "불가")
            Panel(Modifier.fillMaxWidth(), title = "기록 지우기") {
                Text("운행·체류 기록과 이동 일정을 모두 지워요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
                CarButton("모두 지우기", vm::askClearRecords, primary = false)
            }
            // A-37: 연료 %를 L로 바꿀 때 쓰므로 내연기관 차에서만 보여준다.
            if (ui.snapshot?.fuelType == FuelType.ICE) {
                Panel(Modifier.fillMaxWidth(), title = "탱크 용량") {
                    Text("연료 %를 L로 바꿀 때 써요. 30~80L", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CarButton("-5L", { vm.changeTank(-5.0) }, primary = false)
                        Text("${s.tankLiters.roundToInt()}L", style = MaterialTheme.typography.headlineMedium)
                        CarButton("+5L", { vm.changeTank(5.0) }, primary = false)
                    }
                }
            }
            Text("하루 평균 ${s.dailyKm?.roundToInt() ?: 40}km (첫 사용 답)", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String, on: Boolean, set: (Boolean) -> Unit, onLabel: String = "켜기", offLabel: String = "끄기") {
    Panel(Modifier.fillMaxWidth(), title = title) {
        Text(desc, style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarButton(onLabel, { set(true) }, Modifier.weight(1f), primary = on)
            CarButton(offLabel, { set(false) }, Modifier.weight(1f), primary = !on)
        }
    }
}

/** SC-06 음성 등록: 듣는 중 표시 → 확인 문장. 시연 모드는 예문 버튼으로 말을 대신한다. 주행 중에는 터치 버튼을 숨긴다(A-45). */
@Composable
private fun VoiceOverlay(voice: VoiceUi, restricted: Boolean, vm: MiriViewModel, modifier: Modifier) {
    Panel(modifier.fillMaxWidth().padding(CarDimens.pad)) {
        when (voice) {
            is VoiceUi.Listening -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicButton(listening = true, onClick = {}, size = 96.dp)
                    Spacer(Modifier.width(CarDimens.pad))
                    Text(voice.hint, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (!restricted) CarButton("닫기", vm::dismissVoice, primary = false)
                }
                if (voice.examples.isNotEmpty()) {
                    Text("시연: 아래 문장을 누르면 말한 것으로 처리해요", style = MaterialTheme.typography.bodyMedium, color = CarColors.yellow)
                    voice.examples.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { e -> CarButton(e, { vm.submitTyped(e) }, primary = false) }
                        }
                    }
                }
            }
            is VoiceUi.Message -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(voice.text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (!restricted) CarButton("확인", vm::dismissVoice)
            }
            VoiceUi.Idle -> Unit
        }
    }
}

/** SC-12 마이크 권한 안내 (A-34). */
@Composable
private fun MicPrompt(vm: MiriViewModel, onRequest: () -> Unit, modifier: Modifier) {
    Panel(modifier.widthIn(max = 900.dp).padding(CarDimens.pad)) {
        Text("마이크 권한이 필요해요", style = MaterialTheme.typography.headlineLarge)
        Text("이동 일정을 말로 남기고 '응'으로 대답하려면 마이크가 필요해요. 음성은 저장하지 않아요.", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarButton("권한 허용하기", { vm.closeMicPrompt(); onRequest() }, Modifier.weight(1f))
            CarButton("나중에", vm::closeMicPrompt, Modifier.weight(1f), primary = false)
        }
    }
}

/** 기록 모두 지우기 확인 (A-42). 되돌릴 수 없어서 한 번 더 묻는다. */
@Composable
private fun ConfirmClear(vm: MiriViewModel, modifier: Modifier) {
    Panel(modifier.widthIn(max = 900.dp).padding(CarDimens.pad)) {
        Text("기록을 모두 지울까요?", style = MaterialTheme.typography.headlineLarge)
        Text("운행·체류 기록과 이동 일정이 지워지고 되돌릴 수 없어요.", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarButton("모두 지우기", vm::clearRecords, Modifier.weight(1f))
            CarButton("취소", vm::cancelClearRecords, Modifier.weight(1f), primary = false)
        }
    }
}

/** 장소 고르기 (정차 후). */
@Composable
private fun PickPlace(trip: VoiceTrip, candidates: List<kr.mobility.mirichaeum.PlaceCandidate>, vm: MiriViewModel, modifier: Modifier) {
    Panel(modifier.widthIn(max = 900.dp).padding(CarDimens.pad), title = "'${trip.placeQuery}' 어디로 가세요?") {
        if (candidates.isEmpty()) Text("찾은 장소가 없어요", style = MaterialTheme.typography.bodyLarge)
        candidates.forEach { c -> CarButton(c.name, { vm.pickPlace(trip, c) }, Modifier.fillMaxWidth(), primary = false) }
        CarButton("닫기", vm::cancelPickPlace)
    }
}
