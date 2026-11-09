package app.nageune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nageune.DayViewModel
import app.nageune.trip.ArrivalSource
import app.nageune.trip.Eta
import app.nageune.trip.Gear
import app.nageune.trip.Profile
import app.nageune.trip.TripConfig
import app.nageune.trip.TripEngine
import app.nageune.trip.TripEvent
import app.nageune.trip.TripState
import app.nageune.trip.Visit
import app.nageune.trip.VisitStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DayScreen(vm: DayViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val ux by vm.ux.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val nav = Nav(openLog = vm::openLog, openPlan = vm::openPlan, openProfile = vm::openProfile, newDay = vm::startNewDay)
    NageuneTheme {
        when {
            // 주행 중 최적화가 필요하면(정차 P가 아니면) 어느 화면에 있든 한눈에 보는 주행 화면만 보여준다.
            ux.driving -> DrivingContent(s, now, vm.config, vm.demoMode, ux.maxStringLength, vm::post)
            screen == DayViewModel.Screen.LOG -> LogContent(
                records = vm.logRows(records),
                today = vm.todayPreview(),
                profile = profile,
                demo = vm.demoMode,
                onBack = vm::closeLog,
                onEditProfile = vm::openProfile,
            )
            screen == DayViewModel.Screen.PLAN -> PlanContent(vm)
            screen == DayViewModel.Screen.PROFILE -> ProfileContent(profile, vm::saveProfile, vm::closeLog)
            else -> DayContent(s, now, vm.config, vm.demoMode, vm::post, nav, profile)
        }
    }
}

/** 정차 화면에서 다른 화면으로 가는 길. */
data class Nav(
    val openLog: () -> Unit, val openPlan: () -> Unit, val openProfile: () -> Unit, val newDay: () -> Unit,
)

/** 이 폭보다 좁으면(내비와 화면을 반씩 나눈 세로 창) 위아래로 쌓는다. */
internal val NarrowWidth = 1100.dp

@Composable
fun DayContent(
    s: TripState, now: Long, cfg: TripConfig, demo: Boolean, onEvent: (TripEvent) -> Unit, nav: Nav, profile: Profile,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.bg),
    ) {
        val narrow = maxWidth < NarrowWidth
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (narrow) 24.dp else 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 20.dp),
        ) {
            TopBar(s, now, demo, compact = narrow, nav = nav)
            if (narrow) {
                DetailPane(s, now, cfg, onEvent, nav, profile, Modifier.weight(0.66f).fillMaxWidth())
                VisitList(s, now, cfg, Modifier.weight(0.34f).fillMaxWidth())
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    VisitList(s, now, cfg, Modifier.weight(0.42f).fillMaxHeight())
                    DetailPane(s, now, cfg, onEvent, nav, profile, Modifier.weight(0.58f).fillMaxHeight())
                }
                LogTicker(s)
            }
        }
    }
}

// ---------------------------------------------------------------- 상단

@Composable
private fun TopBar(s: TripState, now: Long, demo: Boolean, compact: Boolean, nav: Nav) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("나그네", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Text(dateLabel(now), fontSize = 22.sp, color = Palette.muted)
        }
        Spacer(Modifier.weight(1f))
        val (gearText, gearTone) = when (s.gear) {
            Gear.PARK -> "정차 중 · P" to Tone.Neutral
            Gear.DRIVE -> "주행 중 · D" to Tone.Accent
            Gear.REVERSE -> "후진 · R" to Tone.Accent
            Gear.NEUTRAL -> "중립 · N" to Tone.Neutral
            Gear.UNKNOWN -> "기어 확인 중" to Tone.Neutral
        }
        Chip(gearText, gearTone)
        if (!compact) Chip("계기판 ${s.odometerKm?.let { "%,.1f km".format(it) } ?: "-"}", Tone.Neutral)
        val driven = s.drivenKm
        if (s.started && driven != null) Chip("오늘 %.1f km".format(driven), Tone.Neutral)
        if (demo) Chip("시연 모드", Tone.Warn)
        ActionChip("일정", nav.openPlan)
        ActionChip("운행기록부", nav.openLog)
    }
}

/** 눌러서 다른 화면으로 가는 칩. 정차 화면에서만 쓴다. */
@Composable
internal fun ActionChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Palette.text)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
}

internal enum class Tone { Neutral, Accent, Ok, Warn }

