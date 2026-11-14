package kr.mobility.platform.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * NaviHelper SDK 연결.
 * - 주의 지점: onCautionPointInfo (종류·남은 거리)
 * - 돌발·혼잡: RouteStateInfo.trafficStatus (주기적으로 요청)
 * - 거리·도착 배터리: requestTrip → onTripInfo (미리 채움 R-06 ①)
 */
class PleosNaviSource(
    private val context: Context,
    private val refreshMillis: Long = 10_000,
) : NaviSource {
    private val _state = MutableStateFlow(NaviState())
    override val state: StateFlow<NaviState> = _state.asStateFlow()

    private var helper: NaviHelper? = null
    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null
    private val tripMutex = Mutex()
    @Volatile private var pendingTrip: CompletableDeferred<TripEstimate?>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCautionPointInfo(cautionPoints: List<CautionPoint>) {
            _state.update { s -> s.copy(cautions = cautionPoints.map { NaviCaution(it.id, it.type.name, it.remainDistance) }) }
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            _state.update { it.copy(routeGuidance = navigationStatus == NavigationStatus.ROUTE_DRIVE) }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val traffic = routeStateInfo.trafficStatus ?: return
            _state.update { s ->
                s.copy(
                    incidents = traffic.trafficEventList.map { NaviIncident(it.eventType.name, it.latitude, it.longitude, it.description) },
                    congestion = traffic.congestionItemList.map {
                        NaviCongestion(it.trafficCongestionType.name, it.offset, it.distance)
                    },
                )
            }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _state.update { it.copy(position = NaviPosition(currentLocationInfo.latitude, currentLocationInfo.longitude, currentLocationInfo.heading)) }
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            _state.update { it.copy(speedLimitKmh = speedLimit) }
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            pendingTrip?.complete(TripEstimate(tripInfo.distance, tripInfo.eta, tripInfo.estimatedSocAtDest.takeIf { it >= 0 }))
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
            if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) pendingTrip?.complete(null)
        }
    }

    override fun start() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        runCatching {
            val h = NaviHelper(context)
            helper = h
            h.initialize(
                {
                    h.addListener(listener)
                    refreshJob = s.launch {
                        while (isActive) {
                            runCatching {
                                h.getRouteStateInfo()
                                h.getCurrentLocationInfo()
                            }
                            delay(refreshMillis)
                        }
                    }
                },
                { e -> Log.w(TAG, "NaviHelper init failed", e) },
            )
        }.onFailure { Log.w(TAG, "NaviHelper unavailable", it) }
    }

    override fun stop() {
        refreshJob?.cancel()
        runCatching { helper?.removeListener(listener) }
        runCatching { helper?.release() }
        helper = null
        scope?.cancel()
        scope = null
    }

    override suspend fun estimateTrip(lat: Double, lng: Double, name: String): TripEstimate? {
        val h = helper ?: return null
        return tripMutex.withLock {
            val deferred = CompletableDeferred<TripEstimate?>()
            pendingTrip = deferred
            runCatching { h.requestTrip(TripRequest(lat, lng, "", name, "")) }.onFailure { deferred.complete(null) }
            withTimeoutOrNull(TRIP_TIMEOUT_MILLIS) { deferred.await() }.also { pendingTrip = null }
        }
    }

    override fun guideTo(lat: Double, lng: Double, name: String): Boolean {
        val h = helper ?: return false
        return runCatching {
            if (_state.value.routeGuidance) {
                h.addWaypoint(RequestWaypointInfo(lat, lng, name, WaypointIndex.FIRST, "", "", "", ""))
            } else {
                h.requestRoute(RouteInfo(latitude = lat, longitude = lng, poiName = name, poiId = "", address = "", poiSubId = ""))
            }
        }.onFailure { Log.w(TAG, "guideTo failed", it) }.isSuccess
    }

    private companion object {
        const val TAG = "PleosNavi"
        const val TRIP_TIMEOUT_MILLIS = 5_000L
    }
}
