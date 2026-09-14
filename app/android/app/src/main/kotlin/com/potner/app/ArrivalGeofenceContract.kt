package com.potner.app

internal object ArrivalGeofenceContract {
    const val CHANNEL = "potner/home_geofence"

    const val APPROACH_REQUEST_ID = "HOME_APPROACH"
    const val CANCEL_REQUEST_ID = "HOME_CANCEL"

    const val DEFAULT_APPROACH_RADIUS_METERS = 300f
    const val DEFAULT_CANCEL_RADIUS_METERS = 550f
    const val MIN_APPROACH_RADIUS_METERS = 150f
    const val MIN_RADIUS_GAP_METERS = 200f

    const val NOTIFICATION_RESPONSIVENESS_MS = 60_000
    const val FRESH_LOCATION_MAX_AGE_MS = 60_000L
    const val ACCEPTABLE_ACCURACY_METERS = 100f
    const val LOCATION_TIMEOUT_SECONDS = 15L
    const val EVENT_MAX_AGE_MS = 10 * 60 * 1_000L
    const val FUTURE_EVENT_SKEW_MS = 5 * 60 * 1_000L
    const val CANCEL_WINDOW_MS = 420 * 1_000L

    const val ALL_WORK_TAG = "arrival-geofence"
}

internal enum class GeofenceZoneState {
    DISABLED,
    INITIALIZING,
    OUTSIDE_ARMED,
    INSIDE_LOCKED,
}

internal enum class ArrivalDeliveryState {
    NONE,
    APPROACH_PENDING,
    APPROACH_ACTIVE,
    CANCEL_PENDING,
}

internal enum class GeofenceRegistrationStatus {
    NOT_REGISTERED,
    REGISTERING,
    REGISTERED,
    ERROR,
}

internal enum class NativeArrivalEventType {
    APPROACH,
    CANCEL,
}

internal data class HomeGeofenceConfig(
    val enabled: Boolean,
    val latitude: Double,
    val longitude: Double,
    val approachRadiusMeters: Float,
    val cancelRadiusMeters: Float,
    val apiBaseUrl: String,
)

internal data class HomeGeofenceRuntimeState(
    val zoneState: GeofenceZoneState = GeofenceZoneState.DISABLED,
    val deliveryState: ArrivalDeliveryState = ArrivalDeliveryState.NONE,
    val registrationStatus: GeofenceRegistrationStatus =
        GeofenceRegistrationStatus.NOT_REGISTERED,
    val visitId: String? = null,
    val lastEventId: String? = null,
    val approachOccurredAtEpochMs: Long? = null,
    val cancelableUntilEpochMs: Long? = null,
    val lastTransitionAtEpochMs: Long? = null,
    val lastErrorCode: String? = null,
)

internal data class NativeArrivalEvent(
    val eventId: String,
    val visitId: String,
    val eventType: NativeArrivalEventType,
    val geofenceId: String,
    val occurredAtEpochMs: Long,
)
