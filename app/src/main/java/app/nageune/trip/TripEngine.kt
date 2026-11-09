package app.nageune.trip

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 하루 방문 흐름의 상태 기계. 안드로이드·SDK에 의존하지 않는 순수 함수라 단위 테스트로 검증한다.
 *
 * 도착 판단은 세 갈래 중 먼저 온 것을 쓴다.
 *  - 위치: 방문지 반경(arriveRadiusM) 안으로 들어옴
 *  - 내비: onWayPointArrived / onDestinationArrived (실차)
 *  - 문: 방문지 근처(parkedRadiusM)에서 기어 P + 운전석 문 열림 (지하주차장처럼 위치가 흔들릴 때)
 * 작업 시작은 도착 후 기어 P + 운전석 문 열림, 작업 완료는 작업 중 P가 아닌 채로 방문지를 벗어남.
 */
object TripEngine {

    fun reduce(state: TripState, event: TripEvent, now: Long, cfg: TripConfig = TripConfig()): Reduced {
        val r = when (event) {
            TripEvent.StartDay -> startDay(state, now, cfg)
            is TripEvent.Location -> onLocation(state.copy(lastLat = event.lat, lastLng = event.lng), now, cfg)
            is TripEvent.GearChanged -> onGear(state, event.gear, now, cfg)
            is TripEvent.DriverDoor -> onDoor(state, event.open, now, cfg)
            is TripEvent.Odometer -> Reduced(
                state.copy(
                    odometerKm = event.km,
                    dayStartOdo = if (state.started && state.dayStartOdo == null) event.km else state.dayStartOdo,
                ),
            )
            is TripEvent.NaviArrived -> onNaviArrived(state, event.name, now, cfg)
            is TripEvent.NaviEtaInfo -> {
                val v = state.visits.firstOrNull { it.naviName == event.name }
                if (v == null) Reduced(state)
                else Reduced(state.copy(naviEta = state.naviEta + (v.id to NaviEta(event.seconds, event.meters, now))))
            }
            TripEvent.NaviConnected -> {
                val cur = state.current
                if (state.started && cur != null) loadRouteFrom(state, cur, cfg) else Reduced(state)
            }
            is TripEvent.ManualArrive -> withCurrent(state, event.id) { cur ->
                if (cur.status == VisitStatus.EN_ROUTE) arrive(state, cur, ArrivalSource.MANUAL, now) else Reduced(state)
            }
            is TripEvent.ManualStart -> withCurrent(state, event.id) { cur ->
                when (cur.status) {
                    VisitStatus.EN_ROUTE -> arrive(state, cur, ArrivalSource.MANUAL, now).then { checkIn(it, now) }
                    VisitStatus.ARRIVED -> checkIn(state, now)
                    else -> Reduced(state)
                }
            }
            is TripEvent.ManualFinish -> withCurrent(state, event.id) { cur ->
                if (cur.status == VisitStatus.ARRIVED || cur.status == VisitStatus.IN_SERVICE) {
                    finish(state, cur, now, cfg, "작업 완료(직접)")
                } else Reduced(state)
            }
            is TripEvent.Skip -> withCurrent(state, event.id) { cur -> skip(state, cur, now, cfg) }
            is TripEvent.NotifyLate -> withCurrent(state, event.id) { cur -> notifyLate(state, cur, now, cfg) }
            TripEvent.CloseDay -> closeDay(state, now)
            is TripEvent.EditPlan -> editPlan(state, event.visits, now, cfg)
        }
        // 어떤 신호가 들어와도 도착 예정 알림 조건은 다시 본다.
        return r.then { notifyIfClose(it, now, cfg) }
    }

    // ---- 도착 예상 ----

