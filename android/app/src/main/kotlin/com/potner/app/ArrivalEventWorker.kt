package com.potner.app

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters

internal class ArrivalEventWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val event = readEvent() ?: return failure("INVALID_WORK_INPUT")
        val now = System.currentTimeMillis()
        if (now - event.occurredAtEpochMs > ArrivalGeofenceContract.EVENT_MAX_AGE_MS) {
            coordinator().markPermanentFailure(event, "STALE_EVENT")
            return failure("STALE_EVENT")
        }
        if (event.occurredAtEpochMs - now > ArrivalGeofenceContract.FUTURE_EVENT_SKEW_MS) {
            coordinator().markPermanentFailure(event, "INVALID_EVENT_TIME")
            return failure("INVALID_EVENT_TIME")
        }

        val storage = ArrivalSecureStorage.get(applicationContext)
        val config = GeofenceConfigStore(storage).read()
            ?: run {
                coordinator().markPermanentFailure(event, "GEOFENCE_CONFIG_MISSING")
                return failure("GEOFENCE_CONFIG_MISSING")
            }
        val coordinator = coordinator()
        if (!config.enabled || !coordinator.isPending(event)) {
            return Result.success()
        }
        val client = ArrivalApiClient(FlutterSecureArrivalTokenStore(storage))
        return when (val response = client.sendEvent(config, event)) {
            is ArrivalApiResult.Success -> {
                coordinator.markDelivered(event)
                Result.success(
                    Data.Builder().putString(KEY_RESULT_STATUS, response.status).build(),
                )
            }

            is ArrivalApiResult.RetryableFailure -> {
                if (System.currentTimeMillis() - event.occurredAtEpochMs >=
                    ArrivalGeofenceContract.EVENT_MAX_AGE_MS
                ) {
                    coordinator.markPermanentFailure(event, "STALE_EVENT")
                    failure("STALE_EVENT")
                } else {
                    Result.retry()
                }
            }

            is ArrivalApiResult.PermanentFailure -> {
                coordinator.markPermanentFailure(event, response.errorCode)
                failure(response.errorCode)
            }
        }
    }

    private fun coordinator() = GeofenceEventCoordinator(applicationContext)

    private fun readEvent(): NativeArrivalEvent? {
        val eventId = inputData.getString(KEY_EVENT_ID)?.takeIf { it.isNotBlank() } ?: return null
        val visitId = inputData.getString(KEY_VISIT_ID)?.takeIf { it.isNotBlank() } ?: return null
        val eventType = inputData.getString(KEY_EVENT_TYPE)?.let {
            runCatching { NativeArrivalEventType.valueOf(it) }.getOrNull()
        } ?: return null
        val geofenceId = inputData.getString(KEY_GEOFENCE_ID)?.takeIf { it.isNotBlank() }
            ?: return null
        val occurredAt = inputData.getLong(KEY_OCCURRED_AT, -1L).takeIf { it > 0 } ?: return null
        return NativeArrivalEvent(eventId, visitId, eventType, geofenceId, occurredAt)
    }

    private fun failure(errorCode: String): Result = Result.failure(
        Data.Builder().putString(KEY_ERROR_CODE, errorCode).build(),
    )

    companion object {
        const val KEY_EVENT_ID = "event_id"
        const val KEY_VISIT_ID = "visit_id"
        const val KEY_EVENT_TYPE = "event_type"
        const val KEY_GEOFENCE_ID = "geofence_id"
        const val KEY_OCCURRED_AT = "occurred_at_epoch_ms"
        const val KEY_RESULT_STATUS = "result_status"
        const val KEY_ERROR_CODE = "error_code"
    }
}
