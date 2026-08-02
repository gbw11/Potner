package com.potner.app

internal class GeofenceConfigStore(
    private val storage: ArrivalSecureStorage,
) {
    @Synchronized
    fun read(): HomeGeofenceConfig? {
        val latitude = storage.read(KEY_LATITUDE)?.toDoubleOrNull() ?: return null
        val longitude = storage.read(KEY_LONGITUDE)?.toDoubleOrNull() ?: return null
        val approachRadius = storage.read(KEY_APPROACH_RADIUS)?.toFloatOrNull() ?: return null
        val cancelRadius = storage.read(KEY_CANCEL_RADIUS)?.toFloatOrNull() ?: return null
        val apiBaseUrl = storage.read(KEY_API_BASE_URL)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.API_BASE_URL
        return HomeGeofenceConfig(
            enabled = storage.read(KEY_ENABLED).toBoolean(),
            latitude = latitude,
            longitude = longitude,
            approachRadiusMeters = approachRadius,
            cancelRadiusMeters = cancelRadius,
            apiBaseUrl = normalizeApiBaseUrl(apiBaseUrl),
        )
    }

    @Synchronized
    fun save(config: HomeGeofenceConfig) {
        storage.write(KEY_ENABLED, config.enabled.toString())
        storage.write(KEY_LATITUDE, config.latitude.toString())
        storage.write(KEY_LONGITUDE, config.longitude.toString())
        storage.write(KEY_APPROACH_RADIUS, config.approachRadiusMeters.toString())
        storage.write(KEY_CANCEL_RADIUS, config.cancelRadiusMeters.toString())
        storage.write(KEY_API_BASE_URL, normalizeApiBaseUrl(config.apiBaseUrl))
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        storage.write(KEY_ENABLED, enabled.toString())
    }

    @Synchronized
    fun clear() {
        storage.remove(ALL_KEYS)
    }

    private fun normalizeApiBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        require(trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            "API base URL must use HTTP or HTTPS."
        }
        return trimmed
    }

    private companion object {
        const val KEY_ENABLED = "potner.arrival.enabled"
        const val KEY_LATITUDE = "potner.arrival.home_latitude"
        const val KEY_LONGITUDE = "potner.arrival.home_longitude"
        const val KEY_APPROACH_RADIUS = "potner.arrival.approach_radius"
        const val KEY_CANCEL_RADIUS = "potner.arrival.cancel_radius"
        const val KEY_API_BASE_URL = "potner.arrival.api_base_url"
        val ALL_KEYS = listOf(
            KEY_ENABLED,
            KEY_LATITUDE,
            KEY_LONGITUDE,
            KEY_APPROACH_RADIUS,
            KEY_CANCEL_RADIUS,
            KEY_API_BASE_URL,
        )
    }
}
