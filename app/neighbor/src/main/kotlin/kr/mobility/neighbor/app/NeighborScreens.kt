package kr.mobility.neighbor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mobility.core.ui.CaptionBar
import kr.mobility.core.ui.CarButton
import kr.mobility.core.ui.CarColors
import kr.mobility.core.ui.CarDimens
import kr.mobility.core.ui.CarTheme
import kr.mobility.core.ui.InfoRow
import kr.mobility.core.ui.Panel
import kr.mobility.core.ui.TopBar
import kr.mobility.neighbor.AppealStatus
import kr.mobility.neighbor.DemoMyGrade
import kr.mobility.neighbor.Grade
import kr.mobility.neighbor.Position
import kr.mobility.neighbor.SelfGrade
import kr.mobility.neighbor.Side
import kr.mobility.neighbor.bySector

private const val DEMO_NOTICE = "시연: 가상 데이터. 실제 데이터는 협력 후 연결"
private const val SHARED_ONLY = "등급을 공유한 차량만 보여요"

private fun Grade?.color(): Color = when (this) {
    Grade.GOOD -> CarColors.green
    Grade.NORMAL -> CarColors.yellow
    Grade.CAUTION -> CarColors.red
    null -> CarColors.gray
}

private fun Grade?.text(): String = this?.label ?: "미동의"

@Composable
fun NeighborRoot(vm: NeighborViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    CarTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !ui.settings.introDone -> Intro(vm::finishIntro)
                !ui.settings.consentDone -> ConsentScreen(vm::finishConsent)
                ui.scene != null || ui.state.isRestricted -> Driving(ui, vm)
                else -> Parked(ui, vm)
            }
        }
    }
}

