package com.potner.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

internal class GeofenceTransitionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    @SuppressLint("MissingPermission")
    override fun doWork(): Result {
        val requestIds = inputData.getStringArray(KEY_REQUEST_IDS)?.toList().orEmpty()
        val transition = inputData.getInt(KEY_TRANSITION, -1)
        val occurredAt = inputData.getLong(KEY_OCCURRED_AT, -1L)
        if (requestIds.isEmpty() || occurredAt <= 0L ||
            transition !in listOf(
                Geofence.GEOFENCE_TRANSITION_ENTER,
                Geofence.GEOFENCE_TRANSITION_EXIT,
            )
        ) {
            return Result.failure()
        }
        val now = System.currentTimeMillis()
        if (now - occurredAt > ArrivalGeofenceContract.EVENT_MAX_AGE_MS) {
            return Result.failure()
        }

        val manager = HomeGeofenceManager(applicationContext)
        if (!manager.hasPreciseLocationPermission()) {
            manager.recordError("PRECISE_LOCATION_REQUIRED")
            return Result.failure()
        }
        val config = GeofenceConfigStore(ArrivalSecureStorage.get(applicationContext)).read()
        if (config?.enabled != true) {
            return Result.success()
        }
        val source = CancellationTokenSource()
        val location = runCatching {
            Tasks.await(
                LocationServices.getFusedLocationProviderClient(applicationContext)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, source.token),
                ArrivalGeofenceContract.LOCATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }.getOrNull()
        if (location == null || !location.hasAccuracy() ||
            location.accuracy > ArrivalGeofenceContract.ACCEPTABLE_ACCURACY_METERS
        ) {
            manager.recordError("LOCATION_ACCURACY_TOO_LOW")
            return Result.failure()
        }
        val distance = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            config.latitude,
            config.longitude,
            distance,
        )
        val confirmedIds = requestIds.filter { requestId ->
            when {
                requestId == ArrivalGeofenceContract.APPROACH_REQUEST_ID &&
                    transition == Geofence.GEOFENCE_TRANSITION_ENTER ->
                    distance[0] <= config.approachRadiusMeters

                requestId == ArrivalGeofenceContract.CANCEL_REQUEST_ID &&
                    transition == Geofence.GEOFENCE_TRANSITION_EXIT ->
                    distance[0] >= config.cancelRadiusMeters

                else -> false
            }
        }
        if (confirmedIds.isNotEmpty()) {
            GeofenceEventCoordinator(applicationContext).handleTransition(
                confirmedIds,
                transition,
                occurredAt,
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_REQUEST_IDS = "request_ids"
        const val KEY_TRANSITION = "transition"
        const val KEY_OCCURRED_AT = "occurred_at_epoch_ms"
    }
}
