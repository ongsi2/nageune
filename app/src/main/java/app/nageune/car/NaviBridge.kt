package app.nageune.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import app.nageune.trip.TripEvent
import app.nageune.trip.Visit

/**
 * NaviHelper 연결. 방문 묶음(최대 3곳)을 경유지 2 + 목적지 1로 넣고,
 * 도착·남은 시간 이벤트를 방문 이름(naviName)으로 TripEvent에 넘긴다.
 * 고객 주소에는 내비 POI ID가 없으므로 좌표만 넣는다(11/9 에뮬레이터에서 동작 확인).
 */
class NaviBridge(
    context: Context,
    private val sink: (TripEvent) -> Unit,
    private val log: (String) -> Unit,
) {
    private val navi = NaviHelper(context.applicationContext)
    @Volatile var connected = false
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onWayPointArrived(info: WaypointArrivedInfo) = sink(TripEvent.NaviArrived(info.name))
        override fun onDestinationArrived(info: DestinationArrivedInfo) = sink(TripEvent.NaviArrived(info.name))
        override fun onWayPointInfo(list: List<WaypointInfo>) =
            list.forEach { sink(TripEvent.NaviEtaInfo(it.name, it.remainTime, it.remainDistance)) }
        override fun onDestinationInfo(info: DestinationInfo) =
            sink(TripEvent.NaviEtaInfo(info.name, info.remainTime, info.remainDistance))
        override fun onRoutingCompleted() = refreshEta()
        override fun onError(code: NaviErrorCode) = log("내비 오류: ${code.name}")
    }

    fun start() {
        navi.initialize(
            {
                connected = true
                navi.addListener(listener)
                log("내비 연결됨")
                // 하루가 이미 시작돼 있으면 엔진이 현재 묶음 경로를 다시 넣으라고 돌려준다.
                sink(TripEvent.NaviConnected)
            },
            { log("내비 연결 실패: ${it.message}") },
        )
    }

    fun stop() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        connected = false
    }

    /** 마지막이 목적지, 앞의 1~2곳이 경유지. */
    fun load(stops: List<Visit>) {
        if (stops.isEmpty()) return
        if (!connected) {
            log("내비 연결 전이라 연결되면 경로를 넣어요")
            return
        }
        val dest = stops.last()
        val waypoints = stops.dropLast(1).take(2).mapIndexed { i, v ->
            RequestWaypointInfo(
                latitude = v.lat, longitude = v.lng, poiName = v.naviName,
                waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                poiId = "", address = v.address, poiSubId = "",
            )
        }
        val destination = RouteInfo(
            latitude = dest.lat, longitude = dest.lng, poiName = dest.naviName,
            poiId = "", address = dest.address, poiSubId = "",
        )
        runCatching {
            if (waypoints.isEmpty()) navi.requestRoute(destination)
            else navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))
        }.onSuccess {
            log("내비 경로: " + stops.joinToString(" → ") { it.naviName })
        }.onFailure { log("경로 요청 실패: ${it.message}") }
    }

    /** 내비에 들어 있는 경로를 지운다(일정을 처음 상태로 돌릴 때). */
    fun cancel() {
        if (!connected) return
        runCatching { navi.cancelRoute() }.onFailure { log("경로 취소 실패: ${it.message}") }
    }

    /** 경유지·목적지의 남은 시간을 다시 받아온다(결과는 리스너로 온다). */
    fun refreshEta() {
        if (!connected) return
        runCatching {
            navi.getWaypointInfo()
            navi.getDestinationInfo()
        }
    }
}
