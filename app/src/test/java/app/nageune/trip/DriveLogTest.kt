package app.nageune.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveLogTest {

    private val profile = Profile("홍길동", "방문서비스팀", "아이오닉 5", "123가 4567")
    private val cfg = TripConfig()

    /** 출발 52,310 → 방문마다 계기판 +3km → 마지막 방문 뒤 퇴근 5km → 마감. */
    private fun fullDay(skip: String? = null): TripState {
        var s = TripState(DemoDay.visits())
        var t = 1_000_000L
        fun on(e: TripEvent) { t += 60_000; s = TripEngine.reduce(s, e, t, cfg).state }
        on(TripEvent.Odometer(52_310f))
        on(TripEvent.StartDay)
        var odo = 52_310f
        s.visits.forEach { v ->
            if (v.id == skip) { on(TripEvent.Skip(v.id)); return@forEach }
            odo += 3f
            on(TripEvent.Odometer(odo))
            on(TripEvent.ManualStart(v.id))
            on(TripEvent.ManualFinish(v.id))
        }
        on(TripEvent.Odometer(odo + 5f))
        on(TripEvent.CloseDay)
        return s
    }

    @Test
    fun closeDay_recordsCloseOdometer_once() {
        val s = fullDay()
        assertEquals(52_336f, s.closeOdo)
        assertNotNull(s.closedAt)
        val again = TripEngine.reduce(s.copy(odometerKm = 52_400f), TripEvent.CloseDay, 9_999_999L, cfg)
        assertEquals(52_336f, again.state.closeOdo)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun closeDay_beforeStart_doesNothing() {
        val r = TripEngine.reduce(TripState(DemoDay.visits(), odometerKm = 52_310f), TripEvent.CloseDay, 1L, cfg)
        assertNull(r.state.closedAt)
    }

    @Test
    fun fromDay_buildsLegs_commuteAtEnds_businessBetween() {
        val r = DriveLog.fromDay(fullDay(), profile, "2026-11-09", "월")!!
        assertEquals(52_310f, r.startOdo)
        assertEquals(52_336f, r.endOdo)
        assertEquals(26f, r.totalKm, 0.01f)
        // 출발→1번(3) + 7번→마감(5) = 출·퇴근 8, 방문 사이 6구간 × 3 = 18
        assertEquals(8f, r.commuteKm, 0.01f)
        assertEquals(18f, r.businessKm, 0.01f)
        assertEquals(r.totalKm, r.workKm, 0.01f)
        assertEquals(8, r.legs.size)
        assertEquals("출발", r.legs.first().from)
        assertEquals("운행 마감", r.legs.last().to)
        assertEquals(LegKind.COMMUTE, r.legs.first().kind)
        assertEquals(LegKind.BUSINESS, r.legs[1].kind)
        assertEquals(7, r.visitCount)
        assertTrue(r.note.startsWith("방문 7곳 (김○○ 고객 외 6곳)"))
    }

    @Test
    fun fromDay_legsAreContiguous() {
        val r = DriveLog.fromDay(fullDay(), profile, "2026-11-09", "월")!!
        r.legs.zipWithNext().forEach { (a, b) -> assertEquals(a.endOdo, b.startOdo, 0.001f) }
        assertEquals(r.totalKm, r.legs.sumOf { it.km.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun fromDay_skippedVisit_isNotALeg_andNoted() {
        val r = DriveLog.fromDay(fullDay(skip = "v3"), profile, "2026-11-09", "월")!!
        assertEquals(6, r.visitCount)
        assertTrue(r.legs.none { it.to.contains("박○○") })
        assertTrue(r.note.contains("건너뜀 1곳"))
    }

    @Test
    fun fromDay_withoutStartOdometer_isNull() {
        val s = TripState(DemoDay.visits(), started = true)
        assertNull(DriveLog.fromDay(s, profile, "2026-11-09", "월"))
    }

    @Test
    fun upsert_replacesSameDate_andSorts() {
        val week = DriveLog.sampleWeek(profile)
        val today = DriveLog.fromDay(fullDay(), profile, "2026-11-09", "월")!!
        val once = DriveLog.upsert(week, today)
        val twice = DriveLog.upsert(once, today.copy(note = "다시"))
        assertEquals(6, twice.size)
        assertEquals("2026-11-09", twice.last().date)
        assertEquals("다시", twice.last().note)
    }

    @Test
    fun summarize_countsGapsBetweenDaysAsPersonal() {
        val week = DriveLog.sampleWeek(profile)
        val today = DriveLog.fromDay(fullDay(), profile, "2026-11-09", "월")!!
        val sum = DriveLog.summarize(week + today)
        // 52,021.6 → 52,336.0
        assertEquals(314.4f, sum.totalKm, 0.05f)
        // 기록 사이 빈 거리: 11/3→11/4 3.2km + 주말 52,291.4→52,310.0 18.6km
        assertEquals(21.8f, sum.personalKm, 0.05f)
        assertEquals((314.4f - 21.8f) / 314.4f, sum.ratio!!, 0.001f)
    }

    @Test
    fun summarize_empty() {
        val sum = DriveLog.summarize(emptyList())
        assertEquals(0f, sum.totalKm)
        assertNull(sum.ratio)
    }
}
