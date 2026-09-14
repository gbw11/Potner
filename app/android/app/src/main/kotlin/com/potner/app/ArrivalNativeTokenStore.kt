package com.potner.app

internal data class NativeAuthTokens(
    val accessToken: String,
    val refreshToken: String,
)

internal interface ArrivalNativeTokenStore {
    fun read(): NativeAuthTokens?

    fun write(tokens: NativeAuthTokens)
}

internal class FlutterSecureArrivalTokenStore(
    private val storage: ArrivalSecureStorage,
) : ArrivalNativeTokenStore {
    override fun read(): NativeAuthTokens? {
        val accessToken = storage.read(ACCESS_TOKEN_KEY)?.takeIf { it.isNotBlank() }
        val refreshToken = storage.read(REFRESH_TOKEN_KEY)?.takeIf { it.isNotBlank() }
        if (accessToken == null || refreshToken == null) {
            return null
        }
        return NativeAuthTokens(accessToken, refreshToken)
    }

    override fun write(tokens: NativeAuthTokens) {
        storage.write(ACCESS_TOKEN_KEY, tokens.accessToken)
        storage.write(REFRESH_TOKEN_KEY, tokens.refreshToken)
    }

    private companion object {
        const val ACCESS_TOKEN_KEY = "potner.access_token"
        const val REFRESH_TOKEN_KEY = "potner.refresh_token"
    }
}
