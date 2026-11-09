package app.nageune.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nageune.trip.TripConfig
import app.nageune.trip.TripEngine
import app.nageune.trip.TripEvent
import app.nageune.trip.TripState
import app.nageune.trip.Visit
import app.nageune.trip.VisitStatus

/**
 * 주행 중 화면. Pleos 디자인 지침(1~2초 안에 읽히는 핵심 정보만, 조작 요구 없음,
 * 영상·애니메이션·자동 스크롤 없음)에 맞춰 버튼·목록·메모를 모두 뺐다.
 * 보여주는 것: 다음 방문 고객, 도착까지 남은 시간·거리, 고객 알림 여부, 그다음 방문 한 줄.
 *
 * @param maxLen 주행 중 글자 수 제한(UX_RESTRICTIONS_LIMIT_STRING_LENGTH)이 걸렸을 때 최대 길이.
 */
@Composable
fun DrivingContent(
    s: TripState, now: Long, cfg: TripConfig, demo: Boolean, maxLen: Int?, onEvent: (TripEvent) -> Unit,
) {
    val fit: (String) -> String = { str ->
        if (maxLen != null && str.length > maxLen) str.take((maxLen - 1).coerceAtLeast(1)) + "…" else str
    }
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("나그네", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
                Chip("주행 중", Tone.Accent)
                if (demo) Chip("시연 모드", Tone.Warn)
                Spacer(Modifier.weight(1f))
                if (s.started) {
                    Text(
                        "${s.doneCount}/${s.visits.size}곳",
                        fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted,
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Palette.surface)
                    .padding(if (narrow) 40.dp else 56.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                DrivingCard(s, now, cfg, narrow, fit, onEvent)
            }
            val cur = s.current
            val next = cur?.let { c -> s.visits.firstOrNull { it.order > c.order && !it.status.finished } }
            if (s.started && next != null) {
                Text(
                    fit("그다음 · ${next.customer} · ${shortAddr(next.address)} · ${next.window}"),
                    fontSize = 28.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DrivingCard(
    s: TripState, now: Long, cfg: TripConfig, narrow: Boolean, fit: (String) -> String, onEvent: (TripEvent) -> Unit,
) {
    val cur = s.current
    val nameSize = if (narrow) 60.sp else 88.sp
    when {
        !s.started -> {
            Big("출발 전이에요", 60.sp, Palette.text)
            Sub("차를 세우면(P) 오늘 일정을 시작할 수 있어요", fit)
        }

        cur == null -> {
            Big("오늘 방문을 모두 마쳤어요", if (narrow) 52.sp else 60.sp, Palette.text)
            val driven = s.drivenKm
            if (driven != null) Sub("주행 %.1f km · 운행기록부는 정차 후 확인".format(driven), fit)
        }

        cur.status == VisitStatus.EN_ROUTE -> EnRoute(s, cur, now, cfg, narrow, nameSize, fit) {
            onEvent(TripEvent.NotifyLate(cur.id))
        }

        cur.status == VisitStatus.ARRIVED -> {
            Label("도착", Palette.accent)
            Big(fit(cur.customer), nameSize, Palette.text)
            Spacer(Modifier.height(12.dp))
            Sub("차를 세우면(P) 작업 시작으로 기록해요", fit)
        }

        cur.status == VisitStatus.IN_SERVICE -> {
            Label("작업 마무리 중", Palette.warn)
            Big(fit(cur.customer), nameSize, Palette.text)
            Spacer(Modifier.height(12.dp))
            Sub("방문지를 벗어나면 완료로 기록해요", fit)
        }
    }
}

@Composable
private fun EnRoute(
    s: TripState, v: Visit, now: Long, cfg: TripConfig, narrow: Boolean, nameSize: TextUnit, fit: (String) -> String,
    onLate: () -> Unit,
) {
    val eta = TripEngine.eta(s, v, now, cfg)
    val place = fit("${shortAddr(v.address)} · 약속 ${v.window}")
    if (narrow) {
        Label("다음 방문", Palette.muted)
        Big(fit(v.customer), nameSize, Palette.text)
        Text(place, fontSize = 30.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(32.dp))
        EtaFigure(eta?.seconds, eta?.meters, numberSize = 150.sp)
        Spacer(Modifier.height(28.dp))
        NoticeBadge(v)
        Spacer(Modifier.height(24.dp))
        LateAction(v, eta != null, Modifier.fillMaxWidth(), onLate)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("다음 방문", Palette.muted)
                Big(fit(v.customer), nameSize, Palette.text)
                Text(place, fontSize = 38.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(40.dp))
                NoticeBadge(v)
                Spacer(Modifier.height(28.dp))
                LateAction(v, eta != null, Modifier.width(560.dp), onLate)
            }
            Spacer(Modifier.width(32.dp))
            EtaFigure(eta?.seconds, eta?.meters, numberSize = 220.sp)
        }
    }
}

@Composable
private fun EtaFigure(seconds: Int?, meters: Int?, numberSize: TextUnit) {
    Column(horizontalAlignment = Alignment.End) {
        if (seconds == null) {
            Text("위치 확인 중", fontSize = 44.sp, color = Palette.muted)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                minutes(seconds).toString(), fontSize = numberSize, fontWeight = FontWeight.Bold,
                color = Palette.text, lineHeight = numberSize,
            )
            Text("분", fontSize = 60.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
                modifier = Modifier.padding(start = 8.dp, bottom = 24.dp))
        }
        if (meters != null) Text("%.1f km".format(meters / 1000.0), fontSize = 46.sp, color = Palette.muted)
    }
}

@Composable
private fun NoticeBadge(v: Visit) {
    val sent = v.notifiedAt != null
    Text(
        if (sent) "고객 알림 보냄 ${hhmm(v.notifiedAt)}" else "도착 10분 전 고객 알림",
        fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
        color = if (sent) Palette.ok else Palette.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (sent) Palette.okSoft else Palette.surfaceSoft)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

/**
 * 주행 중 유일한 조작: 버튼 한 번으로 고객에게 늦는다고 알린다. 보낸 뒤에는 버튼 대신 보낸 시각만 보인다.
 */
@Composable
private fun LateAction(v: Visit, enabled: Boolean, modifier: Modifier, onLate: () -> Unit) {
    if (v.lateNotifiedAt != null) {
        Text(
            "늦는다고 알림 보냄 ${hhmm(v.lateNotifiedAt)}",
            fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Palette.warn,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Palette.warnSoft)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
        return
    }
    OutlinedButton(
        onClick = onLate,
        enabled = enabled,
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(2.dp, Palette.warn),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.warn),
    ) { Text("늦어요 · 고객에게 알리기", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Label(text: String, color: Color) {
    Text(text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = color)
}

@Composable
private fun Big(text: String, size: TextUnit, color: Color) {
    Text(
        text, fontSize = size, fontWeight = FontWeight.Bold, color = color,
        lineHeight = size * 1.15f, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Sub(text: String, fit: (String) -> String) {
    Text(fit(text), fontSize = 34.sp, color = Palette.muted, lineHeight = 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
