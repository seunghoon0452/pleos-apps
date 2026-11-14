package kr.mobility.mirichaeum.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.mobility.core.text.Korean
import kr.mobility.core.time.WeekSlot
import kr.mobility.core.ui.CarButton
import kr.mobility.core.ui.CarColors
import kr.mobility.core.ui.CarDimens
import kr.mobility.core.ui.MicButton
import kr.mobility.core.ui.Panel
import kr.mobility.mirichaeum.CalendarDay
import kr.mobility.mirichaeum.CalendarEntry
import kr.mobility.mirichaeum.CalendarView
import kr.mobility.mirichaeum.Copywriter
import kr.mobility.mirichaeum.EntryKind
import kr.mobility.mirichaeum.Verdict
import java.time.YearMonth

private val copy = Copywriter()
private val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")

// 칸 안 글자는 주차 화면 전용이라 본문보다 작게 쓴다(주행 중에는 캘린더가 열리지 않음).
private val cellDate = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
private val cellText = TextStyle(fontSize = 18.sp, lineHeight = 22.sp)
private val chipText = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)

private fun Verdict.word(): String = when (this) {
    Verdict.ENOUGH -> "충분"
    Verdict.TIGHT -> "빠듯"
    Verdict.SHORT -> "부족"
}

/** 일요일 빨강, 토요일 파랑 (국내 달력 관례). */
private fun weekdayColor(index: Int): Color = when (index) {
    0 -> CarColors.red
    6 -> CarColors.accent
    else -> CarColors.text
}

