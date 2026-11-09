package app.nageune.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    private val cfg = TripConfig(preferNaviEta = true)
    private val start = 1_000_000L

    // 에뮬레이터 시작 위치(판교 창업로). v1(백현동)까지 직선 약 2.6km.
    private val home = 37.41272 to 127.09468

    private fun Visit.at(dLatM: Double = 0.0) = (lat + dLatM / 111_000.0) to lng

    private class Run(var s: TripState, val cfg: TripConfig) {
        var t = 1_000_000L
        val effects = mutableListOf<TripEffect>()
        fun on(e: TripEvent, advanceMs: Long = 1_000): Run {
            t += advanceMs
            val r = TripEngine.reduce(s, e, t, cfg)
            s = r.state
            effects += r.effects
            return this
        }
        fun at(p: Pair<Double, Double>) = on(TripEvent.Location(p.first, p.second))
        fun v(id: String) = s.visits.first { it.id == id }
        fun routes() = effects.filterIsInstance<TripEffect.LoadRoute>().map { r -> r.stops.map { it.id } }
        fun notices() = effects.filterIsInstance<TripEffect.NotifyCustomer>().map { it.visit.id }
    }

    private fun run() = Run(TripState(DemoDay.visits()), cfg)
        .on(TripEvent.Odometer(52_310f))
        .on(TripEvent.GearChanged(Gear.PARK))
        .at(home)

    @Test
    fun startDay_loadsFirstThreeStops_andMarksFirstEnRoute() {
        val r = run().on(TripEvent.StartDay)
        assertTrue(r.s.started)
        assertEquals(VisitStatus.EN_ROUTE, r.v("v1").status)
        assertEquals(listOf(listOf("v1", "v2", "v3")), r.routes())
        assertEquals(52_310f, r.s.dayStartOdo)
    }

    @Test
    fun startDay_twice_doesNothingSecondTime() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.StartDay)
        assertEquals(1, r.routes().size)
    }

    @Test
    fun enteringRadius_arrivesByLocation_andRecordsOdometer() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.GearChanged(Gear.DRIVE))
        r.on(TripEvent.Odometer(52_313f)).at(r.v("v1").at(dLatM = 60.0))
        val v1 = r.v("v1")
        assertEquals(VisitStatus.ARRIVED, v1.status)
        assertEquals(ArrivalSource.LOCATION, v1.arrivalSource)
        assertEquals(52_313f, v1.odoAtArrival)
    }

    @Test
    fun outsideRadius_staysEnRoute() {
        val r = run().on(TripEvent.StartDay)
        r.at(r.v("v1").at(dLatM = 300.0))
        assertEquals(VisitStatus.EN_ROUTE, r.v("v1").status)
    }

    @Test
    fun parkAndOpenDoor_afterArrival_startsService() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.GearChanged(Gear.DRIVE))
        r.at(r.v("v1").at(20.0))
            .on(TripEvent.GearChanged(Gear.PARK))
        assertEquals(VisitStatus.ARRIVED, r.v("v1").status)
        r.on(TripEvent.DriverDoor(true))
        assertEquals(VisitStatus.IN_SERVICE, r.v("v1").status)
        assertNotNull(r.v("v1").startedAt)
    }

    @Test
    fun doorOpenWhileInGearDrive_doesNotStartService() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.GearChanged(Gear.DRIVE))
        r.at(r.v("v1").at(20.0)).on(TripEvent.DriverDoor(true))
        assertEquals(VisitStatus.ARRIVED, r.v("v1").status)
    }

    @Test
    fun parkedNearbyWithWeakGps_doorOpen_arrivesAndStarts() {
        // 지하주차장: 위치가 150m 떨어진 입구에 머물러 있어도 P + 문 열림이면 도착·작업 시작
        val r = run().on(TripEvent.StartDay).on(TripEvent.GearChanged(Gear.DRIVE))
        r.at(r.v("v1").at(150.0)).on(TripEvent.GearChanged(Gear.PARK)).on(TripEvent.DriverDoor(true))
        assertEquals(VisitStatus.IN_SERVICE, r.v("v1").status)
        assertEquals(ArrivalSource.DOOR, r.v("v1").arrivalSource)
    }

    @Test
    fun parkedFarAway_doorOpen_doesNothing() {
        val r = run().on(TripEvent.StartDay)
        r.on(TripEvent.DriverDoor(true))
        assertEquals(VisitStatus.EN_ROUTE, r.v("v1").status)
    }

    @Test
    fun leavingAfterService_finishes_andNextIsEnRoute_withoutReloadingRoute() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.GearChanged(Gear.DRIVE))
        val v1 = r.v("v1")
        r.at(v1.at(10.0)).on(TripEvent.GearChanged(Gear.PARK)).on(TripEvent.DriverDoor(true))
            .on(TripEvent.DriverDoor(false))
            .on(TripEvent.GearChanged(Gear.DRIVE))
            .at(v1.at(400.0))
        assertEquals(VisitStatus.DONE, r.v("v1").status)
        assertEquals(VisitStatus.EN_ROUTE, r.v("v2").status)
        // v2는 이미 내비에 경유지로 들어가 있으니 경로를 다시 넣지 않는다.
        assertEquals(1, r.routes().size)
    }

    @Test
    fun movingSlightlyWhileParked_doesNotFinish() {
        val r = run().on(TripEvent.StartDay)
        val v1 = r.v("v1")
        r.at(v1.at(10.0)).on(TripEvent.DriverDoor(true)) // 기어 P 상태
        r.at(v1.at(400.0)) // GPS 튐, 기어는 여전히 P
        assertEquals(VisitStatus.IN_SERVICE, r.v("v1").status)
    }

    @Test
    fun finishingLastStopOfChunk_loadsNextChunk() {
        val r = run().on(TripEvent.StartDay)
        listOf("v1", "v2", "v3").forEach { id ->
            r.on(TripEvent.ManualStart(id)).on(TripEvent.ManualFinish(id))
        }
        assertEquals(listOf(listOf("v1", "v2", "v3"), listOf("v4", "v5", "v6")), r.routes())
        assertEquals(VisitStatus.EN_ROUTE, r.v("v4").status)
    }

    @Test
    fun skip_reloadsRouteFromNext() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.Skip("v1"))
        assertEquals(VisitStatus.SKIPPED, r.v("v1").status)
        assertEquals(VisitStatus.EN_ROUTE, r.v("v2").status)
        assertEquals(listOf("v2", "v3", "v4"), r.routes().last())
    }

    @Test
    fun skipOnlyAffectsCurrentVisit() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.Skip("v3"))
        assertEquals(VisitStatus.PENDING, r.v("v3").status)
    }

    @Test
    fun naviArrival_matchesByNaviName() {
        val r = run().on(TripEvent.StartDay)
        r.on(TripEvent.NaviArrived(r.v("v1").naviName))
        assertEquals(VisitStatus.ARRIVED, r.v("v1").status)
        assertEquals(ArrivalSource.NAVI, r.v("v1").arrivalSource)
    }

    @Test
    fun naviArrival_forOtherStop_isIgnored() {
        val r = run().on(TripEvent.StartDay)
        r.on(TripEvent.NaviArrived(r.v("v2").naviName))
        assertEquals(VisitStatus.EN_ROUTE, r.v("v1").status)
        assertEquals(VisitStatus.PENDING, r.v("v2").status)
    }

    @Test
    fun notify_firesOnceWhenEtaUnderTenMinutes() {
        // 위치 없이 내비 값만으로 판단하는 경우
        val r = Run(TripState(DemoDay.visits()), cfg).on(TripEvent.StartDay)
        // 내비가 20분이라고 하면 아직 안 보냄
        r.on(TripEvent.NaviEtaInfo(r.v("v1").naviName, 1200, 9000))
        assertTrue(r.notices().isEmpty())
        // 9분이 되면 보냄
        r.on(TripEvent.NaviEtaInfo(r.v("v1").naviName, 540, 3500))
        assertEquals(listOf("v1"), r.notices())
        // 다시 줄어도 한 번만
        r.on(TripEvent.NaviEtaInfo(r.v("v1").naviName, 300, 2000))
        assertEquals(listOf("v1"), r.notices())
        assertEquals(540, r.v("v1").notifiedEtaSec)
    }

    @Test
    fun notify_firesAtStartWhenFirstStopIsClose() {
        // 판교 창업로 → 백현동 약 3.5km(도로 환산) ≈ 7분이라 출발하자마자 알림
        val r = run().on(TripEvent.StartDay)
        assertEquals(listOf("v1"), r.notices())
    }

    @Test
    fun staleNaviEta_fallsBackToEstimate() {
        val r = run().on(TripEvent.StartDay)
        r.on(TripEvent.NaviEtaInfo(r.v("v2").naviName, 300, 2000))
        r.on(TripEvent.Location(home.first, home.second), advanceMs = 60_000)
        val eta = TripEngine.eta(r.s, r.v("v2"), r.t, cfg)!!
        assertEquals(false, eta.fromNavi)
        assertTrue(eta.seconds > 300)
    }

    @Test
    fun demoMode_ignoresNaviEta() {
        val demo = cfg.copy(preferNaviEta = false)
        val s = TripState(DemoDay.visits(), lastLat = home.first, lastLng = home.second,
            naviEta = mapOf("v1" to NaviEta(60, 10, start)))
        val eta = TripEngine.eta(s, s.visits[0], start, demo)!!
        assertEquals(false, eta.fromNavi)
    }

    @Test
    fun allDone_recordsEndOdometer_andDrivenKm() {
        val r = run().on(TripEvent.StartDay)
        r.s.visits.forEach { v ->
            r.on(TripEvent.Odometer(r.s.odometerKm!! + 3f))
            r.on(TripEvent.ManualStart(v.id)).on(TripEvent.ManualFinish(v.id))
        }
        assertTrue(r.s.allFinished)
        assertNull(r.s.current)
        assertEquals(21f, r.s.drivenKm!!, 0.01f)
        assertEquals(52_331f, r.s.dayEndOdo)
    }

    @Test
    fun passingByWithoutStopping_revertsToEnRoute() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.GearChanged(Gear.DRIVE))
        val v1 = r.v("v1")
        r.at(v1.at(30.0)).at(v1.at(-300.0))
        assertEquals(VisitStatus.EN_ROUTE, r.v("v1").status)
        assertNull(r.v("v1").arrivedAt)
    }

    @Test
    fun notifyLate_sendsOnceWithCurrentEta() {
        val r = run().on(TripEvent.StartDay)
        r.on(TripEvent.NotifyLate("v1"))
        r.on(TripEvent.NotifyLate("v1"))
        val late = r.effects.filterIsInstance<TripEffect.NotifyCustomer>().filter { it.late }
        assertEquals(1, late.size)
        assertEquals("v1", late[0].visit.id)
        assertNotNull(r.v("v1").lateNotifiedAt)
    }

    @Test
    fun notifyLate_ignoredWhenNotEnRoute() {
        val r = run().on(TripEvent.StartDay).on(TripEvent.ManualArrive("v1")).on(TripEvent.NotifyLate("v1"))
        assertTrue(r.effects.filterIsInstance<TripEffect.NotifyCustomer>().none { it.late })
    }

    @Test
    fun editPlan_beforeStart_replacesAndRenumbers() {
        val r = run()
        val v = r.s.visits
        val newV = Visit("n1", 99, "새 고객", "필터 교체", "성남시 분당구 정자동", 37.36, 127.10, "18:00~18:30")
        // 3번을 맨 앞으로, 1번 삭제, 새 방문을 끝에
        val edited = listOf(v[2]) + v.drop(1).filter { it.id != "v3" } + newV
        r.on(TripEvent.EditPlan(edited))
        assertEquals(listOf("v3", "v2", "v4", "v5", "v6", "v7", "n1"), r.s.visits.map { it.id })
        assertEquals((1..7).toList(), r.s.visits.map { it.order })
        assertEquals("1번 박○○ 고객", r.s.visits[0].naviName)
        assertTrue(r.routes().isEmpty())
    }

    @Test
    fun editPlan_afterStart_keepsFinishedAndCurrent_reloadsRoute() {
        val r = run().on(TripEvent.StartDay)
        r.on(TripEvent.ManualStart("v1")).on(TripEvent.ManualFinish("v1")) // v1 완료, v2 가는 중
        val v = r.s.visits
        // 편집 화면이 v1·v2를 빼고 보내거나 순서를 섞어도 앞에 그대로 남는다
        val edited = listOf(v[3], v[2]) + v.drop(4) + listOf(v[0].copy(customer = "바꾸면 안 됨"))
        r.on(TripEvent.EditPlan(edited))
        assertEquals(listOf("v1", "v2", "v4", "v3", "v5", "v6", "v7"), r.s.visits.map { it.id })
        assertEquals("김○○ 고객", r.v("v1").customer)
        assertEquals(VisitStatus.DONE, r.v("v1").status)
        assertEquals(VisitStatus.EN_ROUTE, r.v("v2").status)
        assertEquals(listOf("v2", "v4", "v3"), r.routes().last())
    }

    @Test
    fun editPlan_editsPendingFields_keepsStatus() {
        val r = run().on(TripEvent.StartDay)
        val v3 = r.v("v3")
        r.on(TripEvent.EditPlan(r.s.visits.map { if (it.id == "v3") it.copy(note = "주차 B2", window = "12:00~12:30") else it }))
        assertEquals("주차 B2", r.v("v3").note)
        assertEquals("12:00~12:30", r.v("v3").window)
        assertEquals(v3.status, r.v("v3").status)
    }

    @Test
    fun editPlan_afterClose_isIgnored() {
        val r = run().on(TripEvent.StartDay)
        r.s.visits.forEach { r.on(TripEvent.ManualStart(it.id)).on(TripEvent.ManualFinish(it.id)) }
        r.on(TripEvent.CloseDay)
        val before = r.s.visits
        r.on(TripEvent.EditPlan(emptyList()))
        assertEquals(before, r.s.visits)
    }

    @Test
    fun distance_isRoughlyRight() {
        // 위도 0.01도 ≈ 1.11km
        val d = TripEngine.distanceM(37.0, 127.0, 37.01, 127.0)
        assertEquals(1112.0, d, 5.0)
    }
}