/** SC-01 시작 안내. */
@Composable
private fun Intro(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(CarDimens.pad, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("안심 이웃", style = MaterialTheme.typography.displayLarge)
        Text(
            "주변 차량의 위험을 미리 알려 거리를 두도록 돕는 보조 정보예요.\n판단과 책임은 운전자에게 있어요.",
            style = MaterialTheme.typography.bodyLarge,
            color = CarColors.textDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 1000.dp),
        )
        Panel(Modifier.widthIn(max = 1000.dp), title = "이렇게 지켜요") {
            Text(
                "· 동의한 운전자의 3등급만 봐요. 점수와 이력은 보지 않아요.\n" +
                    "· 번호판을 읽지 않고, 상대가 누구인지 알 수 없어요.\n" +
                    "· 내 등급 공유는 선택이고, 언제든 끌 수 있어요.\n" +
                    "· 등급이 틀리면 이의 신청을 할 수 있어요.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        CarButton("다음", onNext, Modifier.widthIn(min = 320.dp))
    }
}

/** SC-02 동의 (A-62): 직접 골라야 다음으로 간다. */
@Composable
private fun ConsentScreen(onDone: (Boolean, Boolean) -> Unit) {
    var location by remember { mutableStateOf<Boolean?>(null) }
    var share by remember { mutableStateOf<Boolean?>(null) }
    Column(
        Modifier.fillMaxSize().padding(48.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CarDimens.pad, Alignment.CenterVertically),
    ) {
        Text("두 가지를 확인해 주세요", style = MaterialTheme.typography.headlineLarge)
        Choice("① 위치 제공 (주변 보기에 필요)", "내 위치·방향·속도를 내비 회사에 보내 근처 차량을 찾아요. 임시 번호를 쓰고, 운행이 끝나면 지워요.", location) { location = it }
        Choice("② 내 등급 공유 (선택)", "동의하면 내 차가 주변 차량 화면에 내 등급 색으로 보여요. 점수와 운전 기록은 보이지 않아요.", share) { share = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("언제든 설정에서 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim, modifier = Modifier.weight(1f))
            val l = location
            val s = share
            CarButton("다음", { if (l != null && s != null) onDone(l, s) }, Modifier.widthIn(min = 320.dp), enabled = l != null && s != null)
        }
    }
}

@Composable
private fun Choice(title: String, desc: String, value: Boolean?, set: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth(), title = title) {
        Text(desc, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarButton("동의", { set(true) }, Modifier.width(280.dp), primary = value == true)
            CarButton("동의 안 함", { set(false) }, Modifier.width(280.dp), primary = value == false)
        }
    }
}

/** SC-04 주변 보기. 주행 중에는 터치 요소가 없다. 시연 중에만 끝내기 버튼을 둔다. */
@Composable
private fun Driving(ui: NeighborUi, vm: NeighborViewModel) {
    Column(Modifier.fillMaxSize().padding(CarDimens.pad), verticalArrangement = Arrangement.spacedBy(CarDimens.gap)) {
        ui.scene?.let { scene ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$DEMO_NOTICE · ${scene.title} (${ui.step}/${ui.total})", style = MaterialTheme.typography.bodyLarge, color = CarColors.yellow, modifier = Modifier.weight(1f))
                CarButton("시연 끝내기", vm::stopScene, primary = false)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            SectorGrid(ui, Modifier.fillMaxHeight().aspectRatio(1f))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(CarDimens.pad, Alignment.CenterVertically)) {
                when {
                    !ui.settings.consent.canViewNearby -> Text("위치 제공에 동의하면 주변 보기를 쓸 수 있어요", style = MaterialTheme.typography.headlineMedium)
                    ui.noData -> Text("주변 정보를 받지 못하고 있어요", style = MaterialTheme.typography.headlineMedium)
                    ui.line != null -> Text(ui.line, style = MaterialTheme.typography.headlineLarge)
                    else -> Text("주변 차량", style = MaterialTheme.typography.headlineLarge)
                }
                ui.mySignal?.let {
                    Text("방향지시등: ${if (it == Side.RIGHT) "오른쪽" else "왼쪽"}", style = MaterialTheme.typography.titleLarge, color = CarColors.textDim)
                }
                Legend()
                Text(SHARED_ONLY, style = MaterialTheme.typography.titleLarge)
                Text("표시가 없다고 안전하다는 뜻은 아니에요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
            }
        }
        CaptionBar(if (ui.scene != null) ui.captions else emptyList())
    }
}

/** 8칸 주변 그림 (A-63): 위치를 찍지 않고 구역으로만 보여준다. */
@Composable
private fun SectorGrid(ui: NeighborUi, modifier: Modifier) {
    val sectors = ui.items.bySector()
    val rows = listOf(
        listOf(Position.FRONT_LEFT, Position.FRONT, Position.FRONT_RIGHT),
        listOf(Position.LEFT, null, Position.RIGHT),
        listOf(Position.REAR_LEFT, Position.REAR, Position.REAR_RIGHT),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pos ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                            .background(if (pos == null) CarColors.gray else CarColors.surface)
                            .then(if (pos != null && pos == ui.highlight) Modifier.border(6.dp, CarColors.red, RoundedCornerShape(16.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pos == null) {
                            Text("내 차", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        } else {
                            Text(pos.label, style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                            sectors[pos]?.firstOrNull()?.let { CarChip(it.grade) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarChip(grade: Grade?) {
    Box(
        Modifier.size(width = 96.dp, height = 120.dp).clip(RoundedCornerShape(16.dp)).background(grade.color()),
        contentAlignment = Alignment.Center,
    ) {
        Text(grade.text(), style = MaterialTheme.typography.labelLarge, color = if (grade == Grade.NORMAL) Color.Black else Color.White)
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Grade.GOOD, Grade.NORMAL, Grade.CAUTION).forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(g.color()))
                Spacer(Modifier.width(8.dp))
                Text(g.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Parked(ui: NeighborUi, vm: NeighborViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar("안심 이웃", ui.settings.demo) {
            val tabs = buildList {
                add(Screen.MY_GRADE to "내 등급")
                add(Screen.SETTINGS to "설정")
                if (ui.settings.demo) add(Screen.DEMO to "시연")
            }
            tabs.forEach { (s, label) -> CarButton(label, { vm.go(s) }, primary = ui.screen == s || (s == Screen.MY_GRADE && ui.screen == Screen.APPEAL)) }
        }
        Box(Modifier.fillMaxSize().padding(CarDimens.pad)) {
            when (ui.screen) {
                Screen.MY_GRADE -> MyGrade(ui, vm)
                Screen.APPEAL -> Appeal(ui, vm)
                Screen.SETTINGS -> SettingsScreen(ui, vm)
                Screen.DEMO -> DemoStart(vm)
            }
        }
    }
}

/** SC-06 내 등급 (S2). 시연판은 가상 값이고, 실제 모드는 협력 전이라 연결 안내만 보여준다. */
@Composable
private fun MyGrade(ui: NeighborUi, vm: NeighborViewModel) {
    if (!ui.settings.demo) {
        Panel(Modifier.fillMaxWidth(), title = "내 등급") {
            Text("내비 계정을 연결하면 내 등급을 볼 수 있어요.", style = MaterialTheme.typography.headlineMedium)
            Text("내비 회사와 협력한 뒤 연결할 수 있어요.", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
        }
        return
    }
    val d = DemoMyGrade.value
    val grade = SelfGrade.of(d.score)
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Panel(Modifier.fillMaxWidth(), title = "이번 달 내 등급") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(grade.color()))
                    Spacer(Modifier.width(24.dp))
                    Text(grade.label, style = MaterialTheme.typography.displayLarge)
                }
                Text(SelfGrade.line(d.score), style = MaterialTheme.typography.bodyLarge)
                val visibility = when {
                    ui.appeal.hidesGrade -> "이의 신청을 확인하는 동안 다른 차에 보이지 않아요"
                    ui.settings.consent.visibleToOthers -> "등급 공유 켜짐"
                    else -> "등급 공유 꺼짐 · 다른 차에는 보이지 않아요"
                }
                Text(visibility, style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
            }
            Panel(Modifier.fillMaxWidth(), title = "이번 달 개선") {
                Text(d.changeLine, style = MaterialTheme.typography.bodyLarge)
                Text("시연: 가상 값이에요. 실제로는 내 차 브레이크·속도 변화로 추정해요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Panel(Modifier.fillMaxWidth(), title = "배지·혜택") {
                Text("초록 3개월 연속", style = MaterialTheme.typography.titleLarge)
                d.benefitLine?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            Panel(Modifier.fillMaxWidth(), title = "등급이 이상한가요?") {
                Text("근거 기간과 항목을 보고 재산출을 요청할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
                CarButton("이의 신청", { vm.go(Screen.APPEAL) }, Modifier.fillMaxWidth(), primary = false)
            }
        }
    }
}

/** SC-07 이의 신청 (S3). */
@Composable
private fun Appeal(ui: NeighborUi, vm: NeighborViewModel) {
    val d = DemoMyGrade.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("이의 신청", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            CarButton("← 내 등급", { vm.go(Screen.MY_GRADE) }, primary = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Panel(Modifier.weight(1f), title = "등급 근거 (지난달)") {
                d.evidence.forEach { (k, v) -> InfoRow(k, v) }
                Text("측정 주행거리 ${d.measuredKm}km, 협력 내비 기준", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
            }
            Panel(Modifier.weight(1f), title = if (ui.appeal == AppealStatus.NONE) "사유" else "진행 상태") {
                if (ui.appeal == AppealStatus.NONE) {
                    listOf("내가 운전하지 않았어요", "측정이 잘못된 것 같아요", "기타").forEach { reason ->
                        CarButton(reason, { vm.submitAppeal(reason) }, Modifier.fillMaxWidth(), primary = false)
                    }
                } else {
                    Text(listOf(AppealStatus.RECEIVED, AppealStatus.CHECKING, AppealStatus.DONE).joinToString(" → ") { if (it == ui.appeal) "[${it.label}]" else it.label }, style = MaterialTheme.typography.titleLarge)
                    ui.appealReason?.let { Text("사유: $it", style = MaterialTheme.typography.bodyLarge) }
                    Text(
                        if (ui.appeal == AppealStatus.DONE) "확인 결과 등급이 유지돼요. 근거는 왼쪽과 같아요." else "최대 7일 걸려요. 확인 중에는 내 등급이 다른 차에 보이지 않아요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CarColors.textDim,
                    )
                    CarButton(if (ui.appeal == AppealStatus.DONE) "처음으로 (시연)" else "다음 단계 (시연)", vm::advanceAppeal, Modifier.fillMaxWidth(), primary = false)
                }
            }
        }
    }
}

/** SC-08 설정·동의 관리. */
@Composable
private fun SettingsScreen(ui: NeighborUi, vm: NeighborViewModel) {
    val s = ui.settings
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Toggle("위치 제공 (주변 보기에 필요)", s.consentDate?.let { "$it 변경" } ?: "", s.consent.location, vm::setLocationConsent)
            Toggle("내 등급 공유 (선택)", s.consentDate?.let { "$it 변경" } ?: "", s.consent.share, vm::setShareConsent)
            Panel(Modifier.fillMaxWidth(), title = "내비 계정") {
                Text("협력 후 연결할 수 있어요.", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
            Toggle("음성 안내", "주의 차량이 가까울 때만 한 번 말해요.", s.voiceOn, vm::setVoice)
            Toggle("미동의 차량 보기", "등급을 공유하지 않은 차량을 회색으로 보여줘요.", s.showUnrated, vm::setShowUnrated)
            Toggle("시연 모드", "가상 데이터로 보여줘요.", s.demo, vm::setDemo)
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String, on: Boolean, set: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth(), title = title) {
        if (desc.isNotEmpty()) Text(desc, style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarButton("켜기", { set(true) }, Modifier.weight(1f), primary = on)
            CarButton("끄기", { set(false) }, Modifier.weight(1f), primary = !on)
        }
    }
}

/** SC-09 시연 시작. */
@Composable
private fun DemoStart(vm: NeighborViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Text(DEMO_NOTICE, style = MaterialTheme.typography.titleLarge, color = CarColors.yellow)
        Panel(Modifier.fillMaxWidth(), title = "시연 장면") {
            Scene.entries.forEach { s -> CarButton(s.title, { vm.playScene(s) }, Modifier.fillMaxWidth()) }
            CarButton("S2 주차 후 내 등급 (보여지는 사람)", { vm.go(Screen.MY_GRADE) }, Modifier.fillMaxWidth())
            CarButton("S3 이의 신청", { vm.go(Screen.APPEAL) }, Modifier.fillMaxWidth())
        }
        Text("차량 값(방향지시등)과 음성은 실제 Pleos SDK를 쓰고, 주변 차량과 등급만 가상이에요.", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
    }
}
