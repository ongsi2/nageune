package app.nageune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nageune.trip.Profile

/**
 * 내 정보·차량. 운행기록부 머리말(사용자 부서·성명, 차종, 자동차등록번호)에 들어간다.
 * 차종과 등록번호는 일반 앱이 차에서 읽을 수 없어(등록번호는 비공개 권한) 직접 입력받는다.
 */
@Composable
fun ProfileContent(profile: Profile, onSave: (Profile) -> Unit, onBack: () -> Unit) {
    var user by rememberSaveable { mutableStateOf(profile.user) }
    var dept by rememberSaveable { mutableStateOf(profile.dept) }
    var car by rememberSaveable { mutableStateOf(profile.carModel) }
    var plate by rememberSaveable { mutableStateOf(profile.plate) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton("← 돌아가기", Modifier.width(200.dp), onBack)
            Text("내 정보·차량", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        }
        Column(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.surface)
                .padding(36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("운행기록부 머리말에 들어가요", fontSize = 22.sp, color = Palette.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("이름", user, "예: 홍길동", Modifier.weight(1f)) { user = it }
                Field("부서", dept, "예: 방문서비스팀", Modifier.weight(1f)) { dept = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("차종", car, "예: 아이오닉 5", Modifier.weight(1f)) { car = it }
                Field("자동차등록번호", plate, "예: 123가 4567", Modifier.weight(1f)) { plate = it }
            }
            Text(
                "차종과 자동차등록번호는 차에서 읽을 수 없어 직접 적어 주세요.",
                fontSize = 18.sp, color = Palette.faint,
            )
            Spacer(Modifier.height(8.dp))
            BigButton("저장", Modifier.fillMaxWidth()) {
                if (user.isNotBlank()) onSave(Profile(user, dept, car, plate))
            }
        }
    }
}
