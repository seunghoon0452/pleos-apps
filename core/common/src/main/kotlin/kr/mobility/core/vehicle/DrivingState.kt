package kr.mobility.core.vehicle

/** 기어 위치. SDK의 VehicleGear를 앱 쪽에서 이 값으로 바꿔 쓴다. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 설명회 요구 "모든 상황 정의"의 상황 구분. 두 작품 모두 이 값으로 화면·소리를 고른다.
 * (미리 채움 정의서 6장, 관심 주행 정의서 상황표)
 */
enum class DrivingState {
    /** 기어 P 또는 주차 브레이크. 전체 화면·터치 가능. */
    PARKED,

    /** 충전 중(전기차). 주차와 같지만 충전 목표 화면을 보여준다. */
    CHARGING,

    /** 신호 대기: 속도 0, 기어 D/N/R. 주행 제한이 유지된다고 보고 주행 중과 같게 다룬다. */
    STOPPED,

    /** 주행 중. 한 줄 표시와 음성만. */
    DRIVING;

    /** 화면을 줄이고 음성만 써야 하는 상황. */
    val isRestricted: Boolean get() = this == DRIVING || this == STOPPED

    companion object {
        fun from(gear: Gear, speedKmh: Double, parkingBrake: Boolean = false, charging: Boolean = false): DrivingState {
            val parked = gear == Gear.PARK || (parkingBrake && speedKmh < 1.0)
            if (parked) return if (charging) CHARGING else PARKED
            return if (speedKmh < 1.0) STOPPED else DRIVING
        }
    }
}
