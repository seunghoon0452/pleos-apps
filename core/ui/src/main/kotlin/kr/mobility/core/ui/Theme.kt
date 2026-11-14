package kr.mobility.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 차량 화면용 테마. 어두운 바탕 + 큰 글자 + 높은 대비.
 * 주행 중 한 줄은 1~2초 안에 읽혀야 하므로(정의서 R-07) 본문도 일반 앱보다 크게 잡았다.
 */
object CarColors {
    val background = Color(0xFF0E1116)
    val surface = Color(0xFF181C23)
    val surfaceHigh = Color(0xFF222832)
    val text = Color(0xFFF2F4F7)
    val textDim = Color(0xFFA9B1BD)
    val green = Color(0xFF2FBF71)
    val yellow = Color(0xFFF5B800)
    val red = Color(0xFFEF4444)
    val gray = Color(0xFF6B7280)
    val accent = Color(0xFF4C9AFF)
}

/** 화면에서 쓰는 상태 색. 엔진의 판정·신호를 여기로 바꿔서 쓴다. */
enum class Tone(val color: Color) {
    GOOD(CarColors.green),
    WARN(CarColors.yellow),
    DANGER(CarColors.red),
    NEUTRAL(CarColors.gray),
}

object CarDimens {
    /** AAOS 권장 최소 터치 영역. */
    val touchTarget = 76.dp
    val gap = 16.dp
    val pad = 24.dp
}

private val carTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp),
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
    headlineMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun CarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = CarColors.background,
            surface = CarColors.surface,
            surfaceVariant = CarColors.surfaceHigh,
            primary = CarColors.accent,
            onPrimary = Color.White,
            onBackground = CarColors.text,
            onSurface = CarColors.text,
            onSurfaceVariant = CarColors.textDim,
            error = CarColors.red,
        ),
        typography = carTypography,
        content = content,
    )
}