@Composable
internal fun Chip(text: String, tone: Tone) {
    val (bg, fg) = when (tone) {
        Tone.Neutral -> Palette.surface to Palette.text
        Tone.Accent -> Palette.accentSoft to Palette.accent
        Tone.Ok -> Palette.okSoft to Palette.ok
        Tone.Warn -> Palette.warnSoft to Palette.warn
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 20.sp, color = fg, fontWeight = FontWeight.Medium) }
}

// ---------------------------------------------------------------- 왼쪽 목록

@Composable
private fun VisitList(s: TripState, now: Long, cfg: TripConfig, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surface)
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("오늘 방문", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            Spacer(Modifier.weight(1f))
            Text("${s.doneCount}/${s.visits.size} 완료", fontSize = 20.sp, color = Palette.muted)
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(s.visits, key = { it.id }) { v ->
                val current = s.started && v.id == s.current?.id
                VisitRow(v, current, if (v.status == VisitStatus.EN_ROUTE) TripEngine.eta(s, v, now, cfg) else null)
            }
        }
    }
}

@Composable
private fun VisitRow(v: Visit, current: Boolean, eta: Eta?) {
    val finished = v.status.finished
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (current) Palette.accentSoft else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrderBadge(v, current)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.customer, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                color = if (finished) Palette.muted else Palette.text,
            )
            Text(
                "${v.service} · ${v.window}", fontSize = 19.sp, color = Palette.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        val (label, color) = when (v.status) {
            VisitStatus.DONE -> "완료 ${hhmm(v.finishedAt)}" to Palette.ok
            VisitStatus.SKIPPED -> "건너뜀" to Palette.faint
            VisitStatus.EN_ROUTE -> (eta?.let { "${minutes(it.seconds)}분" } ?: "가는 중") to Palette.accent
            VisitStatus.ARRIVED -> "도착" to Palette.accent
            VisitStatus.IN_SERVICE -> "작업 중" to Palette.warn
            VisitStatus.PENDING -> "" to Palette.muted
        }
        if (label.isNotEmpty()) Text(label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun OrderBadge(v: Visit, current: Boolean) {
    val (bg, fg, text) = when {
        v.status == VisitStatus.DONE -> Triple(Palette.ok, Color.White, "✓")
        v.status == VisitStatus.SKIPPED -> Triple(Palette.line, Palette.muted, "–")
        current -> Triple(Palette.accent, Color.White, v.order.toString())
        else -> Triple(Palette.surface, Palette.muted, v.order.toString())
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .border(2.dp, if (bg == Palette.surface) Palette.line else bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = fg) }
}

// ---------------------------------------------------------------- 오른쪽 상세

@Composable
private fun DetailPane(
    s: TripState, now: Long, cfg: TripConfig, onEvent: (TripEvent) -> Unit, nav: Nav, profile: Profile, modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surface)
            .padding(40.dp),
    ) {
        val cur = s.current
        when {
            !s.started -> StartCard(s, onEvent, nav, profile)
            cur == null -> SummaryCard(s, onEvent, nav)
            else -> CurrentCard(s, cur, now, cfg, onEvent)
        }
    }
}

@Composable
private fun ColumnScope.StartCard(s: TripState, onEvent: (TripEvent) -> Unit, nav: Nav, profile: Profile) {
    val first = s.visits.firstOrNull()
    Text("출발 전", fontSize = 20.sp, color = Palette.muted)
    Text("오늘 방문 ${s.visits.size}곳", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Palette.text)
    if (first != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            "첫 방문 ${first.customer} · ${shortAddr(first.address)} · ${first.window}",
            fontSize = 24.sp, color = Palette.muted,
        )
    }
    Spacer(Modifier.height(36.dp))
    Bullet("내비에 3곳씩 경로를 넣고, 한 묶음을 마치면 다음 3곳을 이어서 넣어요")
    Bullet("도착 10분 전이 되면 고객에게 도착 예정 알림을 보내요")
    Bullet("방문지에서 차를 세우고(P) 운전석 문을 열면 작업 시작으로 기록해요")
    Bullet("출발할 때와 도착할 때 계기판 거리를 남겨 운행기록부를 채워요")
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${profile.dept} ${profile.user} · ${profile.carModel} ${profile.plate}",
            fontSize = 20.sp, color = Palette.muted, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "내 정보·차량 고치기", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = nav.openProfile)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton("일정 편집", Modifier.weight(1f), nav.openPlan)
        if (s.visits.isEmpty()) {
            BigButton("일정 넣기", Modifier.weight(2f), nav.openPlan)
        } else {
            BigButton("출발 · 경로 넣기", Modifier.weight(2f)) { onEvent(TripEvent.StartDay) }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 12.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(Palette.accent),
        )
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 23.sp, color = Palette.text, lineHeight = 32.sp)
    }
}

