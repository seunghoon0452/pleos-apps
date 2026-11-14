package kr.mobility.ansim.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.mobility.ansim.Sensitivity
import kr.mobility.ansim.demo.DemoDrives
import kr.mobility.core.ui.CaptionBar
import kr.mobility.core.ui.CarButton
import kr.mobility.core.ui.CarColors
import kr.mobility.core.ui.CarDimens
import kr.mobility.core.ui.CarTheme
import kr.mobility.core.ui.MicButton
import kr.mobility.core.ui.Panel
import kr.mobility.core.ui.TopBar
import kr.mobility.core.ui.TrafficLight

private val COMPACT_WIDTH = 600.dp
private val COMPACT_TALL_HEIGHT = 600.dp

@Composable
fun AnsimRoot(vm: AnsimViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    CarTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !ui.settings.disclaimerAccepted -> DisclaimerScreen(vm::acceptDisclaimer)
                ui.demoTitle != null || ui.state.isRestricted -> DrivingScreen(ui, vm::askWhy, vm::stopDemo)
                else -> ParkedScreen(ui, vm)
            }
        }
    }
}

/** 첫 사용 (R-08): 보조 정보이며 판단·책임은 운전자에게 있음을 알린다. */
@Composable
private fun DisclaimerScreen(onAccept: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(CarDimens.pad, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrafficLight(active = null)
        Text("관심 주행", style = MaterialTheme.typography.displayLarge)
        Text(
            "앞으로 지날 2km의 도로 상황과 지금 날씨·시간을 합쳐서 초록·노랑·빨강으로 알려드려요.\n" +
                "운전을 돕는 보조 정보예요. 판단과 책임은 운전자에게 있어요.",
            style = MaterialTheme.typography.bodyLarge,
            color = CarColors.textDim,
            modifier = Modifier.widthIn(max = 900.dp),
        )
        Text("지나간 구간의 위치는 저장하지 않아요. 운행이 끝나면 지난 주의 구간 개수만 남겨요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        CarButton("확인했어요", onAccept, Modifier.widthIn(min = 320.dp))
    }
}

/** SC-06 작은 창 (A-58): 내비와 같이 켜 둘 때. 신호등과 한 줄만 남긴다. */
@Composable
private fun CompactDriving(ui: AnsimUi, tall: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(CarDimens.gap),
        verticalArrangement = Arrangement.spacedBy(CarDimens.gap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 세로가 짧은 창에서는 신호등이 글자를 밀어내지 않게 가로로 놓는다.
        TrafficLight(RoadMapping.tone(ui.frame?.assessment?.signal), lampSize = if (tall) 96.dp else 56.dp, vertical = tall)
        Text(
            ui.frame?.screenLine ?: "도로 정보를 받는 중이에요",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
    }
}

/** 주행 중 (R-07): 색 + 이유 한 줄 + 남은 거리만. 버튼은 "왜?" 하나. 창이 작으면 SC-06. */
@Composable
private fun DrivingScreen(ui: AnsimUi, onWhy: () -> Unit, onStopDemo: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < COMPACT_WIDTH) CompactDriving(ui, tall = maxHeight >= COMPACT_TALL_HEIGHT) else FullDriving(ui, onWhy, onStopDemo)
    }
}

@Composable
private fun FullDriving(ui: AnsimUi, onWhy: () -> Unit, onStopDemo: () -> Unit) {
    val frame = ui.frame
    Column(Modifier.fillMaxSize().padding(CarDimens.pad), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        if (ui.demoTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시연: ${ui.demoTitle} (${ui.demoStep}/${ui.demoTotal})", style = MaterialTheme.typography.titleLarge, color = CarColors.yellow, modifier = Modifier.weight(1f))
                CarButton("시연 끝내기", onStopDemo, primary = false)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TrafficLight(RoadMapping.tone(frame?.assessment?.signal), lampSize = 120.dp, vertical = true)
            Spacer(Modifier.padding(horizontal = 24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(frame?.screenLine ?: "도로 정보를 받는 중이에요", style = MaterialTheme.typography.displayLarge)
                // A-51: 점수는 심사위원에게 계산을 보여주는 용도라 시연에서만 표시한다(R-07).
                if (ui.demoTitle != null) {
                    frame?.assessment?.let { a ->
                        Text("위험 점수 ${a.score}점", style = MaterialTheme.typography.titleLarge, color = CarColors.textDim)
                    }
                }
            }
            MicButton(listening = ui.listeningWhy, onClick = onWhy, label = "왜?")
        }
        CaptionBar(ui.captions)
    }
}

/** 주차 중: 오늘 요약, 설정, 시연. */
@Composable
private fun ParkedScreen(ui: AnsimUi, vm: AnsimViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar("관심 주행", ui.settings.demo)
        if (!ui.speedGranted && !ui.settings.demo) {
            Text("차량 속도 권한이 없어 주행 화면이 나오지 않아요", style = MaterialTheme.typography.bodyLarge, color = CarColors.yellow, modifier = Modifier.padding(horizontal = CarDimens.pad))
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(CarDimens.pad)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(CarDimens.pad),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
                Panel(Modifier.fillMaxWidth(), title = "오늘") {
                    Text(ui.lastSummary ?: "주행을 시작하면 신호등이 켜져요.", style = MaterialTheme.typography.headlineMedium)
                    Text("오늘 지난 주의 구간 ${ui.todayCount}곳", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
                    if (!ui.settings.demo) Text("경로 안내를 시작하면 앞길까지 봐요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
                }
                if (ui.settings.demo) {
                    Panel(Modifier.fillMaxWidth(), title = "시연 주행 (가상 경로·날씨)") {
                        DemoDrives.all.forEach { d ->
                            CarButton("${d.id}  ${d.title}", { vm.playDemo(d) }, Modifier.fillMaxWidth())
                        }
                    }
                }
                CaptionBar(ui.captions)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
                Panel(Modifier.fillMaxWidth(), title = "음성 안내") {
                    Text("빨강으로 바뀔 때만 한 번 말해요. 같은 구간은 반복하지 않아요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("켜기", { vm.setVoice(true) }, Modifier.weight(1f), primary = ui.settings.voiceOn)
                        CarButton("끄기", { vm.setVoice(false) }, Modifier.weight(1f), primary = !ui.settings.voiceOn)
                    }
                }
                Panel(Modifier.fillMaxWidth(), title = "민감도") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(Sensitivity.LOW to "낮음", Sensitivity.NORMAL to "보통", Sensitivity.HIGH to "높음").forEach { (s, label) ->
                            CarButton(label, { vm.setSensitivity(s) }, Modifier.weight(1f), primary = ui.settings.sensitivity == s)
                        }
                    }
                    Text(
                        "노랑 ${ui.settings.sensitivity.yellowFrom}점부터, 빨강 ${ui.settings.sensitivity.redFrom}점부터",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CarColors.textDim,
                    )
                }
                Panel(Modifier.fillMaxWidth(), title = "시연 모드") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("켜기", { vm.setDemo(true) }, Modifier.weight(1f), primary = ui.settings.demo)
                        CarButton("끄기 (실제 차량)", { vm.setDemo(false) }, Modifier.weight(1f), primary = !ui.settings.demo)
                    }
                }
                Box(Modifier.height(8.dp))
            }
        }
    }
}
