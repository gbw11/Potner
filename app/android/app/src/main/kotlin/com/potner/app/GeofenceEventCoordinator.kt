package com.potner.app

import android.content.Context
import com.google.android.gms.location.Geofence
import java.util.UUID

internal class GeofenceEventCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val storage = ArrivalSecureStorage.get(appContext)
    private val configStore = GeofenceConfigStore(storage)
    private val stateStore = GeofenceStateStore(storage)
    private val scheduler = ArrivalWorkScheduler(appContext)

    fun handleTransition(
        requestIds: List<String>,
        transition: Int,
        occurredAtEpochMs: Long = System.currentTimeMillis(),
    ) = synchronized(STATE_LOCK) {
        val config = configStore.read()
        if (config?.enabled != true) {
            return@synchronized
        }

        var state = GeofenceStateReducer.normalizeExpired(stateStore.read(), occurredAtEpochMs)
        for (requestId in requestIds.distinct()) {
            val decision = when {
                requestId == ArrivalGeofenceContract.APPROACH_REQUEST_ID &&
                    transition == Geofence.GEOFENCE_TRANSITION_ENTER ->
                    GeofenceStateReducer.onApproachEnter(
                        state,
                        occurredAtEpochMs,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                    )

                requestId == ArrivalGeofenceContract.CANCEL_REQUEST_ID &&
                    transition == Geofence.GEOFENCE_TRANSITION_EXIT ->
                    GeofenceStateReducer.onCancelExit(
                        state,
                        occurredAtEpochMs,
                        UUID.randomUUID().toString(),
                    )

                else -> GeofenceTransitionDecision(state, GeofenceTransitionAction.NONE)
            }
            state = decision.state
            // Worker가 매우 빨리 시작해도 PENDING 상태를 먼저 보도록 상태를 선저장한다.
            stateStore.save(state)
            when (decision.action) {
                GeofenceTransitionAction.ENQUEUE_APPROACH,
                GeofenceTransitionAction.ENQUEUE_CANCEL,
                -> decision.event?.let(scheduler::enqueueEvent)

                GeofenceTransitionAction.CANCEL_PENDING_APPROACH ->
                    decision.eventIdToCancel?.let(scheduler::cancelEvent)

                GeofenceTransitionAction.NONE -> Unit
            }
        }
    }

    fun markDelivered(event: NativeArrivalEvent) = synchronized(STATE_LOCK) {
        val current = stateStore.read()
        if (current.lastEventId != event.eventId || current.visitId != event.visitId) {
            return@synchronized
        }
        val next = when (event.eventType) {
            NativeArrivalEventType.APPROACH -> {
                if (current.deliveryState != ArrivalDeliveryState.APPROACH_PENDING) {
                    return@synchronized
                }
                current.copy(
                    deliveryState = ArrivalDeliveryState.APPROACH_ACTIVE,
                    lastErrorCode = null,
                )
            }

            NativeArrivalEventType.CANCEL -> current.copy(
                deliveryState = ArrivalDeliveryState.NONE,
                visitId = null,
                lastEventId = null,
                approachOccurredAtEpochMs = null,
                cancelableUntilEpochMs = null,
                lastErrorCode = null,
            )
        }
        stateStore.save(next)
    }

    fun isPending(event: NativeArrivalEvent): Boolean = synchronized(STATE_LOCK) {
        val current = stateStore.read()
        current.lastEventId == event.eventId &&
            current.visitId == event.visitId &&
            when (event.eventType) {
                NativeArrivalEventType.APPROACH ->
                    current.deliveryState == ArrivalDeliveryState.APPROACH_PENDING
                NativeArrivalEventType.CANCEL ->
                    current.deliveryState == ArrivalDeliveryState.CANCEL_PENDING
            }
    }

    fun markPermanentFailure(event: NativeArrivalEvent, errorCode: String) =
        synchronized(STATE_LOCK) {
            val current = stateStore.read()
            if (current.lastEventId != event.eventId || current.visitId != event.visitId) {
                return@synchronized
            }
            stateStore.save(
                current.copy(
                    deliveryState = ArrivalDeliveryState.NONE,
                    visitId = null,
                    lastEventId = null,
                    approachOccurredAtEpochMs = null,
                    cancelableUntilEpochMs = null,
                    lastErrorCode = errorCode,
                ),
            )
        }

    fun currentState(nowEpochMs: Long = System.currentTimeMillis()): HomeGeofenceRuntimeState =
        synchronized(STATE_LOCK) {
            val current = stateStore.read()
            val normalized = GeofenceStateReducer.normalizeExpired(current, nowEpochMs)
            if (normalized != current) {
                stateStore.save(normalized)
            }
            normalized
        }

    companion object {
        private val STATE_LOCK = Any()
    }
}
