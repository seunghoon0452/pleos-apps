package kr.mobility.platform.pleos

import android.content.Context

/** 앱이 쓰는 입구 묶음. 실제 차(Pleos SDK)와 시연 모드(가짜)를 같은 모양으로 넘긴다. */
class Platform(
    val vehicle: VehicleSignals,
    val navi: NaviSource,
    val speaker: CaptionedSpeaker,
    val listener: Listener,
    val location: LocationSource,
    val llm: TextGenerator?,
    val isDemo: Boolean,
) {
    fun start() {
        vehicle.start()
        navi.start()
        location.start()
    }

    fun stop() {
        vehicle.stop()
        navi.stop()
        location.stop()
    }

    fun release() {
        stop()
        speaker.release()
        listener.release()
        llm?.release()
    }

    companion object {
        fun pleos(context: Context): Platform {
            val app = context.applicationContext
            return Platform(
                vehicle = PleosVehicleSignals(app),
                navi = PleosNaviSource(app),
                speaker = CaptionedSpeaker(PleosSpeaker(app)),
                listener = PleosListener(app),
                location = PleosLocationSource(app),
                llm = PleosTextGenerator(app),
                isDemo = false,
            )
        }

        /**
         * 시연 모드. 차량·내비는 가짜지만 음성은 진짜 TTS로 읽는다(심사위원이 들을 수 있게).
         * [speakAloud]가 false면 자막만 남긴다(자동 테스트).
         */
        fun demo(
            context: Context?,
            vehicle: FakeVehicleSignals = FakeVehicleSignals(),
            location: FixedLocationSource = FixedLocationSource(null),
            listener: Listener = TypedListener(),
            speakAloud: Boolean = true,
        ): Platform {
            val app = context?.applicationContext
            val tts = if (speakAloud && app != null) PleosSpeaker(app) else null
            return Platform(
                vehicle = vehicle,
                navi = FakeNaviSource { location.fix.value?.let { kr.mobility.core.geo.GeoPoint(it.lat, it.lng) } },
                speaker = CaptionedSpeaker(tts),
                listener = listener,
                location = location,
                llm = null,
                isDemo = true,
            )
        }
    }
}