@Composable
private fun ColumnScope.CurrentCard(s: TripState, v: Visit, now: Long, cfg: TripConfig, onEvent: (TripEvent) -> Unit) {
    Steps(v.status)
    Spacer(Modifier.height(28.dp))
    Text("${v.order}번째 방문 · 약속 ${v.window}", fontSize = 20.sp, color = Palette.muted)
    Text(v.customer, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Palette.text)
    Text(v.service, fontSize = 26.sp, color = Palette.text)
    Text(v.address, fontSize = 22.sp, color = Palette.muted)
    Spacer(Modifier.height(24.dp))
    StatusBlock(s, v, now, cfg)
    Spacer(Modifier.height(20.dp))
    InfoRow(
        "고객 알림",
        if (v.notifiedAt != null) {
            "${hhmm(v.notifiedAt)} 보냄 · 약 ${minutes(v.notifiedEtaSec ?: 0)}분 뒤 도착으로 안내"
        } else {
            "도착 10분 전에 자동으로 보내요"
        },
        if (v.notifiedAt != null) Palette.ok else Palette.text,
    )
    if (v.note.isNotBlank()) InfoRow("메모", v.note, Palette.text)
    Spacer(Modifier.weight(1f))
    val next = s.visits.firstOrNull { it.order > v.order && !it.status.finished }
    if (next != null) {
        Text(
            "다음 · ${next.customer} · ${shortAddr(next.address)} · ${next.window}",
            fontSize = 20.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        when (v.status) {
            VisitStatus.EN_ROUTE -> {
                SecondaryButton("건너뛰기", Modifier.weight(1f)) { onEvent(TripEvent.Skip(v.id)) }
                BigButton("도착 처리", Modifier.weight(2f)) { onEvent(TripEvent.ManualArrive(v.id)) }
            }
            VisitStatus.ARRIVED -> {
                SecondaryButton("건너뛰기", Modifier.weight(1f)) { onEvent(TripEvent.Skip(v.id)) }
                BigButton("작업 시작", Modifier.weight(2f)) { onEvent(TripEvent.ManualStart(v.id)) }
            }
            VisitStatus.IN_SERVICE ->
                BigButton("작업 완료", Modifier.fillMaxWidth()) { onEvent(TripEvent.ManualFinish(v.id)) }
            else -> Unit
        }
    }
}

@Composable
private fun Steps(status: VisitStatus) {
    val labels = listOf("가는 중", "도착", "작업 중", "완료")
    val idx = when (status) {
        VisitStatus.EN_ROUTE, VisitStatus.PENDING -> 0
        VisitStatus.ARRIVED -> 1
        VisitStatus.IN_SERVICE -> 2
        else -> 3
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            if (i > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(36.dp)
                        .height(2.dp)
                        .background(if (i <= idx) Palette.accent else Palette.line),
                )
            }
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (i <= idx) Palette.accent else Palette.line),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label, fontSize = 20.sp,
                fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    i == idx -> Palette.text
                    i < idx -> Palette.muted
                    else -> Palette.faint
                },
            )
        }
    }
}