/** SC-07 캘린더 (F-22): 왼쪽 달력과 2주 전망, 오른쪽 고른 날의 일정. 주차 중에만 보인다. */
@Composable
internal fun CalendarScreen(ui: MiriUi, vm: MiriViewModel) {
    val view = ui.calendar
    val month = ui.calendarMonth
    if (view == null || month == null) {
        Text(ui.status ?: "차량 정보를 읽는 중이에요", style = MaterialTheme.typography.headlineMedium)
        return
    }
    val selected = ui.selectedDate ?: view.days.firstOrNull { it.isToday }?.date ?: view.days.first().date
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(CarDimens.pad)) {
        Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(view.line, style = MaterialTheme.typography.headlineMedium, color = view.needVerdict.tone().color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarButton("◀", { vm.shiftMonth(-1) }, primary = false)
                Text("${month.year}년 ${month.monthValue}월", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                CarButton("▶", { vm.shiftMonth(1) }, primary = false)
                CarButton("오늘", vm::calendarToday, primary = false)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                weekdays.forEachIndexed { i, d ->
                    Text(d, style = cellText, color = weekdayColor(i), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            view.days.chunked(7).forEach { week ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEachIndexed { i, day ->
                        DayCell(
                            day = day,
                            inMonth = YearMonth.from(day.date) == month,
                            selected = day.date == selected,
                            view = view,
                            dateColor = weekdayColor(i),
                            onClick = { vm.selectDate(day.date) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            Legend()
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CarDimens.gap)) {
            view.day(selected)?.let { DayPanel(it, view, ui, vm) }
        }
    }
}

@Composable
private fun DayCell(
    day: CalendarDay,
    inMonth: Boolean,
    selected: Boolean,
    view: CalendarView,
    dateColor: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val bar = day.outlook?.takeIf { !day.isPast }
    val edge = when {
        selected -> CarColors.accent
        day.needDay -> day.outlook.tone().color
        else -> Color.Transparent
    }
    Column(
        modifier
            .clip(shape)
            .background(if (day.isToday) CarColors.surfaceHigh else CarColors.surface)
            .border(if (selected || day.needDay) 3.dp else 0.dp, edge, shape)
            .clickable(onClick = onClick)
            .alpha(if (inMonth) 1f else 0.4f)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${day.date.dayOfMonth}", style = cellDate, color = dateColor)
            Spacer(Modifier.weight(1f))
            if (day.isToday) Text("오늘", style = chipText, color = CarColors.accent)
        }
        val km = if (day.isPast) day.drivenKm else day.plannedKm
        if (km >= 0.5) Text(Korean.km(km), style = cellText, color = if (day.isPast) CarColors.textDim else CarColors.text)
        if (day.skipRoutine) Text("평소 쉼", style = cellText, color = CarColors.yellow)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (day.plannedCount > 0) Chip("일정 ${day.plannedCount}", CarColors.accent)
            if (day.hasFill) Chip("채우기", CarColors.green)
        }
        if (bar != null && !day.afterNeed(view.needDate)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(bar.tone().color))
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        style = chipText,
        color = Color.Black,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Verdict.ENOUGH, Verdict.TIGHT, Verdict.SHORT).forEach { v ->
            Box(Modifier.size(width = 22.dp, height = 8.dp).clip(RoundedCornerShape(3.dp)).background(v.tone().color))
            Text(v.word(), style = cellText, color = CarColors.textDim)
        }
        Text("· 채우지 않고 다닐 때, 오늘부터 2주", style = cellText, color = CarColors.textDim)
    }
}

/** 고른 날의 요약과 항목. 지난 날은 기록만 보여주고 고칠 수 없다. */
@Composable
private fun DayPanel(day: CalendarDay, view: CalendarView, ui: MiriUi, vm: MiriViewModel) {
    Panel(Modifier.fillMaxWidth(), title = Korean.spokenDate(day.date) + if (day.isToday) " · 오늘" else "") {
        Text(summary(day, view), style = MaterialTheme.typography.bodyLarge, color = if (day.needDay) day.outlook.tone().color else CarColors.text)
        if (day.needDay) Text("이날 전에 채우는 게 좋아요", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        if (day.entries.isEmpty()) Text(if (day.isPast) "운행 기록이 없어요" else "일정이 없어요", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        day.entries.forEach { EntryRow(it, day, vm) }
        if (!day.isPast) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarButton("일정 추가", { vm.openNewTrip(day.date) }, Modifier.weight(1f))
                MicButton(listening = ui.voice is VoiceUi.Listening, onClick = vm::startVoice, size = CarDimens.touchTarget)
            }
            if (day.hasRoutine || day.skipRoutine) {
                CarButton(
                    if (day.skipRoutine) "평소 이동 되돌리기" else "이날은 평소대로 안 다녀요",
                    { vm.toggleSkipDay(day.date) },
                    Modifier.fillMaxWidth(),
                    primary = false,
                )
            }
        }
    }
}

private fun summary(day: CalendarDay, view: CalendarView): String {
    if (day.isPast) return if (day.drivenKm > 0) "달린 거리 ${Korean.km(day.drivenKm)}" else "달린 기록 없음"
    val planned = "예상 ${Korean.km(day.plannedKm)}"
    val cumulative = day.cumulativeKm
    return when {
        cumulative == null -> "$planned · 2주 뒤라 전망에는 넣지 않아요"
        day.afterNeed(view.needDate) -> "$planned · 채운 뒤에 다시 계산해요"
        else -> "$planned · 지금부터 누적 ${Korean.km(cumulative)} · ${day.outlook?.word() ?: ""}"
    }
}

@Composable
private fun EntryRow(e: CalendarEntry, day: CalendarDay, vm: MiriViewModel) {
    val (label, color) = when (e.kind) {
        EntryKind.PLANNED -> (if (e.trip?.weekly == true) "매주" else "일정") to CarColors.accent
        EntryKind.ROUTINE -> "평소" to CarColors.textDim
        EntryKind.ESTIMATE -> "추정" to CarColors.textDim
        EntryKind.DRIVEN -> "기록" to CarColors.gray
        EntryKind.FILL -> "채우기" to CarColors.green
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CarColors.surfaceHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip(label, color)
            if (e.kind != EntryKind.ESTIMATE) Text(timeText(e), style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
            Text(
                e.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (e.skipped) TextDecoration.LineThrough else TextDecoration.None,
                color = if (e.skipped) CarColors.textDim else CarColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            e.km?.let { Text(if (e.kind == EntryKind.PLANNED) "왕복 약 ${copy.approxKm(it)}" else Korean.km(it), style = MaterialTheme.typography.bodyLarge) }
        }
        detail(e)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim) }
        // F-26: 이 일정만 다녀올 때 출발 전에 있어야 할 잔량.
        e.guide?.takeIf { !day.isPast && !e.skipped }?.let { g ->
            Text(
                if (g.recommended.overFull) "왕복 약 ${copy.approxKm(g.roundTripKm)} · 한 번 충전으로 모자라 가는 길에 충전 필요"
                else "출발 전 최소 ${copy.levelText(g.minimum)} · 권장 ${copy.levelText(g.recommended)} (왕복 약 ${copy.approxKm(g.roundTripKm)})",
                style = MaterialTheme.typography.bodyLarge,
                color = CarColors.accent,
            )
        }
        val trip = e.trip
        if (e.kind == EntryKind.PLANNED && trip != null && !day.isPast) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (e.km == null) CarButton("장소 고르기", { vm.startPickPlace(trip) })
                CarButton("수정", { vm.openEditTrip(trip) }, primary = false)
                CarButton(if (trip.weekly) "매주 일정 삭제" else "삭제", { vm.deleteTrip(trip.id) }, primary = false)
            }
        }
    }
}

