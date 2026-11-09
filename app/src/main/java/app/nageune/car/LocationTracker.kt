package app.nageune.car

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import app.nageune.trip.TripEvent

/**
 * 안드로이드 위치(gps, fused)를 받아 TripEvent.Location으로 넘긴다.
 * Pleos FusedLocation SDK는 아직 "준비 중"이라 표준 LocationManager를 쓴다.
 * 에뮬레이터에서는 `adb emu geo fix`나 Extended Controls의 경로 재생으로 움직일 수 있다.
 */
class LocationTracker(
    context: Context,
    private val sink: (TripEvent) -> Unit,
    private val log: (String) -> Unit,
) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    private var lastFixAt = 0L
    /** 마지막으로 위치 콜백을 받은 시각(기기 시계). 구독이 끊겼는지 보는 데 쓴다. */
    private var lastReceivedAt = 0L
    private var startedAt = 0L

    private val listener = LocationListener { loc: Location ->
        lastReceivedAt = System.currentTimeMillis()
        // gps와 fused가 같은 위치를 두 번 줄 수 있어 너무 촘촘한 건 거른다.
        if (loc.time - lastFixAt < 500) return@LocationListener
        lastFixAt = loc.time
        sink(TripEvent.Location(loc.latitude, loc.longitude))
    }

    /**
     * 구독이 살아 있는지 확인하고, 위치가 staleMs 넘게 안 들어왔으면 다시 건다.
     * (에뮬레이터에서 다른 앱이 화면을 덮을 때 구독이 풀린 적이 있다. 11/9)
     */
    fun ensure(staleMs: Long = 15_000) {
        val now = System.currentTimeMillis()
        val last = maxOf(lastReceivedAt, startedAt)
        if (!running || now - last > staleMs) {
            if (running) log("위치가 ${(now - last) / 1000}초째 안 들어와서 다시 구독해요")
            runCatching { lm.removeUpdates(listener) }
            running = false
            start()
        }
    }

    @SuppressLint("MissingPermission") // 호출 전에 권한을 확인한다
    fun start() {
        if (running) return
        startedAt = System.currentTimeMillis()
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            log("쓸 수 있는 위치 제공자가 없어요")
            return
        }
        providers.forEach { p ->
            runCatching {
                lm.getLastKnownLocation(p)?.let { sink(TripEvent.Location(it.latitude, it.longitude)) }
                lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            }.onFailure { log("위치 구독 실패($p): ${it.message}") }
        }
        running = true
        log("위치 받는 중: ${providers.joinToString()}")
    }

    fun stop() {
        runCatching { lm.removeUpdates(listener) }
        running = false
        log("위치 구독 해제")
    }
}
