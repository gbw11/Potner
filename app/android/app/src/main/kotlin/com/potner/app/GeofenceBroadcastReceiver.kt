package com.potner.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        val manager = HomeGeofenceManager(context)
        if (event.hasError()) {
            manager.recordError("GEOFENCE_ERROR_${event.errorCode}")
            return
        }
        val requestIds = event.triggeringGeofences
            ?.map { it.requestId }
            .orEmpty()
        if (requestIds.isEmpty()) {
            return
        }
        val occurredAt = System.currentTimeMillis()
        val location = event.triggeringLocation
        if (location != null && location.hasAccuracy() &&
            location.accuracy <= ArrivalGeofenceContract.ACCEPTABLE_ACCURACY_METERS
        ) {
            GeofenceEventCoordinator(context).handleTransition(
                requestIds,
                event.geofenceTransition,
                occurredAt,
            )
        } else {
            ArrivalWorkScheduler(context).enqueueTransitionVerification(
                requestIds,
                event.geofenceTransition,
                occurredAt,
            )
        }
    }
}
