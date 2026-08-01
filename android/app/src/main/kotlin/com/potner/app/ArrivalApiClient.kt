package com.potner.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal sealed interface ArrivalApiResult {
    data class Success(val status: String) : ArrivalApiResult

    data class RetryableFailure(val errorCode: String) : ArrivalApiResult

    data class PermanentFailure(val errorCode: String) : ArrivalApiResult
}

internal class ArrivalApiClient(
    private val tokenStore: ArrivalNativeTokenStore,
) {
    fun sendEvent(config: HomeGeofenceConfig, event: NativeArrivalEvent): ArrivalApiResult {
        val tokens = tokenStore.read()
            ?: return ArrivalApiResult.PermanentFailure("AUTH_TOKEN_MISSING")
        return sendEvent(config, event, tokens, allowRefresh = true)
    }

    private fun sendEvent(
        config: HomeGeofenceConfig,
        event: NativeArrivalEvent,
        tokens: NativeAuthTokens,
        allowRefresh: Boolean,
    ): ArrivalApiResult {
        val payload = JSONObject()
            .put("eventId", event.eventId)
            .put("visitId", event.visitId)
            .put("eventType", event.eventType.name)
            .put("source", "ANDROID_GEOFENCE")
            .put("geofenceId", event.geofenceId)
            .put("occurredAt", isoTimestamp(event.occurredAtEpochMs))

        val response = executeJsonRequest(
            url = "${config.apiBaseUrl}/arrival/events",
            authorization = "Bearer ${tokens.accessToken}",
            payload = payload,
        )
        if (response.transportFailure) {
            return ArrivalApiResult.RetryableFailure("NETWORK_ERROR")
        }
        if (response.statusCode in 200..299) {
            val status = response.json?.optString("status")
                ?.takeIf { it.isNotBlank() }
                ?: "COMMAND_PUBLISHED"
            return ArrivalApiResult.Success(status)
        }

        val errorCode = response.json?.optString("code")
            ?.takeIf { it.isNotBlank() }
            ?: "HTTP_${response.statusCode}"
        if (response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED &&
            errorCode == "EXPIRED_ACCESS_TOKEN" &&
            allowRefresh
        ) {
            val refreshed = refresh(config, tokens.refreshToken)
            if (refreshed != null) {
                tokenStore.write(refreshed)
                return sendEvent(config, event, refreshed, allowRefresh = false)
            }
            return ArrivalApiResult.PermanentFailure("TOKEN_REFRESH_FAILED")
        }
        if (response.statusCode == 429 || response.statusCode >= 500) {
            return ArrivalApiResult.RetryableFailure(errorCode)
        }
        return ArrivalApiResult.PermanentFailure(errorCode)
    }

    private fun refresh(config: HomeGeofenceConfig, refreshToken: String): NativeAuthTokens? {
        val response = executeJsonRequest(
            url = "${config.apiBaseUrl}/auth/reissue",
            authorization = null,
            payload = JSONObject().put("refreshToken", refreshToken),
        )
        if (response.transportFailure || response.statusCode !in 200..299) {
            return null
        }
        val accessToken = response.json?.optString("accessToken")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val newRefreshToken = response.json.optString("refreshToken")
            .takeIf { it.isNotBlank() }
            ?: return null
        return NativeAuthTokens(accessToken, newRefreshToken)
    }

    private fun executeJsonRequest(
        url: String,
        authorization: String?,
        payload: JSONObject,
    ): HttpJsonResponse {
        var connection: HttpURLConnection? = null
        return try {
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                authorization?.let { setRequestProperty("Authorization", it) }
                setFixedLengthStreamingMode(bytes.size)
            }
            connection.outputStream.use { it.write(bytes) }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            HttpJsonResponse(
                statusCode = statusCode,
                json = body?.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                },
            )
        } catch (_: Exception) {
            HttpJsonResponse(statusCode = -1, json = null, transportFailure = true)
        } finally {
            connection?.disconnect()
        }
    }

    private fun isoTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))

    private data class HttpJsonResponse(
        val statusCode: Int,
        val json: JSONObject?,
        val transportFailure: Boolean = false,
    )
}
