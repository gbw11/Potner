package com.potner.app

internal class GeofenceStateStore(
    private val storage: ArrivalSecureStorage,
) {
    @Synchronized
    fun read(): HomeGeofenceRuntimeState = HomeGeofenceRuntimeState(
        zoneState = enumValue(storage.read(KEY_ZONE), GeofenceZoneState.DISABLED),
        deliveryState = enumValue(storage.read(KEY_DELIVERY), ArrivalDeliveryState.NONE),
        registrationStatus = enumValue(
            storage.read(KEY_REGISTRATION),
            GeofenceRegistrationStatus.NOT_REGISTERED,
        ),
        visitId = storage.read(KEY_VISIT_ID),
        lastEventId = storage.read(KEY_LAST_EVENT_ID),
        approachOccurredAtEpochMs = storage.read(KEY_APPROACH_OCCURRED_AT)?.toLongOrNull(),
        cancelableUntilEpochMs = storage.read(KEY_CANCELABLE_UNTIL)?.toLongOrNull(),
        lastTransitionAtEpochMs = storage.read(KEY_LAST_TRANSITION_AT)?.toLongOrNull(),
        lastErrorCode = storage.read(KEY_LAST_ERROR),
    )

    @Synchronized
    fun save(state: HomeGeofenceRuntimeState) {
        storage.write(KEY_ZONE, state.zoneState.name)
        storage.write(KEY_DELIVERY, state.deliveryState.name)
        storage.write(KEY_REGISTRATION, state.registrationStatus.name)
        storage.write(KEY_VISIT_ID, state.visitId)
        storage.write(KEY_LAST_EVENT_ID, state.lastEventId)
        storage.write(KEY_APPROACH_OCCURRED_AT, state.approachOccurredAtEpochMs?.toString())
        storage.write(KEY_CANCELABLE_UNTIL, state.cancelableUntilEpochMs?.toString())
        storage.write(KEY_LAST_TRANSITION_AT, state.lastTransitionAtEpochMs?.toString())
        storage.write(KEY_LAST_ERROR, state.lastErrorCode)
    }

    @Synchronized
    fun clear() {
        storage.remove(ALL_KEYS)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_ZONE = "potner.arrival.zone_state"
        const val KEY_DELIVERY = "potner.arrival.delivery_state"
        const val KEY_REGISTRATION = "potner.arrival.registration_status"
        const val KEY_VISIT_ID = "potner.arrival.visit_id"
        const val KEY_LAST_EVENT_ID = "potner.arrival.last_event_id"
        const val KEY_APPROACH_OCCURRED_AT = "potner.arrival.approach_occurred_at"
        const val KEY_CANCELABLE_UNTIL = "potner.arrival.cancelable_until"
        const val KEY_LAST_TRANSITION_AT = "potner.arrival.last_transition_at"
        const val KEY_LAST_ERROR = "potner.arrival.last_error"
        val ALL_KEYS = listOf(
            KEY_ZONE,
            KEY_DELIVERY,
            KEY_REGISTRATION,
            KEY_VISIT_ID,
            KEY_LAST_EVENT_ID,
            KEY_APPROACH_OCCURRED_AT,
            KEY_CANCELABLE_UNTIL,
            KEY_LAST_TRANSITION_AT,
            KEY_LAST_ERROR,
        )
    }
}
