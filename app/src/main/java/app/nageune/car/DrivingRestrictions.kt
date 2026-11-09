package app.nageune.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context

/**
 * 주행 중 화면 제한 상태.
 * @param driving 주행 중 최적화(DO)가 필요한가. true면 주행 화면만 보여준다.
 * @param maxStringLength 글자 수 제한이 걸렸을 때의 최대 길이(없으면 null).
 * @param fromSystem 안드로이드 자동차 UX 제한 서비스에서 받은 값인지, 기어로 추정한 값인지.
 */
data class UxState(
    val driving: Boolean,
    val maxStringLength: Int? = null,
    val fromSystem: Boolean = false,
)

/**
 * AAOS CarUxRestrictionsManager로 "지금 주행 중 최적화가 필요한가"를 받는다.
 * 나라·차종마다 제한 규칙이 다르므로(한국: 주행 중 영상·긴 글·설정 제한) 직접 판단하지 않고 시스템 값을 따른다.
 * 서비스를 못 쓰는 환경이면 null을 돌려주고, 호출 쪽에서 기어로 대신 판단한다.
 */
class DrivingRestrictions(
    private val context: Context,
    private val sink: (UxState) -> Unit,
    private val log: (String) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    /** 연결되면 true. */
    fun start(): Boolean = try {
        val c = Car.createCar(context.applicationContext)
        val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        car = c
        manager = m
        m.registerListener { sink(it.toState()) }
        sink(m.currentCarUxRestrictions.toState())
        log("주행 제한 서비스 연결됨")
        true
    } catch (t: Throwable) { // android.car 라이브러리가 없거나 서비스 연결 실패
        log("주행 제한 서비스를 못 써서 기어로 판단해요: ${t.message}")
        false
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private fun CarUxRestrictions.toState(): UxState {
        val limited = activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0
        return UxState(
            driving = isRequiresDistractionOptimization,
            maxStringLength = if (limited) maxRestrictedStringLength.takeIf { it > 0 } else null,
            fromSystem = true,
        )
    }
}
