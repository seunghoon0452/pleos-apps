package kr.mobility.platform.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.mobility.core.vehicle.Gear
import kotlin.math.abs

/**
 * Vehicle SDK + 안드로이드 표준 차량 속성을 하나의 [VehicleReading]으로 모은다.
 * 차량 서비스가 없으면(일반 폰·서비스 미설치) 값이 비어 있을 뿐 앱이 죽지 않게 모든 호출을 감쌌다.
 */
class PleosVehicleSignals(private val context: Context) : VehicleSignals {
    private val _reading = MutableStateFlow(VehicleReading())
    override val reading: StateFlow<VehicleReading> = _reading.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private val cleanups = mutableListOf<() -> Unit>()

    private fun update(f: (VehicleReading) -> VehicleReading) = _reading.update(f)
    private val fail: (Exception) -> Unit = { Log.w(TAG, "vehicle value failed", it) }

    override fun start() {
        runCatching { startPleos() }.onFailure { Log.w(TAG, "Pleos Vehicle SDK unavailable", it) }
        runCatching { startCarService() }.onFailure { Log.w(TAG, "android.car unavailable", it) }
    }

    override fun stop() {
        cleanups.asReversed().forEach { runCatching(it) }
        cleanups.clear()
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        vehicle = null
        car = null
    }

    private fun startPleos() {
        val v = Vehicle(context).also { it.initialize() }
        vehicle = v

        v.getGear().getCurrentGearSelectionState({ g -> update { it.copy(gear = g?.toGear() ?: Gear.UNKNOWN) } }, fail)
        val gearListener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = update { it.copy(gear = gear?.toGear() ?: Gear.UNKNOWN) }
            override fun onFailed(e: Exception) = fail(e)
        }
        v.getGear().registerGearSelection(gearListener)
        cleanups += { v.getGear().unregisterGearSelection(gearListener) }

