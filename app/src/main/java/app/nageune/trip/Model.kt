package app.nageune.trip

/** 방문 하나의 진행 단계. */
enum class VisitStatus {
    PENDING,    // 아직 차례가 아님
    EN_ROUTE,   // 지금 가는 중
    ARRIVED,    // 도착(위치·내비·수동), 아직 작업 시작 전
    IN_SERVICE, // 차를 세우고 문을 열어 작업 중
    DONE,       // 작업을 마치고 떠남
    SKIPPED;    // 건너뜀

    val finished: Boolean get() = this == DONE || this == SKIPPED
}

enum class ArrivalSource { LOCATION, NAVI, DOOR, MANUAL }

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

data class Visit(
    val id: String,
    val order: Int,
    val customer: String,
    val service: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    /** 고객과 약속한 방문 시간대, 예: "10:30~11:00" */
    val window: String,
    val note: String = "",
    val status: VisitStatus = VisitStatus.PENDING,
    val arrivedAt: Long? = null,
    val arrivalSource: ArrivalSource? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** 도착했을 때 계기판 누적 거리(km). 운행기록부 구간 거리 계산용. */
    val odoAtArrival: Float? = null,
    /** 고객에게 도착 예정 알림을 보낸 시각. */
    val notifiedAt: Long? = null,
    val notifiedEtaSec: Int? = null,
    /** 주행 중 "늦어요" 버튼으로 지연 알림을 보낸 시각. */
    val lateNotifiedAt: Long? = null,
) {
    /** 내비에 넘기는 이름. 하루 안에서 겹치지 않아야 도착 이벤트를 방문과 짝지을 수 있다. */
    val naviName: String get() = "${order}번 $customer"

    /** 좌표를 아직 못 정한 방문(입력 중)은 0,0으로 둔다. */
    val hasLocation: Boolean get() = !(lat == 0.0 && lng == 0.0)
}

/** 내비가 알려준 남은 시간·거리. */
data class NaviEta(val seconds: Int, val meters: Int, val at: Long)

/** 화면에 보여줄 도착 예상. */
data class Eta(val seconds: Int, val meters: Int, val fromNavi: Boolean)

data class TripLog(val at: Long, val text: String)

data class TripConfig(
    /** 이 거리 안으로 들어오면 도착으로 본다. */
    val arriveRadiusM: Double = 80.0,
    /** 차를 세우고 문을 열었을 때, 이 거리 안이면 도착+작업 시작으로 본다(지하주차장 등 위치 오차 감안). */
    val parkedRadiusM: Double = 200.0,
    /** 작업 중에 이 거리 밖으로 벗어나면 출발(작업 완료)로 본다. */
    val leaveRadiusM: Double = 150.0,
    /** 남은 시간이 이 아래로 내려가면 고객에게 도착 예정 알림을 보낸다. */
    val notifyBeforeSec: Int = 600,
    /** 내비 값이 이보다 오래되면 직접 추정한 값을 쓴다. */
    val naviEtaFreshMs: Long = 30_000,
    /** false면 내비 남은 시간을 쓰지 않는다(에뮬레이터 시연: 내비 위치가 고정돼 값이 틀림). */
    val preferNaviEta: Boolean = true,
    /** 직선거리 → 도로 거리 보정 배수와 도심 평균 속도. */
    val roadFactor: Double = 1.35,
    val avgSpeedMps: Double = 30_000.0 / 3600.0,
    /** 내비 경유지는 최대 2곳 + 목적지 1곳. */
    val routeChunkSize: Int = 3,
)

data class TripState(
    val visits: List<Visit>,
    /** 이 일정의 날짜("2026-11-09"). 날짜가 바뀌면 앱이 하루를 넘긴다. */
    val date: String? = null,
    val started: Boolean = false,
    /** 지금 내비에 들어가 있는 방문 id(마지막이 목적지). */
    val routeChunk: List<String> = emptyList(),
    val gear: Gear = Gear.UNKNOWN,
    val driverDoorOpen: Boolean = false,
    val odometerKm: Float? = null,
    val dayStartOdo: Float? = null,
    val dayEndOdo: Float? = null,
    /** 운행 마감(퇴근해서 차를 세운 뒤) 때 계기판. 마지막 방문 → 마감 구간이 퇴근 거리가 된다. */
    val closeOdo: Float? = null,
    val closedAt: Long? = null,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val naviEta: Map<String, NaviEta> = emptyMap(),
    val log: List<TripLog> = emptyList(),
) {
    val current: Visit? get() = visits.firstOrNull { !it.status.finished }
    val doneCount: Int get() = visits.count { it.status == VisitStatus.DONE }
    val allFinished: Boolean get() = visits.isNotEmpty() && visits.all { it.status.finished }

    /** 오늘 달린 거리(km). 계기판 기준. */
    val drivenKm: Float?
        get() {
            val start = dayStartOdo ?: return null
            val end = closeOdo ?: odometerKm ?: dayEndOdo ?: return null
            return (end - start).coerceAtLeast(0f)
        }
}

sealed interface TripEvent {
    data object StartDay : TripEvent
    data class Location(val lat: Double, val lng: Double) : TripEvent
    data class GearChanged(val gear: Gear) : TripEvent
    data class DriverDoor(val open: Boolean) : TripEvent
    data class Odometer(val km: Float) : TripEvent
    data class NaviArrived(val name: String) : TripEvent
    data class NaviEtaInfo(val name: String, val seconds: Int, val meters: Int) : TripEvent
    data object NaviConnected : TripEvent
    data class ManualArrive(val id: String) : TripEvent
    data class ManualStart(val id: String) : TripEvent
    data class ManualFinish(val id: String) : TripEvent
    data class Skip(val id: String) : TripEvent
    /** 주행 중 버튼 한 번: 고객에게 늦는다고 알린다. */
    data class NotifyLate(val id: String) : TripEvent
    /** 퇴근해서 차를 세운 뒤 운행 마감. 지금 계기판으로 하루 기록을 닫는다. */
    data object CloseDay : TripEvent
    /**
     * 일정 편집 결과(원하는 순서대로 전체 목록). 이미 끝났거나 지금 가는 방문은 바뀌지 않고,
     * 남은 방문만 고치거나 순서를 바꾸거나 더할 수 있다.
     */
    data class EditPlan(val visits: List<Visit>) : TripEvent
}

sealed interface TripEffect {
    /** 내비에 경로를 넣는다. 마지막이 목적지, 앞의 것들이 경유지. */
    data class LoadRoute(val stops: List<Visit>) : TripEffect
    data class NotifyCustomer(val visit: Visit, val etaSec: Int, val late: Boolean = false) : TripEffect
    /** 하루가 닫혔다. 운행기록부에 한 줄을 남긴다. */
    data object DayClosed : TripEffect
}

data class Reduced(val state: TripState, val effects: List<TripEffect> = emptyList())
