package app.nageune.trip

/**
 * 국세청 「업무용승용차 운행기록부」에 맞춘 기록.
 *
 * 서식에 들어가는 것: 차종, 자동차등록번호, 사용일자, 사용자(부서·성명),
 * 주행 전·후 계기판 누적거리, 주행거리, 업무용 사용거리(출·퇴근용 / 일반 업무용), 비고.
 * 같은 사람이 하루에 여러 번 쓰면 하루 한 줄로 합쳐 적을 수 있어 하루 단위로 남기고,
 * 방문 구간별 계기판 값은 증빙용 상세로 따로 둔다.
 *
 * 구간 구분(나그네의 가정): 출발 → 첫 방문은 출근, 방문 사이는 일반 업무, 마지막 방문 → 운행 마감은 퇴근.
 * 출·퇴근도 업무용 사용거리에 들어가므로 이 구분은 업무사용비율에 영향을 주지 않는다.
 */
enum class LegKind { COMMUTE, BUSINESS }

data class LogLeg(
    val from: String,
    val to: String,
    val startOdo: Float,
    val endOdo: Float,
    val kind: LegKind,
    val at: Long? = null,
) {
    val km: Float get() = (endOdo - startOdo).coerceAtLeast(0f)
}

data class DayRecord(
    /** "2026-11-09" */
    val date: String,
    /** "월" */
    val weekday: String,
    val user: String,
    val dept: String,
    val startOdo: Float,
    val endOdo: Float,
    val commuteKm: Float,
    val businessKm: Float,
    val visitCount: Int,
    val note: String,
    val legs: List<LogLeg> = emptyList(),
    /** 시연용 예시 기록(실제 주행 아님). 화면에 "예시"로 표시한다. */
    val sample: Boolean = false,
) {
    val totalKm: Float get() = (endOdo - startOdo).coerceAtLeast(0f)
    val workKm: Float get() = commuteKm + businessKm
}

/** 운행기록부 머리말에 들어가는 사용자·차량 정보. 차종·번호는 차에서 읽을 수 없어 사용자가 입력한다. */
data class Profile(
    val user: String,
    val dept: String,
    val carModel: String,
    val plate: String,
)

data class LogSummary(
    /** 첫 기록 출발 ~ 마지막 기록 마감 사이 계기판 거리(기록 사이 개인 주행 포함). */
    val totalKm: Float,
    /** 업무용 사용거리(출·퇴근 + 일반 업무). */
    val workKm: Float,
    /** 기록 사이에 계기판이 늘어난 만큼 = 개인(비업무) 주행으로 본다. */
    val personalKm: Float,
    /** 업무사용비율(0~1). 총 주행거리가 0이면 null. */
    val ratio: Float?,
)

object DriveLog {

    /** 하루 상태를 운행기록부 한 줄로 만든다. 출발 계기판이 없으면 null. */
    fun fromDay(s: TripState, profile: Profile, date: String, weekday: String): DayRecord? {
        val start = s.dayStartOdo ?: return null
        val end = s.closeOdo ?: s.odometerKm ?: s.dayEndOdo ?: return null
        val done = s.visits
            .filter { it.status == VisitStatus.DONE && it.odoAtArrival != null }
            .sortedWith(compareBy({ it.arrivedAt ?: Long.MAX_VALUE }, { it.order }))

        val legs = mutableListOf<LogLeg>()
        var prevName = "출발"
        var prevOdo = start
        done.forEachIndexed { i, v ->
            val odo = v.odoAtArrival!!.coerceAtLeast(prevOdo)
            legs += LogLeg(prevName, v.naviName, prevOdo, odo, if (i == 0) LegKind.COMMUTE else LegKind.BUSINESS, v.arrivedAt)
            prevName = v.naviName
            prevOdo = odo
        }
        legs += LogLeg(
            from = prevName,
            to = "운행 마감",
            startOdo = prevOdo,
            endOdo = end.coerceAtLeast(prevOdo),
            // 방문이 하나도 없으면 하루 주행을 일반 업무로 본다.
            kind = if (done.isEmpty()) LegKind.BUSINESS else LegKind.COMMUTE,
            at = s.closedAt,
        )

        val skipped = s.visits.count { it.status == VisitStatus.SKIPPED }
        val note = buildString {
            append("방문 ${done.size}곳")
            if (done.isNotEmpty()) {
                append(" (${done.first().customer}")
                if (done.size > 1) append(" 외 ${done.size - 1}곳")
                append(")")
            }
            if (skipped > 0) append(" · 건너뜀 ${skipped}곳")
        }
        return DayRecord(
            date = date,
            weekday = weekday,
            user = profile.user,
            dept = profile.dept,
            startOdo = start,
            endOdo = legs.last().endOdo,
            commuteKm = legs.filter { it.kind == LegKind.COMMUTE }.sumOf { it.km.toDouble() }.toFloat(),
            businessKm = legs.filter { it.kind == LegKind.BUSINESS }.sumOf { it.km.toDouble() }.toFloat(),
            visitCount = done.size,
            note = note,
            legs = legs,
        )
    }

    /** 같은 날짜 기록은 새것으로 바꾸고 날짜순으로 정렬한다. */
    fun upsert(records: List<DayRecord>, r: DayRecord): List<DayRecord> =
        (records.filter { it.date != r.date } + r).sortedBy { it.date }

    fun summarize(records: List<DayRecord>): LogSummary {
        if (records.isEmpty()) return LogSummary(0f, 0f, 0f, null)
        val sorted = records.sortedBy { it.date }
        val total = (sorted.last().endOdo - sorted.first().startOdo).coerceAtLeast(0f)
        val work = sorted.sumOf { it.workKm.toDouble() }.toFloat()
        val personal = (total - work).coerceAtLeast(0f)
        return LogSummary(total, work, personal, if (total > 0f) (work / total).coerceAtMost(1f) else null)
    }

    /**
     * 시연용 지난주 예시 기록(월~금). 주말 사이 계기판이 벌어진 만큼(18.6km)은 개인 주행으로 잡힌다.
     * @param anchorStart 이번 주 첫 기록의 출발 계기판. 예시 기록이 여기에 이어지도록 값을 옮긴다
     *   (에뮬레이터 계기판은 되돌릴 수 없어 시연할 때마다 늘어나기 때문).
     */
    fun sampleWeek(profile: Profile, anchorStart: Float = 52_310f): List<DayRecord> {
        val d = anchorStart - 52_310f
        return listOf(
            sample("2026-11-02", "월", profile, 52_021.6f + d, 52_079.3f + d, 9.8f, 7),
            sample("2026-11-03", "화", profile, 52_079.3f + d, 52_131.0f + d, 8.6f, 6),
            sample("2026-11-04", "수", profile, 52_134.2f + d, 52_197.5f + d, 10.4f, 8),
            sample("2026-11-05", "목", profile, 52_197.5f + d, 52_246.8f + d, 9.1f, 6),
            sample("2026-11-06", "금", profile, 52_246.8f + d, 52_291.4f + d, 8.8f, 5),
        )
    }

    private fun sample(date: String, wd: String, p: Profile, start: Float, end: Float, commute: Float, visits: Int) =
        DayRecord(
            date = date, weekday = wd, user = p.user, dept = p.dept,
            startOdo = start, endOdo = end,
            commuteKm = commute, businessKm = (end - start - commute),
            visitCount = visits, note = "방문 ${visits}곳", sample = true,
        )
}