    fun eta(state: TripState, v: Visit, now: Long, cfg: TripConfig = TripConfig()): Eta? {
        val navi = state.naviEta[v.id]
        if (cfg.preferNaviEta && navi != null && now - navi.at <= cfg.naviEtaFreshMs) {
            return Eta(navi.seconds, navi.meters, fromNavi = true)
        }
        val lat = state.lastLat
        val lng = state.lastLng
        if (lat != null && lng != null) {
            val road = distanceM(lat, lng, v.lat, v.lng) * cfg.roadFactor
            return Eta((road / cfg.avgSpeedMps).roundToInt(), road.roundToInt(), fromNavi = false)
        }
        if (cfg.preferNaviEta && navi != null) return Eta(navi.seconds, navi.meters, fromNavi = true)
        return null
    }

    fun distanceTo(state: TripState, v: Visit): Double? {
        val lat = state.lastLat ?: return null
        val lng = state.lastLng ?: return null
        return distanceM(lat, lng, v.lat, v.lng)
    }

    /** 두 좌표 사이 직선거리(m), haversine. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    // ---- 이벤트 처리 ----

    private fun startDay(state: TripState, now: Long, cfg: TripConfig): Reduced {
        val first = state.current
        if (state.started || first == null) return Reduced(state)
        val s = state.copy(
            started = true,
            dayStartOdo = state.odometerKm ?: state.dayStartOdo,
            visits = state.visits.update(first.id) { it.copy(status = VisitStatus.EN_ROUTE) },
        ).log(now, "하루 시작: 방문 ${state.visits.size}곳")
        return loadRouteFrom(s, s.current!!, cfg)
    }

    private fun onLocation(state: TripState, now: Long, cfg: TripConfig): Reduced {
        val cur = state.current
        if (!state.started || cur == null) return Reduced(state)
        val d = distanceTo(state, cur) ?: return Reduced(state)
        return when {
            cur.status == VisitStatus.EN_ROUTE && d <= cfg.arriveRadiusM ->
                arrive(state, cur, ArrivalSource.LOCATION, now)

            cur.status == VisitStatus.ARRIVED && d > cfg.leaveRadiusM && state.gear != Gear.PARK -> Reduced(
                state.copy(
                    visits = state.visits.update(cur.id) {
                        it.copy(status = VisitStatus.EN_ROUTE, arrivedAt = null, arrivalSource = null, odoAtArrival = null)
                    },
                ).log(now, "${cur.customer} 앞을 지나쳤어요. 다시 가는 중으로 돌려요"),
            )

            cur.status == VisitStatus.IN_SERVICE && d > cfg.leaveRadiusM && state.gear != Gear.PARK ->
                finish(state, cur, now, cfg, "출발 → 작업 완료")

            else -> Reduced(state)
        }
    }

    private fun onGear(state: TripState, gear: Gear, now: Long, cfg: TripConfig): Reduced {
        val s = state.copy(gear = gear)
        val cur = s.current ?: return Reduced(s)
        if (!s.started) return Reduced(s)
        return if (cur.status == VisitStatus.ARRIVED && gear == Gear.PARK && s.driverDoorOpen) checkIn(s, now)
        else Reduced(s)
    }

    private fun onDoor(state: TripState, open: Boolean, now: Long, cfg: TripConfig): Reduced {
        val s = state.copy(driverDoorOpen = open)
        val cur = s.current
        if (!open || !s.started || cur == null || s.gear != Gear.PARK) return Reduced(s)
        return when (cur.status) {
            VisitStatus.ARRIVED -> checkIn(s, now)
            VisitStatus.EN_ROUTE -> {
                val d = distanceTo(s, cur)
                if (d != null && d <= cfg.parkedRadiusM) arrive(s, cur, ArrivalSource.DOOR, now).then { checkIn(it, now) }
                else Reduced(s)
            }
            else -> Reduced(s)
        }
    }

    private fun onNaviArrived(state: TripState, name: String, now: Long, cfg: TripConfig): Reduced {
        val cur = state.current
        val v = state.visits.firstOrNull { it.naviName == name }
        if (v == null || cur == null || v.id != cur.id || cur.status != VisitStatus.EN_ROUTE) {
            return Reduced(state.log(now, "내비 도착 신호: $name"))
        }
        return arrive(state, cur, ArrivalSource.NAVI, now)
    }

    // ---- 상태 전이 ----

    private fun arrive(state: TripState, v: Visit, source: ArrivalSource, now: Long): Reduced {
        val how = when (source) {
            ArrivalSource.LOCATION -> "위치"
            ArrivalSource.NAVI -> "내비"
            ArrivalSource.DOOR -> "주차·문 열림"
            ArrivalSource.MANUAL -> "직접"
        }
        return Reduced(
            state.copy(
                visits = state.visits.update(v.id) {
                    it.copy(
                        status = VisitStatus.ARRIVED,
                        arrivedAt = now,
                        arrivalSource = source,
                        odoAtArrival = state.odometerKm,
                    )
                },
            ).log(now, "${v.customer} 도착 ($how)"),
        )
    }

    private fun checkIn(state: TripState, now: Long): Reduced {
        val cur = state.current ?: return Reduced(state)
        if (cur.status != VisitStatus.ARRIVED) return Reduced(state)
        return Reduced(
            state.copy(visits = state.visits.update(cur.id) { it.copy(status = VisitStatus.IN_SERVICE, startedAt = now) })
                .log(now, "${cur.customer} 작업 시작"),
        )
    }

    private fun finish(state: TripState, v: Visit, now: Long, cfg: TripConfig, why: String): Reduced {
        val s = state.copy(visits = state.visits.update(v.id) { it.copy(status = VisitStatus.DONE, finishedAt = now) })
            .log(now, "${v.customer} $why")
        return advance(s, v, now, cfg, forceReload = false)
    }

    private fun skip(state: TripState, v: Visit, now: Long, cfg: TripConfig): Reduced {
        val s = state.copy(visits = state.visits.update(v.id) { it.copy(status = VisitStatus.SKIPPED) })
            .log(now, "${v.customer} 건너뜀")
        // 내비에는 건너뛴 곳이 경유지로 남아 있으니 경로를 다시 넣는다.
        return advance(s, v, now, cfg, forceReload = true)
    }

    /** 방금 끝난 방문(prev) 다음으로 넘어간다. 필요하면 다음 묶음 경로를 넣는다. */
    private fun advance(state: TripState, prev: Visit, now: Long, cfg: TripConfig, forceReload: Boolean): Reduced {
        val next = state.current
        if (next == null) {
            return Reduced(
                state.copy(routeChunk = emptyList(), dayEndOdo = state.odometerKm)
                    .log(now, "오늘 방문을 모두 마쳤어요"),
            )
        }
        val s = state.copy(visits = state.visits.update(next.id) { it.copy(status = VisitStatus.EN_ROUTE) })
        val chunkEnded = prev.id == state.routeChunk.lastOrNull() || next.id !in state.routeChunk
        return if (forceReload || chunkEnded) loadRouteFrom(s, s.current!!, cfg) else Reduced(s)
    }