        // 전기차
        val battery = v.getEvBattery()
        battery.getEvBatteryStateOfCharge({ soc -> update { it.copy(socPercent = soc?.toDouble()) } }, fail)
        battery.getEvBatteryCapacity({ cap -> update { it.copy(batteryCapacityKwh = Units.kwh(cap)) } }, fail)
        battery.getCumulativeEvEfficiency({ e -> update { it.copy(evEfficiency = Units.kmPerKwh(e)) } }, fail)
        val socListener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) = update { it.copy(socPercent = value?.toDouble()) }
            override fun onFailed(e: Exception) = fail(e)
        }
        battery.registerEvBatteryStateOfCharge(socListener)
        cleanups += { battery.unregisterEvBatteryStateOfCharge(socListener) }

        // 내연기관
        val fuel = v.getFuel()
        fuel.getFuelPercent({ p -> update { it.copy(fuelPercent = p?.toDouble()) } }, fail)
        fuel.getCumulativeFuelEconomy({ e -> update { it.copy(fuelEconomy = e?.toDouble()) } }, fail)
        val fuelListener = object : FuelPercentListener {
            override fun onFuelPercentUpdated(value: Int?) = update { it.copy(fuelPercent = value?.toDouble()) }
            override fun onFailed(e: Exception) = fail(e)
        }
        fuel.registerFuelPercent(fuelListener)
        cleanups += { fuel.unregisterFuelPercent(fuelListener) }

        // 주행거리·주행가능거리
        val odo = v.getOdometer()
        odo.getCurrentDistanceDriven({ km -> update { it.copy(odometerKm = km?.toDouble()) } }, fail)
        odo.getRangeRemainingDistance({ r -> update { it.copy(evRangeKm = Units.km(r)) } }, fail)
        val odoListener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) = update { it.copy(odometerKm = value?.toDouble()) }
            override fun onFailed(e: Exception) = fail(e)
        }
        val rangeListener = object : RangeRemainingListener {
            override fun onRangeRemainingChanged(value: Float?) = update { it.copy(evRangeKm = Units.km(value)) }
            override fun onFailed(e: Exception) = fail(e)
        }
        odo.registerDistanceDriven(odoListener)
        odo.registerRangeRemainingListener(rangeListener)
        cleanups += { odo.unregisterDistanceDriven(odoListener) }
        cleanups += { odo.unregisterRangeRemainingListener(rangeListener) }

        // 비·안개·와셔액·방향지시등 (관심 주행)
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiperListener = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = update { it.copy(wiperOn = state?.isOn() == true) }
            override fun onFailed(e: Exception) = fail(e)
        }
        v.getWiper().getCurrentWiperState(front, { s -> update { it.copy(wiperOn = s?.isOn() == true) } }, fail)
        v.getWiper().registerWiperState(front, wiperListener)
        cleanups += { v.getWiper().unregisterWiperState(front, wiperListener) }

        val fogListener = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = update { it.copy(frontFogLightOn = state == LightState.ON) }
            override fun onFailed(e: Exception) = fail(e)
        }
        v.getLight().getCurrentFrontFogLightState({ s -> update { it.copy(frontFogLightOn = s == LightState.ON) } }, fail)
        v.getLight().registerFrontFogLightState(fogListener)
        cleanups += { v.getLight().unregisterFrontFogLightState(fogListener) }

        val washerListener = object : LowWasherFluidWaringListener {
            override fun onLowWasherFluidWaringUpdated(value: Boolean?) = update { it.copy(washerFluidLow = value == true) }
            override fun onFailed(e: Exception) = fail(e)
        }
        v.getSafety().hasLowWasherFluidWarning({ low -> update { it.copy(washerFluidLow = low == true) } }, fail)
        v.getSafety().registerLowWasherFluidWaringState(washerListener)
        cleanups += { v.getSafety().unregisterLowWasherFluidWaringState(washerListener) }

        val turnListener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = update { it.copy(turnSignal = value?.toState() ?: TurnSignalState.OFF) }
            override fun onFailed(e: Exception) = fail(e)
        }
        v.getTurnSignal().registerTurnSignalState(turnListener)
        cleanups += { v.getTurnSignal().unregisterTurnSignalState(turnListener) }
    }

    /** 속도·외기 온도·주차 브레이크·충전 상태·주행 제한은 안드로이드 표준 API로 읽는다. */
    private fun startCarService() {
        val c = Car.createCar(context) ?: return
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val v = value.value ?: return
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> update { it.copy(speedKmh = abs((v as Float) * 3.6)) }
                    VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> update { it.copy(outsideTempC = (v as Float).toDouble()) }
                    VehiclePropertyIds.PARKING_BRAKE_ON -> update { it.copy(parkingBrake = v as Boolean) }
                    // 1 = 충전 중 (android.car.hardware.property.EvChargeState.STATE_CHARGING)
                    VehiclePropertyIds.EV_CHARGE_STATE -> update { it.copy(charging = (v as Int) == 1) }
                    // Pleos SDK가 기어·주행가능거리는 표준 속성을 쓰라고 안내한다. 둘 다 오면 표준 값이 덮어쓴다.
                    VehiclePropertyIds.GEAR_SELECTION -> update { it.copy(gear = standardGear(v as Int)) }
                    VehiclePropertyIds.RANGE_REMAINING -> update { it.copy(evRangeKm = (v as Float) / 1000.0) }
                }
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                Log.w(TAG, "car property error $propertyId")
            }
        }
        val props = listOf(
            VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_NORMAL,
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE to CarPropertyManager.SENSOR_RATE_NORMAL,
            VehiclePropertyIds.PARKING_BRAKE_ON to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.EV_CHARGE_STATE to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.GEAR_SELECTION to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.RANGE_REMAINING to CarPropertyManager.SENSOR_RATE_NORMAL,
        )
        for ((id, rate) in props) {
            // 권한이 없거나 차가 지원하지 않는 속성은 건너뛴다.
            runCatching { pm.registerCallback(callback, id, rate) }.onFailure { Log.w(TAG, "skip property $id: ${it.message}") }
        }
        cleanups += { runCatching { pm.unregisterCallback(callback) } }
        // 배터리 용량(Wh)은 바뀌지 않는 값이라 한 번만 읽는다.
        runCatching { pm.getFloatProperty(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, 0) }
            .onSuccess { wh -> if (wh > 0) update { it.copy(batteryCapacityKwh = wh / 1000.0) } }

        val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        update { it.copy(uxRestricted = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization) }
        ux.registerListener { r -> update { it.copy(uxRestricted = r.isRequiresDistractionOptimization) } }
        cleanups += { runCatching { ux.unregisterListener() } }
    }

    private companion object {
        const val TAG = "PleosVehicle"
    }
}

/** 안드로이드 VehicleGear 비트값: N 0x1, R 0x2, P 0x4, D 0x8, 1단 이상 0x10~. */
private fun standardGear(value: Int): Gear = when (value) {
    0x1 -> Gear.NEUTRAL
    0x2 -> Gear.REVERSE
    0x4 -> Gear.PARK
    0 -> Gear.UNKNOWN
    else -> Gear.DRIVE
}

private fun VehicleGear.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE
}

private fun WiperState.isOn(): Boolean = this == WiperState.ON || this == WiperState.OTHER

private fun TurnSignalValue.toState(): TurnSignalState = when (this) {
    TurnSignalValue.LEFT -> TurnSignalState.LEFT
    TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
    else -> TurnSignalState.OFF
}

/**
 * SDK 문서에 단위가 적혀 있지 않은 값의 단위 맞추기(가안, 에뮬레이터 값으로 확인 필요).
 * 안드로이드 표준(VHAL)은 거리 m, 배터리 Wh이므로 값 크기로 구분한다.
 */
internal object Units {
    /** 주행가능거리: 2,000보다 크면 m로 보고 km로 바꾼다. */
    fun km(value: Float?): Double? = value?.toDouble()?.let { if (it > 2_000) it / 1000.0 else it }

    /** 배터리 용량: 1,000보다 크면 Wh로 보고 kWh로 바꾼다. */
    fun kwh(value: Float?): Double? = value?.toDouble()?.takeIf { it > 0 }?.let { if (it > 1_000) it / 1000.0 else it }

    /** 전비: 50보다 크면 Wh/km로 보고 km/kWh로 바꾼다. */
    fun kmPerKwh(value: Float?): Double? = value?.toDouble()?.takeIf { it > 0 }?.let { if (it > 50) 1000.0 / it else it }
}