@Composable
private fun StatusBlock(s: TripState, v: Visit, now: Long, cfg: TripConfig) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.surfaceSoft)
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        when (v.status) {
            VisitStatus.EN_ROUTE -> {
                val eta = TripEngine.eta(s, v, now, cfg)
                Text("도착까지", fontSize = 22.sp, color = Palette.muted)
                if (eta == null) {
                    Text("위치를 기다리는 중이에요", fontSize = 32.sp, color = Palette.text)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(minutes(eta.seconds).toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Palette.text, lineHeight = 76.sp)
                        Text("분", fontSize = 32.sp, color = Palette.text, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                        Spacer(Modifier.width(24.dp))
                        Text("%.1f km".format(eta.meters / 1000.0), fontSize = 28.sp, color = Palette.muted, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Text(
                        if (eta.fromNavi) "내비 기준" else "현재 위치에서 어림잡은 값",
                        fontSize = 18.sp, color = Palette.faint,
                    )
                }
            }
            VisitStatus.ARRIVED -> {
                Text("도착 ${hhmm(v.arrivedAt)}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
                Text(
                    "${sourceLabel(v.arrivalSource)} · 차를 세우고(P) 운전석 문을 열면 작업 시작으로 기록해요",
                    fontSize = 22.sp, color = Palette.muted, lineHeight = 30.sp,
                )
            }
            VisitStatus.IN_SERVICE -> {
                val mins = ((now - (v.startedAt ?: now)) / 60_000).coerceAtLeast(0)
                Text("작업 중 · ${mins}분째", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.warn)
                Text(
                    "${hhmm(v.startedAt)} 시작 · 다음 방문지로 출발하면 자동으로 완료돼요",
                    fontSize = 22.sp, color = Palette.muted, lineHeight = 30.sp,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: Color) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, fontSize = 20.sp, color = Palette.muted, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 20.sp, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColumnScope.SummaryCard(s: TripState, onEvent: (TripEvent) -> Unit, nav: Nav) {
    val done = s.visits.filter { it.status == VisitStatus.DONE }
    val skipped = s.visits.count { it.status == VisitStatus.SKIPPED }
    val closed = s.closedAt != null
    Text(if (closed) "운행 마감 ${hhmm(s.closedAt)}" else "하루 마감", fontSize = 20.sp, color = Palette.muted)
    Text("오늘 방문을 모두 마쳤어요", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Palette.text)
    Spacer(Modifier.height(32.dp))
    Stat("완료", "${done.size}곳" + if (skipped > 0) " · 건너뜀 ${skipped}곳" else "")
    val driven = s.drivenKm
    val startOdo = s.dayStartOdo
    val endOdo = s.closeOdo ?: s.odometerKm ?: s.dayEndOdo
    Stat(
        "주행",
        when {
            driven == null -> "계기판 값 없음"
            startOdo != null && endOdo != null -> "%.1f km (계기판 %,.1f → %,.1f)".format(driven, startOdo, endOdo)
            else -> "%.1f km".format(driven)
        },
    )
    val first = done.mapNotNull { it.arrivedAt }.minOrNull()
    val last = done.mapNotNull { it.finishedAt }.maxOrNull()
    if (first != null && last != null) Stat("방문 시간", "${hhmm(first)} ~ ${hhmm(last)}")
    Spacer(Modifier.weight(1f))
    if (closed) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton("새 하루 시작", Modifier.weight(1f), nav.newDay)
            BigButton("운행기록부 보기", Modifier.weight(2f), nav.openLog)
        }
    } else {
        Text(
            "퇴근해서 차를 세운 뒤 누르면 마지막 계기판까지 운행기록부에 남겨요",
            fontSize = 20.sp, color = Palette.muted, lineHeight = 28.sp,
        )
        Spacer(Modifier.height(16.dp))
        BigButton("운행 마감 · 기록부에 남기기", Modifier.fillMaxWidth()) { onEvent(TripEvent.CloseDay) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, fontSize = 22.sp, color = Palette.muted, modifier = Modifier.width(140.dp))
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
    }
}

// ---------------------------------------------------------------- 공통

@Composable
internal fun BigButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) { Text(text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) { Text(text, fontSize = 24.sp, color = Palette.text) }
}

@Composable
private fun LogTicker(s: TripState) {
    val last = s.log.firstOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.surface)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("기록", fontSize = 18.sp, color = Palette.muted)
        Spacer(Modifier.width(20.dp))
        Text(
            last?.let { "${hhmm(it.at)}  ${it.text}" } ?: "아직 기록이 없어요",
            fontSize = 20.sp, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun sourceLabel(src: ArrivalSource?) = when (src) {
    ArrivalSource.LOCATION -> "위치로 확인"
    ArrivalSource.NAVI -> "내비 도착"
    ArrivalSource.DOOR -> "주차·문 열림으로 확인"
    ArrivalSource.MANUAL -> "직접 처리"
    null -> "도착"
}

internal fun minutes(sec: Int) = ((sec + 59) / 60).coerceAtLeast(1)

internal fun shortAddr(addr: String) = addr.substringAfterLast(' ')

// 형식기는 만들 때의 시간대를 붙잡아 두므로 매번 새로 만든다(앱이 떠 있는 동안 기기 시간대가 바뀌어도 맞게).
internal fun hhmm(t: Long?) = t?.let { SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it)) } ?: "-"

private fun dateLabel(t: Long) = SimpleDateFormat("M월 d일 EEEE", Locale.KOREA).format(Date(t))