    private fun loadRouteFrom(state: TripState, from: Visit, cfg: TripConfig): Reduced {
        val stops = state.visits
            .filter { !it.status.finished && it.order >= from.order }
            .sortedBy { it.order }
            .take(cfg.routeChunkSize)
        if (stops.isEmpty()) return Reduced(state.copy(routeChunk = emptyList()))
        return Reduced(state.copy(routeChunk = stops.map { it.id }), listOf(TripEffect.LoadRoute(stops)))
    }

    private fun notifyIfClose(state: TripState, now: Long, cfg: TripConfig): Reduced {
        val cur = state.current
        if (!state.started || cur == null || cur.status != VisitStatus.EN_ROUTE || cur.notifiedAt != null) {
            return Reduced(state)
        }
        val e = eta(state, cur, now, cfg) ?: return Reduced(state)
        if (e.seconds > cfg.notifyBeforeSec) return Reduced(state)
        val minutes = ((e.seconds + 59) / 60).coerceAtLeast(1)
        val s = state.copy(
            visits = state.visits.update(cur.id) { it.copy(notifiedAt = now, notifiedEtaSec = e.seconds) },
        ).log(now, "${cur.customer}께 도착 예정 알림: 약 ${minutes}분 후")
        return Reduced(s, listOf(TripEffect.NotifyCustomer(s.current!!, e.seconds)))
    }

