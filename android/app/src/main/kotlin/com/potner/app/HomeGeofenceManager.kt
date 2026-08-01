package com.potner.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class HomeGeofenceManager(context: Context) {
    private val appContext = context.applicationContext
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(appContext)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val storage = ArrivalSecureStorage.get(appContext)
    private val configStore = GeofenceConfigStore(storage)
    private val stateStore = GeofenceStateStore(storage)
    private val workScheduler = ArrivalWorkScheduler(appContext)

    fun permissionStatus(): Map<String, Any?> = mapOf(
        "permissionStatus" to when {
            !hasForegroundLocationPermission() -> "DENIED"
            hasBackgroundLocationPermission() -> "BACKGROUND"
            else -> "FOREGROUND_ONLY"
        },
        "preciseLocationGranted" to hasPreciseLocationPermission(),
        "locationServicesEnabled" to isLocationServicesEnabled(),
        "googlePlayServicesAvailable" to hasGooglePlayServices(),
    )

    fun status(): Map<String, Any?> {
        val config = configStore.read()
        val state = GeofenceEventCoordinator(appContext).currentState()
        return permissionStatus() + mapOf(
            "enabled" to (config?.enabled == true),
            "registrationStatus" to state.registrationStatus.name,
            "zoneState" to state.zoneState.name,
            "deliveryState" to state.deliveryState.name,
            "lastTransitionAtEpochMs" to state.lastTransitionAtEpochMs,
            "lastErrorCode" to state.lastErrorCode,
            "homeLatitude" to config?.latitude,
            "homeLongitude" to config?.longitude,
            "approachRadius" to (
                config?.approachRadiusMeters
                    ?: ArrivalGeofenceContract.DEFAULT_APPROACH_RADIUS_METERS
                ).toDouble(),
            "cancelRadius" to (
                config?.cancelRadiusMeters
                    ?: ArrivalGeofenceContract.DEFAULT_CANCEL_RADIUS_METERS
                ).toDouble(),
        )
    }

    @SuppressLint("MissingPermission")
    fun currentLocation(): Task<Location> {
        if (!hasPreciseLocationPermission()) {
            return Tasks.forException(SecurityException("PRECISE_LOCATION_REQUIRED"))
        }
        if (!isLocationServicesEnabled()) {
            return Tasks.forException(IllegalStateException("LOCATION_SERVICES_DISABLED"))
        }
        val source = CancellationTokenSource()
        val task = fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            source.token,
        )
        return Tasks.withTimeout(
            task,
            ArrivalGeofenceContract.LOCATION_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        ).continueWith { completed ->
            val location = completed.result
                ?: throw IllegalStateException("CURRENT_LOCATION_UNAVAILABLE")
            location
        }
    }

    fun currentLocationMap(): Task<Map<String, Any?>> = currentLocation().continueWith { task ->
        val location = task.result
        mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy.toDouble(),
            "timestampEpochMs" to location.time,
        )
    }

    @SuppressLint("MissingPermission")
    fun register(
        latitude: Double,
        longitude: Double,
        approachRadiusMeters: Float,
        cancelRadiusMeters: Float,
        apiBaseUrl: String,
    ): Task<Map<String, Any?>> {
        validateRegistration(
            latitude,
            longitude,
            approachRadiusMeters,
            cancelRadiusMeters,
        )
        requireReadyForRegistration()

        val config = HomeGeofenceConfig(
            enabled = true,
            latitude = latitude,
            longitude = longitude,
            approachRadiusMeters = approachRadiusMeters,
            cancelRadiusMeters = cancelRadiusMeters,
            apiBaseUrl = apiBaseUrl,
        )
        stateStore.save(
            stateStore.read().copy(
                zoneState = GeofenceZoneState.INITIALIZING,
                registrationStatus = GeofenceRegistrationStatus.REGISTERING,
                lastErrorCode = null,
            ),
        )

        return currentLocation().continueWithTask { locationTask ->
            val location = if (locationTask.isSuccessful) locationTask.result else null
            val initialZone = initialZone(config, location)
            configStore.save(config)
            stateStore.save(
                HomeGeofenceRuntimeState(
                    zoneState = initialZone,
                    registrationStatus = GeofenceRegistrationStatus.REGISTERING,
                ),
            )
            geofencingClient.removeGeofences(geofenceRequestIds()).continueWithTask {
                geofencingClient.addGeofences(
                    geofencingRequest(config),
                    geofencePendingIntent(),
                )
            }
        }.continueWith { registrationTask ->
            if (!registrationTask.isSuccessful) {
                configStore.setEnabled(false)
                stateStore.save(
                    HomeGeofenceRuntimeState(
                        zoneState = GeofenceZoneState.DISABLED,
                        registrationStatus = GeofenceRegistrationStatus.ERROR,
                        lastErrorCode = "GEOFENCE_REGISTRATION_FAILED",
                    ),
                )
                throw registrationTask.exception
                    ?: IllegalStateException("GEOFENCE_REGISTRATION_FAILED")
            }
            val current = stateStore.read().copy(
                registrationStatus = GeofenceRegistrationStatus.REGISTERED,
                lastErrorCode = null,
            )
            stateStore.save(current)
            status()
        }
    }

    @SuppressLint("MissingPermission")
    fun registerStoredAfterBoot(): Task<Void> {
        val config = configStore.read()
            ?: return Tasks.forException(IllegalStateException("GEOFENCE_CONFIG_MISSING"))
        if (!config.enabled) {
            return Tasks.forResult(null)
        }
        return try {
            requireReadyForRegistration()
            stateStore.save(
                stateStore.read().copy(
                    registrationStatus = GeofenceRegistrationStatus.REGISTERING,
                    lastErrorCode = null,
                ),
            )
            currentLocation().continueWithTask { locationTask ->
                val current = stateStore.read()
                if (current.deliveryState == ArrivalDeliveryState.NONE) {
                    val location = if (locationTask.isSuccessful) locationTask.result else null
                    stateStore.save(
                        current.copy(zoneState = initialZone(config, location)),
                    )
                }
                geofencingClient.addGeofences(
                    geofencingRequest(config),
                    geofencePendingIntent(),
                )
            }.continueWith { task ->
                if (!task.isSuccessful) {
                    stateStore.save(
                        stateStore.read().copy(
                            registrationStatus = GeofenceRegistrationStatus.ERROR,
                            lastErrorCode = "BOOT_REREGISTRATION_FAILED",
                        ),
                    )
                    throw task.exception ?: IllegalStateException("BOOT_REREGISTRATION_FAILED")
                }
                stateStore.save(
                    stateStore.read().copy(
                        registrationStatus = GeofenceRegistrationStatus.REGISTERED,
                        lastErrorCode = null,
                    ),
                )
                null
            }
        } catch (error: Exception) {
            recordError("BOOT_REREGISTRATION_FAILED")
            Tasks.forException(error)
        }
    }

    fun clearSessionBlocking(sendCancel: Boolean, clearConfig: Boolean) {
        val now = System.currentTimeMillis()
        val config = configStore.read()
        val state = GeofenceEventCoordinator(appContext).currentState(now)

        // Ignore any transition delivered while the old geofences are being removed.
        config?.let { configStore.save(it.copy(enabled = false)) }
        workScheduler.cancelAll()

        if (sendCancel &&
            config != null &&
            state.deliveryState == ArrivalDeliveryState.APPROACH_ACTIVE &&
            state.visitId != null &&
            state.cancelableUntilEpochMs?.let { now <= it } == true
        ) {
            val cancelEvent = NativeArrivalEvent(
                eventId = UUID.randomUUID().toString(),
                visitId = state.visitId,
                eventType = NativeArrivalEventType.CANCEL,
                geofenceId = ArrivalGeofenceContract.CANCEL_REQUEST_ID,
                occurredAtEpochMs = now,
            )
            ArrivalApiClient(FlutterSecureArrivalTokenStore(storage)).sendEvent(config, cancelEvent)
        }

        runCatching {
            Tasks.await(
                geofencingClient.removeGeofences(geofenceRequestIds()),
                10,
                TimeUnit.SECONDS,
            )
        }
        if (clearConfig) {
            configStore.clear()
        }
        stateStore.save(
            HomeGeofenceRuntimeState(
                zoneState = GeofenceZoneState.DISABLED,
                registrationStatus = GeofenceRegistrationStatus.NOT_REGISTERED,
            ),
        )
    }

    fun recordError(errorCode: String) {
        stateStore.save(stateStore.read().copy(lastErrorCode = errorCode))
    }

    fun hasForegroundLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED || hasPreciseLocationPermission()

    fun hasPreciseLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requireReadyForRegistration() {
        require(hasForegroundLocationPermission()) { "FOREGROUND_LOCATION_REQUIRED" }
        require(hasPreciseLocationPermission()) { "PRECISE_LOCATION_REQUIRED" }
        require(hasBackgroundLocationPermission()) { "BACKGROUND_LOCATION_REQUIRED" }
        require(isLocationServicesEnabled()) { "LOCATION_SERVICES_DISABLED" }
        require(hasGooglePlayServices()) { "GOOGLE_PLAY_SERVICES_UNAVAILABLE" }
    }

    private fun validateRegistration(
        latitude: Double,
        longitude: Double,
        approachRadiusMeters: Float,
        cancelRadiusMeters: Float,
    ) {
        require(latitude in -90.0..90.0) { "INVALID_HOME_LATITUDE" }
        require(longitude in -180.0..180.0) { "INVALID_HOME_LONGITUDE" }
        require(approachRadiusMeters >= ArrivalGeofenceContract.MIN_APPROACH_RADIUS_METERS) {
            "APPROACH_RADIUS_TOO_SMALL"
        }
        require(
            cancelRadiusMeters >=
                approachRadiusMeters + ArrivalGeofenceContract.MIN_RADIUS_GAP_METERS,
        ) { "GEOFENCE_RADIUS_GAP_TOO_SMALL" }
    }

    private fun initialZone(config: HomeGeofenceConfig, location: Location?): GeofenceZoneState {
        if (location == null ||
            System.currentTimeMillis() - location.time >
            ArrivalGeofenceContract.FRESH_LOCATION_MAX_AGE_MS ||
            !location.hasAccuracy() ||
            location.accuracy > ArrivalGeofenceContract.ACCEPTABLE_ACCURACY_METERS
        ) {
            return GeofenceZoneState.INSIDE_LOCKED
        }
        val distance = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            config.latitude,
            config.longitude,
            distance,
        )
        return if (distance[0] >= config.cancelRadiusMeters) {
            GeofenceZoneState.OUTSIDE_ARMED
        } else {
            GeofenceZoneState.INSIDE_LOCKED
        }
    }

    private fun isLocationServicesEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun hasGooglePlayServices(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS

    private fun geofencingRequest(config: HomeGeofenceConfig): GeofencingRequest {
        val approach = Geofence.Builder()
            .setRequestId(ArrivalGeofenceContract.APPROACH_REQUEST_ID)
            .setCircularRegion(
                config.latitude,
                config.longitude,
                config.approachRadiusMeters,
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(
                ArrivalGeofenceContract.NOTIFICATION_RESPONSIVENESS_MS,
            )
            .build()
        val cancel = Geofence.Builder()
            .setRequestId(ArrivalGeofenceContract.CANCEL_REQUEST_ID)
            .setCircularRegion(
                config.latitude,
                config.longitude,
                config.cancelRadiusMeters,
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(
                ArrivalGeofenceContract.NOTIFICATION_RESPONSIVENESS_MS,
            )
            .build()
        return GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(listOf(approach, cancel))
            .build()
    }

    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(appContext, GeofenceBroadcastReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    private fun geofenceRequestIds() = listOf(
        ArrivalGeofenceContract.APPROACH_REQUEST_ID,
        ArrivalGeofenceContract.CANCEL_REQUEST_ID,
    )
}
