package app.nageune.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.nageune.trip.Gear
import app.nageune.trip.TripEvent

/**
 * Vehicle SDK에서 기어·운전석 문·계기판 누적 거리를 받아 TripEvent로 넘긴다.
 * 콜백은 바인더 스레드에서 오므로 sink 쪽에서 메인 스레드로 옮긴다.
 */
class CarSignals(
    context: Context,
    private val sink: (TripEvent) -> Unit,
    private val log: (String) -> Unit,
) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)

    var vin: String? = null
        private set

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(value: VehicleGear?) = sink(TripEvent.GearChanged(value.toGear()))
        override fun onFailed(e: Exception) = log("기어 구독 실패: ${e.message}")
    }
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            if (pos != null) sink(TripEvent.DriverDoor(pos > 0))
        }
        override fun onFailed(e: Exception) = log("문 구독 실패: ${e.message}")
    }
    private val odoListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            if (value != null) sink(TripEvent.Odometer(value))
        }
        override fun onFailed(e: Exception) = log("계기판 구독 실패: ${e.message}")
    }

    fun start() {
        runCatching { vehicle.initialize() }.onFailure { log("Vehicle 초기화 실패: ${it.message}") }
        // initialize()는 바로 반환되지만 서비스 바인딩은 조금 뒤에 끝난다.
        main.postDelayed({ subscribe(); readOnce() }, 1500)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getDoor().unregisterDoorPosition(driverDoor, doorListener)
            vehicle.getOdometer().unregisterDistanceDriven(odoListener)
        }
        runCatching { vehicle.release() }
    }

    fun readOnce() {
        vehicle.getGear().getCurrentGearSelectionState(
            { sink(TripEvent.GearChanged(it.toGear())) }, { log("기어 읽기 실패: ${it.message}") },
        )
        vehicle.getDoor().getCurrentDoorPosition(
            driverDoor, { if (it != null) sink(TripEvent.DriverDoor(it > 0)) }, { log("문 읽기 실패: ${it.message}") },
        )
        vehicle.getOdometer().getCurrentDistanceDriven(
            { if (it != null) sink(TripEvent.Odometer(it)) }, { log("계기판 읽기 실패: ${it.message}") },
        )
        vehicle.getCarInfo().getVINInfo(
            { vin = it?.trim('"') }, { log("VIN 읽기 실패: ${it.message}") },
        )
    }

    private fun subscribe() {
        runCatching { vehicle.getGear().registerGearSelection(gearListener) }
            .onFailure { log("기어 구독 예외: ${it.message}") }
        runCatching { vehicle.getDoor().registerDoorPosition(driverDoor, doorListener) }
            .onFailure { log("문 구독 예외: ${it.message}") }
        runCatching { vehicle.getOdometer().registerDistanceDriven(odoListener) }
            .onFailure { log("계기판 구독 예외: ${it.message}") }
    }

    private fun VehicleGear?.toGear(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // GEAR_DRIVE, GEAR_1..9
    }
}
