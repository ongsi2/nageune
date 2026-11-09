package app.nageune

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nageune.car.CarSignals
import app.nageune.car.DrivingRestrictions
import app.nageune.car.Geo
import app.nageune.car.LocationTracker
import app.nageune.car.NaviBridge
import app.nageune.car.UxState
import app.nageune.trip.Gear
import app.nageune.data.DayStore
import app.nageune.data.LogStore
import app.nageune.trip.DayRecord
import app.nageune.trip.DemoDay
import app.nageune.trip.DriveLog
import app.nageune.trip.Profile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.nageune.trip.TripConfig
import app.nageune.trip.TripEffect
import app.nageune.trip.TripEngine
import app.nageune.trip.TripEvent
import app.nageune.trip.TripState
import app.nageune.trip.Visit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DayViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * 에뮬레이터에서는 내비 위치가 움직이지 않아(GPS NMEA 체크섬 문제) 내비 남은 시간이 틀린다.
     * 그래서 시연 모드에선 위치로 직접 추정한 값을 쓰고, 화면에 시연 모드라고 표시한다.
     */
    val demoMode: Boolean = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
    val config = TripConfig(preferNaviEta = !demoMode)

    private val main = Handler(Looper.getMainLooper())
    private val store = DayStore(app)

    private val _state = MutableStateFlow(store.load() ?: TripState(freshPlan(), date = today()))
    val state: StateFlow<TripState> = _state.asStateFlow()

    /** 화면의 "몇 분째" 같은 표시를 위한 1초 시계. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _notices = MutableStateFlow<List<String>>(emptyList())
    val notices: StateFlow<List<String>> = _notices.asStateFlow()

    // ---- 운행기록부 ----
    private val logStore = LogStore(app)

    /** 사용자·차량 정보. 차종·번호는 차에서 읽을 수 없어 사용자가 입력한다(처음엔 시연용 기본값). */
    private val _profile = MutableStateFlow(
        logStore.loadProfile()
            ?: Profile(user = "홍길동", dept = "방문서비스팀", carModel = "아이오닉 5", plate = "123가 4567"),
    )
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun openProfile() { _screen.value = Screen.PROFILE }

    fun saveProfile(p: Profile) {
        val clean = p.copy(user = p.user.trim(), dept = p.dept.trim(), carModel = p.carModel.trim(), plate = p.plate.trim())
        _profile.value = clean
        logStore.saveProfile(clean)
        debug("내 정보·차량 저장: ${clean.carModel} ${clean.plate}")
        _screen.value = Screen.DAY
    }

    // ---- 일정 편집(초안은 뷰모델에 둬서 창 크기가 바뀌어도 남게 한다) ----
    private val geo = Geo(app)

    private val _draft = MutableStateFlow<List<Visit>>(emptyList())
    val draft: StateFlow<List<Visit>> = _draft.asStateFlow()

    /** 오른쪽 입력칸에서 고치는 중인 방문(null이면 입력칸 닫힘). */
    private val _editing = MutableStateFlow<Visit?>(null)
    val editing: StateFlow<Visit?> = _editing.asStateFlow()

    sealed interface GeoStatus {
        data object Idle : GeoStatus
        data object Searching : GeoStatus
        data class Found(val label: String, val source: String) : GeoStatus
        data class Failed(val message: String) : GeoStatus
    }

    private val _geoStatus = MutableStateFlow<GeoStatus>(GeoStatus.Idle)
    val geoStatus: StateFlow<GeoStatus> = _geoStatus.asStateFlow()

    /** 끝났거나 지금 가는 방문은 편집할 수 없다. */
    fun lockedIds(): Set<String> {
        val s = _state.value
        val cur = if (s.started) s.current?.id else null
        return s.visits.filter { it.status.finished || it.id == cur }.map { it.id }.toSet()
    }

    fun openPlan() {
        _draft.value = _state.value.visits
        _editing.value = null
        _geoStatus.value = GeoStatus.Idle
        _screen.value = Screen.PLAN
    }

    fun draftMove(id: String, delta: Int) {
        val list = _draft.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        val locked = lockedIds()
        if (i < 0 || j < 0 || j >= list.size || list[j].id in locked || id in locked) return
        list.add(j, list.removeAt(i))
        _draft.value = list
    }

    fun draftDelete(id: String) {
        if (id in lockedIds()) return
        _draft.value = _draft.value.filter { it.id != id }
        if (_editing.value?.id == id) _editing.value = null
    }

    fun draftEdit(id: String) {
        if (id in lockedIds()) return
        _editing.value = _draft.value.firstOrNull { it.id == id }
        _geoStatus.value = GeoStatus.Idle
    }

    fun draftNew() {
        _editing.value = Visit(
            id = "n${System.currentTimeMillis()}", order = _draft.value.size + 1,
            customer = "", service = "", address = "", lat = 0.0, lng = 0.0, window = "",
        )
        _geoStatus.value = GeoStatus.Idle
    }

    fun updateEditing(v: Visit) { _editing.value = v }

    fun cancelEditing() {
        _editing.value = null
        _geoStatus.value = GeoStatus.Idle
    }

    /** 입력칸 내용을 초안에 넣는다(새 방문이면 끝에 붙인다). */
    fun commitEditing() {
        val v = _editing.value ?: return
        _draft.value = if (_draft.value.any { it.id == v.id }) {
            _draft.value.map { if (it.id == v.id) v else it }
        } else {
            _draft.value + v
        }
        cancelEditing()
    }

    fun findLocation() {
        val v = _editing.value ?: return
        if (v.address.isBlank()) {
            _geoStatus.value = GeoStatus.Failed("주소를 먼저 적어 주세요")
            return
        }
        _geoStatus.value = GeoStatus.Searching
        viewModelScope.launch {
            val p = geo.search(v.address)
            val cur = _editing.value
            if (p == null || cur == null || cur.id != v.id) {
                _geoStatus.value = GeoStatus.Failed("위치를 못 찾았어요. 동 이름이나 도로명까지 적어 보세요")
                return@launch
            }
            _editing.value = cur.copy(lat = p.lat, lng = p.lng)
            _geoStatus.value = GeoStatus.Found(p.label, p.source)
            debug("위치 찾음(${p.source}): ${v.address} → ${p.lat}, ${p.lng}")
        }
    }

    /** 지금 차가 있는 곳을 방문지로. 주소가 비어 있으면 위치로 주소를 채운다. */
    fun useCurrentLocation() {
        val v = _editing.value ?: return
        val s = _state.value
        val lat = s.lastLat
        val lng = s.lastLng
        if (lat == null || lng == null) {
            _geoStatus.value = GeoStatus.Failed("아직 현재 위치를 받지 못했어요")
            return
        }
        _editing.value = v.copy(lat = lat, lng = lng)
        _geoStatus.value = GeoStatus.Searching
        viewModelScope.launch {
            val label = geo.reverse(lat, lng)
            val cur = _editing.value ?: return@launch
            if (label != null && cur.address.isBlank()) _editing.value = cur.copy(address = label)
            _geoStatus.value = GeoStatus.Found(label ?: "현재 위치", "현재 위치")
        }
    }

    fun loadDemoDraft() {
        if (_state.value.started) return
        _draft.value = DemoDay.visits()
        cancelEditing()
    }

    /** 초안을 일정으로 확정한다. 위치가 없는 방문이 있으면 저장하지 않는다. */
    fun savePlan(): Boolean {
        if (_draft.value.any { !it.hasLocation }) return false
        post(TripEvent.EditPlan(_draft.value))
        _screen.value = Screen.DAY
        return true
    }

    fun cancelPlan() {
        cancelEditing()
        _screen.value = Screen.DAY
    }

    /** 저장된 실제 운행기록(예시 기록은 저장하지 않는다). */
    private val _records = MutableStateFlow(logStore.load().filter { !it.sample })
    val records: StateFlow<List<DayRecord>> = _records.asStateFlow()

    /** 화면에 보여줄 기록: 시연 모드면 지난주 예시 + 저장된 기록. 예시는 첫 실제 기록 출발 계기판에 맞춰 이어 붙인다. */
    fun logRows(records: List<DayRecord>): List<DayRecord> {
        val real = todayPreview()?.let { DriveLog.upsert(records, it) } ?: records
        if (!demoMode) return real
        val anchor = real.firstOrNull()?.startOdo ?: _state.value.odometerKm ?: 52_310f
        return DriveLog.sampleWeek(_profile.value, anchor) + real
    }

    enum class Screen { DAY, LOG, PLAN, PROFILE }

    private val _screen = MutableStateFlow(Screen.DAY)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    fun openLog() { _screen.value = Screen.LOG }
    fun closeLog() { _screen.value = Screen.DAY }

    /** 아직 마감 전이면 지금까지의 오늘 기록(미리보기)을 만든다. 마감 뒤에는 저장된 기록을 쓴다. */
    fun todayPreview(): DayRecord? {
        val s = _state.value
        if (!s.started || s.closedAt != null) return null
        val (date, wd) = dayOf(s)
        return DriveLog.fromDay(s, _profile.value, date, wd)
    }

    /** 일정의 날짜와 요일. 날짜가 없으면(예전 저장 파일) 오늘. */
    private fun dayOf(s: TripState): Pair<String, String> {
        val d = s.date ?: return dateOf(System.currentTimeMillis())
        val t = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).parse(d)!!.time }
            .getOrDefault(System.currentTimeMillis())
        return d to SimpleDateFormat("E", Locale.KOREA).format(Date(t))
    }

    private fun today() = dateOf(System.currentTimeMillis()).first

    /** 새 하루의 일정. 시연 모드면 예시 7곳, 실차면 빈 일정(일정 편집에서 넣음). */
    private fun freshPlan() = if (demoMode) DemoDay.visits() else emptyList()

    /** 일정만 새로 바꾸고 기어·문·계기판·위치 같은 실시간 값은 그대로 둔다. */
    private fun replaceWithNewDay(visits: List<Visit>) {
        TripService.stop(getApplication())
        navi.cancel()
        val live = _state.value
        _state.value = TripState(
            visits, date = today(),
            gear = live.gear, driverDoorOpen = live.driverDoorOpen, odometerKm = live.odometerKm,
            lastLat = live.lastLat, lastLng = live.lastLng,
        )
        store.save(_state.value)
        _notices.value = emptyList()
        _screen.value = Screen.DAY
    }

    /** 운행 마감 뒤 "새 하루 시작". */
    fun startNewDay() {
        if (_state.value.started && _state.value.closedAt == null) return
        replaceWithNewDay(freshPlan())
        debug("새 하루를 시작했어요")
    }

    /**
     * 날짜가 바뀌었으면 하루를 넘긴다. 어제 시작만 하고 마감하지 않았으면 그날 날짜로 자동 마감해
     * 운행기록부에 남긴다(계기판은 마지막으로 받은 값).
     */
    fun checkRollover() {
        val s = _state.value
        val today = today()
        if (s.date == null) {
            _state.value = s.copy(date = today)
            store.save(_state.value)
            return
        }
        if (s.date == today) return
        when {
            s.started && s.closedAt == null -> {
                val end = s.odometerKm ?: s.dayEndOdo ?: s.visits.mapNotNull { it.odoAtArrival }.maxOrNull() ?: s.dayStartOdo
                _state.value = s.copy(closeOdo = end, closedAt = System.currentTimeMillis())
                saveToday()
                debug("${s.date} 운행을 자동으로 마감했어요")
                replaceWithNewDay(freshPlan())
            }
            s.started -> replaceWithNewDay(freshPlan())
            // 시작 전 일정은 미리 짜 둔 것일 수 있어 그대로 두고 날짜만 오늘로(시연 모드는 예시로 새로).
            else -> if (demoMode) replaceWithNewDay(freshPlan()) else {
                _state.value = s.copy(date = today)
                store.save(_state.value)
            }
        }
    }

    /** 시험용(시연 모드만): 지금 일정을 어제 것으로 만들고 하루 넘기기를 돌린다. */
    fun simulateYesterday() {
        if (!demoMode) return
        val y = dateOf(System.currentTimeMillis() - 24 * 3600 * 1000L).first
        _state.value = _state.value.copy(date = y)
        checkRollover()
    }

    private fun saveToday() {
        val s = _state.value
        val (date, wd) = dayOf(s)
        val r = DriveLog.fromDay(s, _profile.value, date, wd) ?: run {
            debug("계기판 값이 없어 운행기록을 남기지 못했어요")
            return
        }
        _records.value = DriveLog.upsert(_records.value, r)
        logStore.save(_records.value)
        Log.i(TAG, "운행기록 저장: ${r.date} ${"%,.1f".format(r.startOdo)} → ${"%,.1f".format(r.endOdo)} (출퇴근 ${r.commuteKm}, 업무 ${r.businessKm})")
    }

    private fun dateOf(t: Long): Pair<String, String> {
        val d = Date(t)
        return SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(d) to SimpleDateFormat("E", Locale.KOREA).format(d)
    }

    private val car = CarSignals(app, ::post, ::debug)
    private val navi = NaviBridge(app, ::post, ::debug)
    private val location = LocationTracker(app, ::post, ::debug)

    /** 주행 중 화면 제한. 시스템 UX 제한 서비스를 우선 쓰고, 못 쓰면 기어(P가 아니면 주행)로 판단한다. */
    private val _ux = MutableStateFlow(UxState(driving = false))
    val ux: StateFlow<UxState> = _ux.asStateFlow()
    private val restrictions = DrivingRestrictions(app, { u -> main.post { _ux.value = u } }, ::debug)
    private val uxFromSystem: Boolean

    init {
        debug("뷰모델 시작 · 기기 지오코더 ${if (geo.systemAvailable) "있음" else "없음(OpenStreetMap 사용)"}")
        car.start()
        navi.start()
        uxFromSystem = restrictions.start()
        checkRollover()
        syncTripService(null, _state.value)
        viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(1000)
                _now.value = System.currentTimeMillis()
                tick++
                // 실차에선 내비 남은 시간을 30초마다 새로 받는다.
                if (tick % 30 == 0 && config.preferNaviEta && _state.value.started) navi.refreshEta()
                // 위치 구독이 끊기지 않았는지 5초마다 확인한다.
                if (tick % 5 == 0 && locationGranted) location.ensure()
            }
        }
    }

    private var locationGranted = false

    /** 화면이 보일 때마다 불린다. 권한이 있으면 위치 구독을 확인·재개한다. */
    fun onLocationPermission(granted: Boolean) {
        locationGranted = granted
        if (granted) location.ensure() else debug("위치 권한이 없어 도착을 위치로 판단할 수 없어요")
    }

    val vin: String? get() = car.vin

    /** 어느 스레드에서 불러도 메인 스레드에서 순서대로 처리한다. */
    fun post(e: TripEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch(e) else main.post { dispatch(e) }
    }

    private fun dispatch(e: TripEvent) {
        val before = _state.value
        val r = TripEngine.reduce(before, e, System.currentTimeMillis(), config)
        _state.value = r.state
        if (!uxFromSystem && e is TripEvent.GearChanged) {
            _ux.value = UxState(driving = e.gear != Gear.PARK && e.gear != Gear.UNKNOWN)
        }
        if (r.state.log.firstOrNull() != before.log.firstOrNull()) {
            // 새로 생긴 기록만 logcat으로
            r.state.log.takeWhile { it != before.log.firstOrNull() }.reversed().forEach { Log.i(TAG, it.text) }
        }
        if (r.state.visits != before.visits || r.state.started != before.started ||
            r.state.routeChunk != before.routeChunk || r.state.dayStartOdo != before.dayStartOdo ||
            r.state.closedAt != before.closedAt
        ) {
            store.save(r.state)
        }
        r.effects.forEach(::run)
        syncTripService(before, r.state)
    }

    private fun run(effect: TripEffect) {
        when (effect) {
            is TripEffect.LoadRoute -> navi.load(effect.stops)
            TripEffect.DayClosed -> {
                saveToday()
                openLog()
            }
            is TripEffect.NotifyCustomer -> {
                // 1차 제출본은 발송 기록만 남긴다. 실제 문자 발송(서버·발신번호)은 2차에서.
                val min = ((effect.etaSec + 59) / 60).coerceAtLeast(1)
                val text = if (effect.late) {
                    "${effect.visit.customer}님, 방문 기사입니다. 앞 일정이 늦어져 약 ${min}분 뒤 도착 예정이에요. 기다리게 해서 죄송합니다."
                } else {
                    "${effect.visit.customer}님, 방문 기사입니다. 약 ${min}분 뒤 도착 예정이에요."
                }
                _notices.value = listOf(text) + _notices.value
                Log.i(TAG, "[알림 발송 기록] $text")
            }
        }
    }

    /** 하루가 시작돼 아직 방문이 남아 있는 동안만 방문 진행 서비스를 띄운다. */
    private fun tracking(s: TripState) = s.started && s.closedAt == null && !s.allFinished

    private fun syncTripService(before: TripState?, after: TripState) {
        val was = before?.let(::tracking) ?: false
        val now = tracking(after)
        if (now && !was) {
            TripService.start(getApplication())
            debug("방문 진행 서비스 시작")
        } else if (!now && was) {
            TripService.stop(getApplication())
            debug("방문 진행 서비스 종료")
        }
    }

    /** 시연·시험용: 오늘 일정을 예시 7곳 처음 상태로 되돌린다(운행기록은 그대로). */
    fun resetDay() {
        replaceWithNewDay(DemoDay.visits())
        debug("오늘 일정을 처음 상태로 되돌렸어요")
    }

    fun debug(msg: String) {
        Log.i(TAG, msg)
    }

    override fun onCleared() {
        debug("뷰모델 정리")
        restrictions.stop()
        location.stop()
        navi.stop()
        car.stop()
        super.onCleared()
    }

    companion object {
        const val TAG = "Nageune"
    }
}
