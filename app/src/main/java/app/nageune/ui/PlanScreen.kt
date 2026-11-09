package app.nageune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nageune.DayViewModel
import app.nageune.DayViewModel.GeoStatus
import app.nageune.trip.Visit

/**
 * 오늘 일정 편집(정차 중에만). 왼쪽 목록에서 순서를 바꾸거나 지우고, 오른쪽 입력칸에서 방문을 더하거나 고친다.
 * 주소는 "주소로 위치 찾기"로 좌표를 정하거나, 지금 차가 있는 곳을 그대로 쓸 수 있다.
 * 하루가 시작된 뒤에는 끝났거나 지금 가는 방문은 잠기고 남은 방문만 고칠 수 있다.
 */
@Composable
fun PlanContent(vm: DayViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val geoStatus by vm.geoStatus.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val locked = vm.lockedIds()
    val missing = draft.count { !it.hasLocation }

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
                SecondaryButton("← 취소", Modifier.width(if (narrow) 150.dp else 180.dp), vm::cancelPlan)
                Text("오늘 일정", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Text("방문 ${draft.size}곳", fontSize = 22.sp, color = Palette.muted)
                Spacer(Modifier.weight(1f))
                if (vm.demoMode && !state.started && !narrow) {
                    SecondaryButton("예시 일정", Modifier.width(180.dp), vm::loadDemoDraft)
                }
                BigButton("일정 저장", Modifier.width(if (narrow) 180.dp else 220.dp)) { vm.savePlan() }
            }
            if (missing > 0) {
                Text(
                    "위치를 못 정한 방문이 ${missing}곳 있어요. 위치를 정해야 저장할 수 있어요",
                    fontSize = 20.sp, color = Palette.warn,
                )
            }
            if (narrow) {
                if (editing != null) {
                    EditForm(vm, editing!!, geoStatus, Modifier.weight(1f).fillMaxWidth())
                } else {
                    PlanList(vm, draft, locked, Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PlanList(vm, draft, locked, Modifier.weight(0.46f).fillMaxHeight())
                    val e = editing
                    if (e != null) {
                        EditForm(vm, e, geoStatus, Modifier.weight(0.54f).fillMaxHeight())
                    } else {
                        Box(
                            Modifier
                                .weight(0.54f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Palette.surface)
                                .padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "왼쪽에서 고칠 방문을 고르거나 ‘방문 추가’를 눌러 주세요",
                                fontSize = 24.sp, color = Palette.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanList(vm: DayViewModel, draft: List<Visit>, locked: Set<String>, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surface)
            .padding(24.dp),
    ) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(draft, key = { _, v -> v.id }) { i, v ->
                PlanRow(vm, v, i, draft.size, v.id in locked)
            }
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("+ 방문 추가", Modifier.fillMaxWidth(), vm::draftNew)
    }
}

@Composable
private fun PlanRow(vm: DayViewModel, v: Visit, index: Int, size: Int, locked: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (locked) Palette.surfaceSoft else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (locked) Palette.line else Palette.surface)
                .border(2.dp, Palette.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("${index + 1}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.muted) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.customer.ifBlank { "(이름 없음)" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = if (locked) Palette.muted else Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(v.service, v.window).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "-" },
                fontSize = 18.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (v.hasLocation) v.address else "위치 없음 · ${v.address.ifBlank { "주소 없음" }}",
                fontSize = 17.sp, color = if (v.hasLocation) Palette.faint else Palette.warn,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (locked) {
            Text("고칠 수 없음", fontSize = 17.sp, color = Palette.faint)
        } else {
            SmallAction("▲", index > 0) { vm.draftMove(v.id, -1) }
            SmallAction("▼", index < size - 1) { vm.draftMove(v.id, +1) }
            SmallAction("고치기", true) { vm.draftEdit(v.id) }
            SmallAction("지우기", true, Palette.warn) { vm.draftDelete(v.id) }
        }
    }
}

@Composable
private fun SmallAction(text: String, enabled: Boolean, color: Color = Palette.accent, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Palette.surfaceSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) color else Palette.line)
    }
}

@Composable
private fun EditForm(vm: DayViewModel, v: Visit, geo: GeoStatus, modifier: Modifier) {
    val isNew = vm.draft.value.none { it.id == v.id }
    val (start, end) = v.window.split("~").let { (it.getOrNull(0) ?: "").trim() to (it.getOrNull(1) ?: "").trim() }
    fun window(s: String, e: String) = listOf(s.trim(), e.trim()).filter { it.isNotEmpty() }.joinToString("~")

    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.surface)
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (isNew) "방문 추가" else "방문 고치기", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Field("고객", v.customer, "예: 김민지 고객") { vm.updateEditing(v.copy(customer = it)) }
        Field("작업 내용", v.service, "예: 정수기 정기 점검") { vm.updateEditing(v.copy(service = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("방문 시작", start, "10:30", Modifier.weight(1f)) { vm.updateEditing(v.copy(window = window(it, end))) }
            Field("끝", end, "11:00", Modifier.weight(1f)) { vm.updateEditing(v.copy(window = window(start, it))) }
        }
        Field("주소", v.address, "예: 성남시 분당구 정자동") { vm.updateEditing(v.copy(address = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("주소로 위치 찾기", Modifier.weight(1f), vm::findLocation)
            SecondaryButton("지금 위치로", Modifier.weight(1f), vm::useCurrentLocation)
        }
        val (geoText, geoColor) = when (geo) {
            GeoStatus.Searching -> "위치를 찾는 중이에요…" to Palette.muted
            is GeoStatus.Found -> "위치 확인: ${geo.label} (${geo.source})" to Palette.ok
            is GeoStatus.Failed -> geo.message to Palette.warn
            GeoStatus.Idle -> (
                if (v.hasLocation) "위치: %.5f, %.5f".format(v.lat, v.lng) else "아직 위치를 정하지 않았어요"
                ) to (if (v.hasLocation) Palette.muted else Palette.warn)
        }
        Text(geoText, fontSize = 19.sp, color = geoColor)
        Field("메모", v.note, "예: 지하주차장 방문 차량 등록 필요", singleLine = false) { vm.updateEditing(v.copy(note = it)) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton("닫기", Modifier.weight(1f), vm::cancelEditing)
            val ok = v.customer.isNotBlank() && v.hasLocation
            BigButton(if (isNew) "목록에 넣기" else "고친 내용 넣기", Modifier.weight(2f)) { if (ok) vm.commitEditing() }
        }
        if (v.customer.isBlank() || !v.hasLocation) {
            Text("고객 이름과 위치가 있어야 넣을 수 있어요", fontSize = 17.sp, color = Palette.faint)
        }
    }
}

@Composable
internal fun Field(
    label: String, value: String, hint: String, modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 18.sp) },
        placeholder = { Text(hint, fontSize = 20.sp, color = Palette.faint) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        textStyle = TextStyle(fontSize = 22.sp, color = Palette.text),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = modifier,
    )
}