/** 평소 이동은 2시간 칸으로 배운 것이라 "오후 6시~8시"처럼 칸으로 보여준다 (R-03). */
private fun timeText(e: CalendarEntry): String {
    val start = Korean.spokenTime(e.at.toLocalTime())
    if (e.kind != EntryKind.ROUTINE) return start
    val end = e.at.plusHours(WeekSlot.HOURS_PER_SLOT.toLong()).hour.let { if (it % 12 == 0) 12 else it % 12 }
    return "$start~${end}시"
}

private fun detail(e: CalendarEntry): String? = when (e.kind) {
    EntryKind.ROUTINE -> buildString {
        append("최근 ${e.windowWeeks}주 중 ${e.weeksSeen}주 같은 시간에 다녔어요")
        if (e.skipped) append(" · 이날은 빼고 계산")
    }
    EntryKind.PLANNED -> when {
        e.km == null -> "장소를 골라주세요. 고르기 전에는 계산에 넣지 않아요"
        e.trip?.weekly == true -> "매주 ${Korean.dayName(e.at.dayOfWeek)} 반복"
        else -> null
    }
    EntryKind.FILL -> "48시간 계획의 1순위 방법이에요"
    else -> null
}

/** SC-13 일정 추가·고치기 (F-23, F-24). 주행을 시작하면 닫힌다. */
@Composable
internal fun TripFormPanel(form: TripForm, demo: Boolean, vm: MiriViewModel, modifier: Modifier) {
    Panel(
        modifier.widthIn(max = 1240.dp).padding(CarDimens.pad).heightIn(max = 1100.dp).verticalScroll(rememberScrollState()),
        title = if (form.editing != null) "일정 고치기" else "일정 추가",
    ) {
        FormRow("날짜") {
            CarButton("◀", { vm.formShiftDate(-1) }, primary = false)
            Text(Korean.spokenDate(form.date), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 340.dp))
            CarButton("▶", { vm.formShiftDate(1) }, primary = false)
        }
        FormRow("시각") {
            CarButton("-1시간", { vm.formShiftTime(-60) }, primary = false)
            CarButton("-10분", { vm.formShiftTime(-10) }, primary = false)
            Text(Korean.spokenTime(form.time), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 260.dp))
            CarButton("+10분", { vm.formShiftTime(10) }, primary = false)
            CarButton("+1시간", { vm.formShiftTime(60) }, primary = false)
        }
        FormRow("반복") {
            CarButton("한 번", { vm.formSetWeekly(false) }, primary = !form.weekly)
            CarButton("매주 ${Korean.dayName(form.date.dayOfWeek)}", { vm.formSetWeekly(true) }, primary = form.weekly)
        }
        FormRow("장소") {
            OutlinedTextField(
                value = form.query,
                onValueChange = vm::formSetQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("예: 대전 정부청사", style = MaterialTheme.typography.bodyLarge) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.formSearch() }),
                modifier = Modifier.weight(1f),
            )
            CarButton("찾기", { vm.formSearch() })
        }
        if (demo) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("시연", style = MaterialTheme.typography.bodyMedium, color = CarColors.yellow)
                DEMO_PLACES.forEach { p -> CarButton(p, { vm.formSearch(p) }, primary = false) }
            }
        }
        if (form.searched && form.candidates.isEmpty()) Text("찾은 장소가 없어요", style = MaterialTheme.typography.bodyLarge, color = CarColors.textDim)
        form.candidates.forEach { c ->
            val chosen = c == form.chosen
            CarButton(if (chosen) "선택함 · ${c.name}" else c.name, { vm.formChoose(c) }, Modifier.fillMaxWidth(), primary = chosen)
        }
        Text("일정 제목·참석자는 받지 않아요. 날짜·시각·장소만 저장해요.", style = MaterialTheme.typography.bodyMedium, color = CarColors.textDim)
        form.error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = CarColors.yellow) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarButton("저장", vm::saveForm, Modifier.weight(1f), enabled = form.chosen != null)
            if (form.editing != null) CarButton("삭제", vm::deleteFromForm, Modifier.weight(1f), primary = false)
            CarButton("취소", vm::cancelForm, Modifier.weight(1f), primary = false)
        }
    }
}

@Composable
private fun FormRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = CarColors.textDim, modifier = Modifier.width(96.dp))
        content()
    }
}

/** 시연 모드에서 화면 키보드 대신 누르는 장소. 시연 장소 목록(DemoPlaces)에 있는 이름. */
private val DEMO_PLACES = listOf("대전", "부산", "강릉", "인천공항", "광교")
