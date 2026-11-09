package app.nageune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nageune.trip.DayRecord
import app.nageune.trip.DriveLog
import app.nageune.trip.LegKind
import app.nageune.trip.LogLeg
import app.nageune.trip.Profile

/**
 * 운행기록부 화면. 정차 중에만 보인다(주행 중엔 주행 화면이 덮는다).
 * 왼쪽: 국세청 「업무용승용차 운행기록부」 항목대로 하루 한 줄. 아래에 이번 달 업무사용비율.
 * 오른쪽: 고른 날의 구간별 계기판 기록(증빙용).
 */
@Composable
fun LogContent(
    records: List<DayRecord>, today: DayRecord?, profile: Profile, demo: Boolean, onBack: () -> Unit,
    onEditProfile: () -> Unit,
) {
    // 마감 전인 오늘은 미리보기로 맨 아래에 붙인다.
    val rows = if (today != null) DriveLog.upsert(records, today) else records
    var selected by remember(rows.size) { mutableStateOf(rows.lastOrNull()?.date) }
    val pick = rows.firstOrNull { it.date == selected } ?: rows.lastOrNull()
    val month = rows.lastOrNull()?.date?.let { "${it.substring(0, 4)}년 ${it.substring(5, 7).toInt()}월" } ?: ""

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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton("← 돌아가기", Modifier.width(if (narrow) 170.dp else 200.dp), onBack)
                Text("운행기록부", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                if (!narrow) Text(month, fontSize = 24.sp, color = Palette.muted)
                Spacer(Modifier.weight(1f))
                ActionChip("${profile.carModel} · ${profile.plate}", onEditProfile)
                if (!narrow) Chip("${profile.dept} ${profile.user}", Tone.Neutral)
                if (demo && rows.any { it.sample }) Chip("지난 기록은 예시", Tone.Warn)
            }
            if (rows.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Palette.surface),
                    contentAlignment = Alignment.Center,
                ) { Text("아직 기록이 없어요. 하루를 마감하면 여기에 한 줄씩 쌓여요.", fontSize = 26.sp, color = Palette.muted) }
            } else if (narrow) {
                LogTable(rows, pick?.date, { selected = it }, Modifier.weight(0.55f).fillMaxWidth(), compact = true)
                LegDetail(pick, today, Modifier.weight(0.45f).fillMaxWidth())
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LogTable(rows, pick?.date, { selected = it }, Modifier.weight(0.62f).fillMaxHeight(), compact = false)
                    LegDetail(pick, today, Modifier.weight(0.38f).fillMaxHeight())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 표

private val colWeights = listOf(1.15f, 1.3f, 1.3f, 1f, 1f, 1f, 1.9f)

@Composable
private fun LogTable(
    rows: List<DayRecord>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier, compact: Boolean,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surface)
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("업무용승용차 운행기록부", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            Spacer(Modifier.width(12.dp))
            Text("국세청 서식 항목 기준 · 단위 km", fontSize = 18.sp, color = Palette.muted)
        }
        Spacer(Modifier.height(16.dp))
        val headers = if (compact) listOf("날짜", "주행 전", "주행 후", "주행", "출·퇴근", "일반 업무", "비고")
        else listOf("사용일자", "주행 전 계기판", "주행 후 계기판", "주행거리", "출·퇴근용", "일반 업무용", "비고")
        Row(Modifier.padding(horizontal = 8.dp)) {
            headers.forEachIndexed { i, h -> Cell(h, i, 17.sp, Palette.muted, FontWeight.Medium) }
        }
        Line()
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.date }) { r ->
                val isSel = r.date == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) Palette.accentSoft else Color.Transparent)
                        .clickable { onSelect(r.date) }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val textColor = if (r.sample) Palette.muted else Palette.text
                    Cell("${r.date.substring(5, 7).toInt()}/${r.date.substring(8, 10).toInt()} (${r.weekday})", 0, 20.sp, textColor, FontWeight.SemiBold)
                    Cell("%,.1f".format(r.startOdo), 1, 20.sp, textColor)
                    Cell("%,.1f".format(r.endOdo), 2, 20.sp, textColor)
                    Cell("%.1f".format(r.totalKm), 3, 20.sp, textColor, FontWeight.SemiBold)
                    Cell("%.1f".format(r.commuteKm), 4, 20.sp, textColor)
                    Cell("%.1f".format(r.businessKm), 5, 20.sp, textColor)
                    Cell((if (r.sample) "예시 · " else "") + r.note, 6, 18.sp, Palette.muted)
                }
            }
        }
        Line()
        val sum = DriveLog.summarize(rows)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 36.dp)) {
            SumStat("총 주행거리", "%,.1f km".format(sum.totalKm))
            SumStat("업무용 사용거리", "%,.1f km".format(sum.workKm))
            SumStat("업무사용비율", sum.ratio?.let { "%.1f%%".format(it * 100) } ?: "-", Palette.accent)
            SumStat("기록 밖 주행", "%,.1f km".format(sum.personalKm), Palette.muted)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "기록 밖 주행은 기록과 기록 사이에 계기판이 늘어난 거리예요. 개인 용도로 보고 업무사용비율에서 빼요.",
            fontSize = 16.sp, color = Palette.faint, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowScope.Cell(text: String, col: Int, size: androidx.compose.ui.unit.TextUnit, color: Color, weight: FontWeight = FontWeight.Normal) {
    Text(
        text, fontSize = size, color = color, fontWeight = weight,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = if (col in 1..5) TextAlign.End else TextAlign.Start,
        modifier = Modifier
            .weight(colWeights[col])
            .padding(horizontal = 6.dp),
    )
}

@Composable
private fun SumStat(label: String, value: String, color: Color = Palette.text) {
    Column {
        Text(label, fontSize = 17.sp, color = Palette.muted)
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Line() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(Palette.line),
    )
}

// ---------------------------------------------------------------- 구간 상세

@Composable
private fun LegDetail(r: DayRecord?, today: DayRecord?, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surface)
            .padding(28.dp),
    ) {
        if (r == null) return@Column
        Text(
            "${r.date.substring(5, 7).toInt()}월 ${r.date.substring(8, 10).toInt()}일 (${r.weekday}) 구간 기록",
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.text,
        )
        Text("사용자 ${r.dept} ${r.user} · 방문 ${r.visitCount}곳", fontSize = 19.sp, color = Palette.muted)
        if (today != null && today.date == r.date) {
            Spacer(Modifier.height(8.dp))
            Text("아직 운행 마감 전이라 지금 계기판까지의 값이에요", fontSize = 19.sp, color = Palette.warn)
        }
        Spacer(Modifier.height(16.dp))
        if (r.legs.isEmpty()) {
            Text(
                if (r.sample) "예시 기록이라 구간 기록이 없어요" else "구간 기록이 없어요",
                fontSize = 22.sp, color = Palette.muted,
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(r.legs) { i, leg -> LegRow(leg, i, r.legs.lastIndex) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "출·퇴근 거리도 업무용 사용거리에 들어가요. 같은 날 여러 번 운행하면 하루 한 줄로 합쳐 적을 수 있어요.",
            fontSize = 16.sp, color = Palette.faint, lineHeight = 22.sp,
        )
    }
}

@Composable
private fun LegRow(leg: LogLeg, index: Int, last: Int) {
    val kind = when {
        leg.kind == LegKind.BUSINESS -> "일반 업무용"
        index == 0 -> "출근 · 출·퇴근용"
        index == last -> "퇴근 · 출·퇴근용"
        else -> "출·퇴근용"
    }
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${leg.from} → ${leg.to}", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "%,.1f → %,.1f · %s".format(leg.startOdo, leg.endOdo, kind) + (leg.at?.let { " · ${hhmm(it)}" } ?: ""),
                fontSize = 17.sp, color = if (leg.kind == LegKind.BUSINESS) Palette.muted else Palette.accent,
            )
        }
        Text("%.1f km".format(leg.km), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Palette.text)
    }
}
