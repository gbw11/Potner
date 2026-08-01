package com.potner.app

internal enum class GeofenceTransitionAction {
    NONE,
    ENQUEUE_APPROACH,
    CANCEL_PENDING_APPROACH,
    ENQUEUE_CANCEL,
}

internal data class GeofenceTransitionDecision(
    val state: HomeGeofenceRuntimeState,
    val action: GeofenceTransitionAction,
    val event: NativeArrivalEvent? = null,
    val eventIdToCancel: String? = null,
)

internal object GeofenceStateReducer {
    fun normalizeExpired(
        state: HomeGeofenceRuntimeState,
        nowEpochMs: Long,
    ): HomeGeofenceRuntimeState {
        val approachExpired = state.approachOccurredAtEpochMs?.let {
            nowEpochMs - it > ArrivalGeofenceContract.EVENT_MAX_AGE_MS
        } ?: false
        val cancelWindowExpired = state.cancelableUntilEpochMs?.let {
            nowEpochMs > it
        } ?: false
        val cancelExpired = state.lastTransitionAtEpochMs?.let {
            nowEpochMs - it > ArrivalGeofenceContract.EVENT_MAX_AGE_MS
        } ?: false
        if (state.deliveryState == ArrivalDeliveryState.APPROACH_PENDING && approachExpired) {
            return state.clearVisit(lastErrorCode = "STALE_EVENT")
        }
        if (state.deliveryState == ArrivalDeliveryState.APPROACH_ACTIVE && cancelWindowExpired) {
            return state.clearVisit()
        }
        if (state.deliveryState == ArrivalDeliveryState.CANCEL_PENDING && cancelExpired) {
            return state.clearVisit(lastErrorCode = "STALE_EVENT")
        }
        return state
    }

    fun onApproachEnter(
        state: HomeGeofenceRuntimeState,
        nowEpochMs: Long,
        eventId: String,
        visitId: String,
    ): GeofenceTransitionDecision {
        val normalized = normalizeExpired(state, nowEpochMs)
        if (normalized.zoneState != GeofenceZoneState.OUTSIDE_ARMED ||
            normalized.deliveryState != ArrivalDeliveryState.NONE
        ) {
            return GeofenceTransitionDecision(normalized, GeofenceTransitionAction.NONE)
        }
        val event = NativeArrivalEvent(
            eventId = eventId,
            visitId = visitId,
            eventType = NativeArrivalEventType.APPROACH,
            geofenceId = ArrivalGeofenceContract.APPROACH_REQUEST_ID,
            occurredAtEpochMs = nowEpochMs,
        )
        return GeofenceTransitionDecision(
            state = normalized.copy(
                zoneState = GeofenceZoneState.INSIDE_LOCKED,
                deliveryState = ArrivalDeliveryState.APPROACH_PENDING,
                visitId = visitId,
                lastEventId = eventId,
                approachOccurredAtEpochMs = nowEpochMs,
                cancelableUntilEpochMs = nowEpochMs + ArrivalGeofenceContract.CANCEL_WINDOW_MS,
                lastTransitionAtEpochMs = nowEpochMs,
                lastErrorCode = null,
            ),
            action = GeofenceTransitionAction.ENQUEUE_APPROACH,
            event = event,
        )
    }

    fun onCancelExit(
        state: HomeGeofenceRuntimeState,
        nowEpochMs: Long,
        cancelEventId: String,
    ): GeofenceTransitionDecision {
        val normalized = normalizeExpired(state, nowEpochMs)
        if (normalized.deliveryState == ArrivalDeliveryState.APPROACH_PENDING) {
            return GeofenceTransitionDecision(
                state = normalized.clearVisit().copy(
                    zoneState = GeofenceZoneState.OUTSIDE_ARMED,
                    lastTransitionAtEpochMs = nowEpochMs,
                ),
                action = GeofenceTransitionAction.CANCEL_PENDING_APPROACH,
                eventIdToCancel = normalized.lastEventId,
            )
        }

        if (normalized.deliveryState == ArrivalDeliveryState.CANCEL_PENDING) {
            return GeofenceTransitionDecision(normalized, GeofenceTransitionAction.NONE)
        }

        val visitId = normalized.visitId
        val cancelableUntil = normalized.cancelableUntilEpochMs
        if (normalized.deliveryState == ArrivalDeliveryState.APPROACH_ACTIVE &&
            visitId != null &&
            cancelableUntil != null &&
            nowEpochMs <= cancelableUntil
        ) {
            val event = NativeArrivalEvent(
                eventId = cancelEventId,
                visitId = visitId,
                eventType = NativeArrivalEventType.CANCEL,
                geofenceId = ArrivalGeofenceContract.CANCEL_REQUEST_ID,
                occurredAtEpochMs = nowEpochMs,
            )
            return GeofenceTransitionDecision(
                state = normalized.copy(
                    zoneState = GeofenceZoneState.OUTSIDE_ARMED,
                    deliveryState = ArrivalDeliveryState.CANCEL_PENDING,
                    lastEventId = cancelEventId,
                    lastTransitionAtEpochMs = nowEpochMs,
                    lastErrorCode = null,
                ),
                action = GeofenceTransitionAction.ENQUEUE_CANCEL,
                event = event,
            )
        }

        return GeofenceTransitionDecision(
            state = normalized.clearVisit().copy(
                zoneState = GeofenceZoneState.OUTSIDE_ARMED,
                lastTransitionAtEpochMs = nowEpochMs,
            ),
            action = GeofenceTransitionAction.NONE,
        )
    }

    private fun HomeGeofenceRuntimeState.clearVisit(
        lastErrorCode: String? = this.lastErrorCode,
    ): HomeGeofenceRuntimeState = copy(
        deliveryState = ArrivalDeliveryState.NONE,
        visitId = null,
        lastEventId = null,
        approachOccurredAtEpochMs = null,
        cancelableUntilEpochMs = null,
        lastErrorCode = lastErrorCode,
    )
}
