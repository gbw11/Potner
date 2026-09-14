package com.potner.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import com.it_nomads.fluttersecurestorage.FlutterSecureStoragePlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var foregroundPermissionResult: MethodChannel.Result? = null
    private lateinit var geofenceManager: HomeGeofenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensureCreated(applicationContext)
        geofenceManager = HomeGeofenceManager(applicationContext)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Avoid duplicate registration after Flutter's generated registrant is available.
        if (!flutterEngine.plugins.has(FlutterSecureStoragePlugin::class.java)) {
            flutterEngine.plugins.add(FlutterSecureStoragePlugin())
        }
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            ArrivalGeofenceContract.CHANNEL,
        ).setMethodCallHandler(::handleGeofenceMethod)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != FOREGROUND_LOCATION_REQUEST_CODE) {
            return
        }
        foregroundPermissionResult?.success(geofenceManager.permissionStatus())
        foregroundPermissionResult = null
    }

    override fun onDestroy() {
        foregroundPermissionResult?.error(
            "ACTIVITY_DESTROYED",
            "위치 권한 요청 화면이 종료되었습니다.",
            null,
        )
        foregroundPermissionResult = null
        backgroundExecutor.shutdown()
        super.onDestroy()
    }

    private fun handleGeofenceMethod(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getLocationPermissionStatus" -> result.success(geofenceManager.permissionStatus())
            "requestForegroundLocationPermission" -> requestForegroundPermission(result)
            "openBackgroundLocationSettings" -> openBackgroundLocationSettings(result)
            "getCurrentLocation" -> geofenceManager.currentLocationMap()
                .addOnSuccessListener(result::success)
                .addOnFailureListener { result.platformError(it) }

            "registerHomeGeofences" -> registerHomeGeofences(call, result)
            "removeHomeGeofences" -> runBlockingMethod(result) {
                geofenceManager.clearSessionBlocking(sendCancel = true, clearConfig = false)
                geofenceManager.status()
            }

            "getGeofenceStatus" -> runBlockingMethod(result, geofenceManager::status)
            "clearSession" -> runBlockingMethod(result) {
                geofenceManager.clearSessionBlocking(sendCancel = true, clearConfig = true)
                null
            }

            else -> result.notImplemented()
        }
    }

    private fun requestForegroundPermission(result: MethodChannel.Result) {
        if (geofenceManager.hasPreciseLocationPermission()) {
            result.success(geofenceManager.permissionStatus())
            return
        }
        if (foregroundPermissionResult != null) {
            result.error("REQUEST_IN_PROGRESS", "위치 권한 요청을 처리하고 있습니다.", null)
            return
        }
        foregroundPermissionResult = result
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            FOREGROUND_LOCATION_REQUEST_CODE,
        )
    }

    private fun openBackgroundLocationSettings(result: MethodChannel.Result) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
        result.success(null)
    }

    private fun registerHomeGeofences(call: MethodCall, result: MethodChannel.Result) {
        val latitude = call.numberArgument("latitude")?.toDouble()
        val longitude = call.numberArgument("longitude")?.toDouble()
        val approachRadius = call.numberArgument("approachRadius")?.toFloat()
        val cancelRadius = call.numberArgument("cancelRadius")?.toFloat()
        val apiBaseUrl = call.argument<String>("apiBaseUrl")
        if (latitude == null || longitude == null || approachRadius == null ||
            cancelRadius == null || apiBaseUrl.isNullOrBlank()
        ) {
            result.error("INVALID_ARGUMENT", "지오펜스 설정값이 올바르지 않습니다.", null)
            return
        }
        runCatching {
            geofenceManager.register(
                latitude,
                longitude,
                approachRadius,
                cancelRadius,
                apiBaseUrl,
            )
        }.onSuccess { task ->
            task.addOnSuccessListener(result::success)
                .addOnFailureListener { result.platformError(it) }
        }.onFailure { result.platformError(it) }
    }

    private fun runBlockingMethod(
        result: MethodChannel.Result,
        block: () -> Any?,
    ) {
        backgroundExecutor.execute {
            runCatching(block)
                .onSuccess { value -> runOnUiThread { result.success(value) } }
                .onFailure { error -> runOnUiThread { result.platformError(error) } }
        }
    }

    private fun MethodCall.numberArgument(name: String): Number? = argument<Number>(name)

    private fun MethodChannel.Result.platformError(error: Throwable) {
        val code = error.message
            ?.takeIf { it.matches(Regex("[A-Z0-9_]+")) }
            ?: "GEOFENCE_OPERATION_FAILED"
        this.error(code, "귀가 감지 설정을 처리하지 못했습니다.", null)
    }

    private companion object {
        const val FOREGROUND_LOCATION_REQUEST_CODE = 4102
    }
}
