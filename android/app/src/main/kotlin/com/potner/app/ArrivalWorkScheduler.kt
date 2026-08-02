package com.potner.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class ArrivalWorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueueEvent(event: NativeArrivalEvent) {
        val input = Data.Builder()
            .putString(ArrivalEventWorker.KEY_EVENT_ID, event.eventId)
            .putString(ArrivalEventWorker.KEY_VISIT_ID, event.visitId)
            .putString(ArrivalEventWorker.KEY_EVENT_TYPE, event.eventType.name)
            .putString(ArrivalEventWorker.KEY_GEOFENCE_ID, event.geofenceId)
            .putLong(ArrivalEventWorker.KEY_OCCURRED_AT, event.occurredAtEpochMs)
            .build()
        val request = OneTimeWorkRequestBuilder<ArrivalEventWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ArrivalGeofenceContract.ALL_WORK_TAG)
            .addTag(visitTag(event.visitId))
            .addTag(eventTag(event.eventId))
            .build()
        workManager.enqueueUniqueWork(
            eventWorkName(event.eventId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelEvent(eventId: String) {
        workManager.cancelUniqueWork(eventWorkName(eventId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(ArrivalGeofenceContract.ALL_WORK_TAG)
    }

    fun enqueueTransitionVerification(
        requestIds: List<String>,
        transition: Int,
        occurredAtEpochMs: Long,
    ) {
        if (requestIds.isEmpty()) {
            return
        }
        val input = Data.Builder()
            .putStringArray(GeofenceTransitionWorker.KEY_REQUEST_IDS, requestIds.toTypedArray())
            .putInt(GeofenceTransitionWorker.KEY_TRANSITION, transition)
            .putLong(GeofenceTransitionWorker.KEY_OCCURRED_AT, occurredAtEpochMs)
            .build()
        val request = OneTimeWorkRequestBuilder<GeofenceTransitionWorker>()
            .setInputData(input)
            .setInitialDelay(15, TimeUnit.SECONDS)
            .addTag(ArrivalGeofenceContract.ALL_WORK_TAG)
            .build()
        workManager.enqueue(request)
    }

    private fun eventWorkName(eventId: String) = "arrival-event-$eventId"

    private fun eventTag(eventId: String) = "arrival-event-tag-$eventId"

    private fun visitTag(visitId: String) = "arrival-visit-$visitId"
}
