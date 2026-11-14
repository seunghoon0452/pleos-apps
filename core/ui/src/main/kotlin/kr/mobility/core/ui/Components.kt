package kr.mobility.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 신호등 한 칸. 켜진 칸만 밝게. */
@Composable
fun SignalLamp(tone: Tone, lit: Boolean, size: Dp = 96.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (lit) tone.color else tone.color.copy(alpha = 0.15f))
            .border(2.dp, tone.color.copy(alpha = if (lit) 1f else 0.3f), CircleShape),
    )
}

/** 초록·노랑·빨강 세 칸 신호등. [active]가 null이면 모두 꺼짐. */
@Composable
fun TrafficLight(active: Tone?, lampSize: Dp = 96.dp, vertical: Boolean = false) {
    val lamps: @Composable () -> Unit = {
        SignalLamp(Tone.GOOD, active == Tone.GOOD, lampSize)
        SignalLamp(Tone.WARN, active == Tone.WARN, lampSize)
        SignalLamp(Tone.DANGER, active == Tone.DANGER, lampSize)
    }
    val label = when (active) {
        Tone.GOOD -> "초록"
        Tone.WARN -> "노랑"
        Tone.DANGER -> "빨강"
        else -> "꺼짐"
    }
    val modifier = Modifier
        .clip(RoundedCornerShape(lampSize / 2))
        .background(CarColors.surfaceHigh)
        .padding(16.dp)
        .semantics { contentDescription = "신호등 $label" }
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) { lamps() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) { lamps() }
    }
}

/** 판정 색 띠 + 큰 한 줄. 주행 중 화면의 전부. */
@Composable
fun DrivingLine(tone: Tone, text: String, sub: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CarColors.surface)
            .padding(CarDimens.pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(tone.color),
        )
        Spacer(Modifier.width(CarDimens.pad))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim, maxLines = 1)
        }
    }
}

/** 마이크 버튼. 듣는 중이면 깜빡인다. */
@Composable
fun MicButton(listening: Boolean, onClick: () -> Unit, size: Dp = 112.dp, label: String = "말하기") {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (listening) CarColors.red else CarColors.accent)
            .alpha(if (listening) pulse else 1f)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (listening) "듣는 중" else label },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (listening) "듣는 중" else label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** 차량용 큰 버튼. */
@Composable
fun CarButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(18.dp)
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = modifier.heightIn(min = CarDimens.touchTarget),
            colors = ButtonDefaults.buttonColors(containerColor = CarColors.accent),
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = modifier.heightIn(min = CarDimens.touchTarget),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, color = CarColors.text) }
    }
}

/** 둥근 면 위의 묶음. */
@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CarColors.surface)
            .padding(CarDimens.pad),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, color = CarColors.textDim)
        content()
    }
}

/** 마지막으로 읽은 음성 안내를 자막으로 보여준다(소리를 못 들은 경우·시연 화면용). */
@Composable
fun CaptionBar(lines: List<String>, modifier: Modifier = Modifier) {
    val last = lines.lastOrNull() ?: return
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CarColors.surfaceHigh)
            .padding(horizontal = CarDimens.pad, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("음성", style = MaterialTheme.typography.labelLarge, color = CarColors.accent)
        Spacer(Modifier.width(16.dp))
        Text(last, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** 상단 띠: 앱 이름, 시연 모드 표시, 오른쪽 동작. */
@Composable
fun TopBar(title: String, demo: Boolean, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CarDimens.pad, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (demo) {
            Spacer(Modifier.width(16.dp))
            Text(
                "시연 모드",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CarColors.yellow)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
    }
}

/** 이름 + 값 한 줄. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = CarColors.text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
    }
}