    /**
     * 일정 편집. 끝난 방문과 지금 가는 방문은 그대로 앞에 두고, 나머지는 편집된 순서·내용을 따른다.
     * 순번(order)은 1부터 다시 매긴다. 하루가 시작된 뒤라면 순번이 바뀌어 내비 이름도 바뀌므로 경로를 다시 넣는다.
     */
    private fun editPlan(state: TripState, edited: List<Visit>, now: Long, cfg: TripConfig): Reduced {
        if (state.closedAt != null) return Reduced(state)
        val cur = if (state.started) state.current else null
        val locked = state.visits.filter { it.status.finished || it.id == cur?.id }.sortedBy { it.order }
        val lockedIds = locked.map { it.id }.toSet()
        val existing = state.visits.associateBy { it.id }
        val rest = edited
            .filter { it.id !in lockedIds }
            .distinctBy { it.id }
            .map { e ->
                val old = existing[e.id]
                // 남은 방문은 내용만 바꾸고 상태·기록은 유지(새 방문이면 PENDING).
                old?.copy(
                    customer = e.customer, service = e.service, address = e.address,
                    lat = e.lat, lng = e.lng, window = e.window, note = e.note,
                ) ?: e.copy(status = VisitStatus.PENDING, arrivedAt = null, startedAt = null, finishedAt = null)
            }
        val visits = (locked + rest).mapIndexed { i, v -> v.copy(order = i + 1) }
        var s = state.copy(visits = visits).log(now, "일정 수정: 방문 ${visits.size}곳")
        if (!s.started) return Reduced(s.copy(routeChunk = emptyList()))
        val next = s.current
        if (next == null) {
            return Reduced(s.copy(routeChunk = emptyList(), dayEndOdo = s.dayEndOdo ?: s.odometerKm))
        }
        if (next.status == VisitStatus.PENDING) {
            s = s.copy(visits = s.visits.update(next.id) { it.copy(status = VisitStatus.EN_ROUTE) })
        }
        return loadRouteFrom(s, s.current!!, cfg)
    }

    private fun closeDay(state: TripState, now: Long): Reduced {
        if (!state.started || state.closedAt != null) return Reduced(state)
        val odo = state.odometerKm ?: state.dayEndOdo
        val s = state.copy(closeOdo = odo, closedAt = now)
            .log(now, "운행 마감" + (odo?.let { ": 계기판 %,.1f km".format(it) } ?: ""))
        return Reduced(s, listOf(TripEffect.DayClosed))
    }

    private fun notifyLate(state: TripState, cur: Visit, now: Long, cfg: TripConfig): Reduced {
        if (cur.status != VisitStatus.EN_ROUTE || cur.lateNotifiedAt != null) return Reduced(state)
        val etaSec = eta(state, cur, now, cfg)?.seconds ?: return Reduced(state)
        val minutes = ((etaSec + 59) / 60).coerceAtLeast(1)
        val s = state.copy(visits = state.visits.update(cur.id) { it.copy(lateNotifiedAt = now) })
            .log(now, "${cur.customer}께 늦는다고 알림: 약 ${minutes}분 후 도착")
        return Reduced(s, listOf(TripEffect.NotifyCustomer(s.current!!, etaSec, late = true)))
    }

    // ---- 도우미 ----

    private inline fun withCurrent(state: TripState, id: String, block: (Visit) -> Reduced): Reduced {
        val cur = state.current
        return if (state.started && cur != null && cur.id == id) block(cur) else Reduced(state)
    }

    private inline fun Reduced.then(next: (TripState) -> Reduced): Reduced {
        val r = next(state)
        return Reduced(r.state, effects + r.effects)
    }

    private fun List<Visit>.update(id: String, f: (Visit) -> Visit) = map { if (it.id == id) f(it) else it }

    private fun TripState.log(now: Long, text: String) = copy(log = (listOf(TripLog(now, text)) + log).take(80))
}
