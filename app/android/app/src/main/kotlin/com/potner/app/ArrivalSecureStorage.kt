package com.potner.app

import android.content.Context
import com.it_nomads.fluttersecurestorage.FlutterSecureStorage
import com.it_nomads.fluttersecurestorage.FlutterSecureStorageConfig
import com.it_nomads.fluttersecurestorage.SecurePreferencesCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * flutter_secure_storage가 사용하는 Android 저장소를 네이티브 백그라운드 코드에서도 읽는다.
 * 플러그인 구현 의존은 이 클래스 안에만 가둬 버전 변경 시 교체 지점을 한 곳으로 제한한다.
 */
internal class ArrivalSecureStorage private constructor(context: Context) {
    private val delegate = FlutterSecureStorage(context.applicationContext)

    init {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        delegate.initialize(
            FlutterSecureStorageConfig(emptyMap()),
            object : SecurePreferencesCallback<Void> {
                override fun onSuccess(result: Void?) {
                    latch.countDown()
                }

                override fun onError(error: Exception) {
                    failure.set(error)
                    latch.countDown()
                }
            },
        )
        check(latch.await(ArrivalGeofenceContract.LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Secure storage initialization timed out."
        }
        failure.get()?.let { throw IllegalStateException("Secure storage initialization failed.", it) }
    }

    @Synchronized
    fun read(key: String): String? = delegate.read(delegate.addPrefixToKey(key))

    @Synchronized
    fun write(key: String, value: String?) {
        val prefixedKey = delegate.addPrefixToKey(key)
        if (value == null) {
            delegate.delete(prefixedKey)
        } else {
            delegate.write(prefixedKey, value)
        }
    }

    @Synchronized
    fun remove(keys: Iterable<String>) {
        keys.forEach { delegate.delete(delegate.addPrefixToKey(it)) }
    }

    companion object {
        @Volatile
        private var instance: ArrivalSecureStorage? = null

        fun get(context: Context): ArrivalSecureStorage =
            instance ?: synchronized(this) {
                instance ?: ArrivalSecureStorage(context).also { instance = it }
            }
    }
}
