package com.potner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeofenceStateReducerTest {
    private val outside = HomeGeofenceRuntimeState(
        zoneState = GeofenceZoneState.OUTSIDE_ARMED,
        registrationStatus = GeofenceRegistrationStatus.REGISTERED,
    )

    @Test
    fun approachEnterCreatesOnePendingVisit() {
        val first = GeofenceStateReducer.onApproachEnter(
            outside,
            nowEpochMs = 1_000,
            eventId = "event-a",
            visitId = "visit-a",
        )
        val duplicate = GeofenceStateReducer.onApproachEnter(
            first.state,
            nowEpochMs = 1_100,
            eventId = "event-b",
            visitId = "visit-b",
        )

        assertEquals(GeofenceTransitionAction.ENQUEUE_APPROACH, first.action)
        assertEquals(ArrivalDeliveryState.APPROACH_PENDING, first.state.deliveryState)
        assertEquals(GeofenceZoneState.INSIDE_LOCKED, first.state.zoneState)
        assertEquals(GeofenceTransitionAction.NONE, duplicate.action)
        assertEquals("event-a", duplicate.state.lastEventId)
    }

    @Test
    fun outerExitCancelsApproachThatServerHasNotAccepted() {
        val pending = GeofenceStateReducer.onApproachEnter(
            outside,
            nowEpochMs = 1_000,
            eventId = "event-a",
            visitId = "visit-a",
        ).state

        val decision = GeofenceStateReducer.onCancelExit(
            pending,
            nowEpochMs = 2_000,
            cancelEventId = "event-cancel",
        )

        assertEquals(GeofenceTransitionAction.CANCEL_PENDING_APPROACH, decision.action)
        assertEquals("event-a", decision.eventIdToCancel)
        assertNull(decision.event)
        assertEquals(ArrivalDeliveryState.NONE, decision.state.deliveryState)
        assertEquals(GeofenceZoneState.OUTSIDE_ARMED, decision.state.zoneState)
    }

    @Test
    fun outerExitAfterAcceptedApproachCreatesCancelWithSameVisit() {
        val pending = GeofenceStateReducer.onApproachEnter(
            outside,
            nowEpochMs = 1_000,
            eventId = "event-a",
            visitId = "visit-a",
        ).state
        val active = pending.copy(deliveryState = ArrivalDeliveryState.APPROACH_ACTIVE)

        val decision = GeofenceStateReducer.onCancelExit(
            active,
            nowEpochMs = 2_000,
            cancelEventId = "event-cancel",
        )

        assertEquals(GeofenceTransitionAction.ENQUEUE_CANCEL, decision.action)
        assertEquals("visit-a", decision.event?.visitId)
        assertEquals(NativeArrivalEventType.CANCEL, decision.event?.eventType)
        assertEquals(ArrivalDeliveryState.CANCEL_PENDING, decision.state.deliveryState)
    }

    @Test
    fun duplicateOuterExitKeepsPendingCancel() {
        val cancelPending = HomeGeofenceRuntimeState(
            zoneState = GeofenceZoneState.OUTSIDE_ARMED,
            deliveryState = ArrivalDeliveryState.CANCEL_PENDING,
            visitId = "visit-a",
            lastEventId = "event-cancel",
            approachOccurredAtEpochMs = 1_000,
            cancelableUntilEpochMs = 421_000,
            lastTransitionAtEpochMs = 2_000,
        )

        val decision = GeofenceStateReducer.onCancelExit(
            cancelPending,
            nowEpochMs = 2_100,
            cancelEventId = "duplicate-cancel",
        )

        assertEquals(GeofenceTransitionAction.NONE, decision.action)
        assertEquals(ArrivalDeliveryState.CANCEL_PENDING, decision.state.deliveryState)
        assertEquals("event-cancel", decision.state.lastEventId)
        assertEquals("visit-a", decision.state.visitId)
    }

    @Test
    fun stalePendingCancelIsCleared() {
        val cancelPending = HomeGeofenceRuntimeState(
            zoneState = GeofenceZoneState.OUTSIDE_ARMED,
            deliveryState = ArrivalDeliveryState.CANCEL_PENDING,
            visitId = "visit-a",
            lastEventId = "event-cancel",
            lastTransitionAtEpochMs = 2_000,
        )

        val normalized = GeofenceStateReducer.normalizeExpired(
            cancelPending,
            nowEpochMs = 2_000 + ArrivalGeofenceContract.EVENT_MAX_AGE_MS + 1,
        )

        assertEquals(ArrivalDeliveryState.NONE, normalized.deliveryState)
        assertNull(normalized.visitId)
        assertEquals("STALE_EVENT", normalized.lastErrorCode)
    }

    @Test
    fun outerExitAfterCancelWindowOnlyRearmsNextVisit() {
        val active = HomeGeofenceRuntimeState(
            zoneState = GeofenceZoneState.INSIDE_LOCKED,
            deliveryState = ArrivalDeliveryState.APPROACH_ACTIVE,
            visitId = "visit-a",
            lastEventId = "event-a",
            approachOccurredAtEpochMs = 1_000,
            cancelableUntilEpochMs = 421_000,
        )

        val decision = GeofenceStateReducer.onCancelExit(
            active,
            nowEpochMs = 421_001,
            cancelEventId = "event-cancel",
        )

        assertEquals(GeofenceTransitionAction.NONE, decision.action)
        assertEquals(ArrivalDeliveryState.NONE, decision.state.deliveryState)
        assertNull(decision.state.visitId)
        assertEquals(GeofenceZoneState.OUTSIDE_ARMED, decision.state.zoneState)
    }
}
