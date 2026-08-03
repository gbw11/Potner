package com.potner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 푸시 알림이 쓸 채널을 만든다.
 *
 * Android 8 부터 헤드업 팝업과 소리는 **채널 중요도가 HIGH 일 때만** 난다. 채널을 만들지
 * 않으면 FCM 이 자동 생성한 기본 채널(IMPORTANCE_DEFAULT)로 들어가서, 알림이 도착해도
 * 상태바에만 조용히 쌓이고 잠금 화면에 뜨지 않는다.
 *
 * 채널 id 는 세 곳이 같은 문자열이어야 한다.
 *   1) 여기
 *   2) AndroidManifest 의 default_notification_channel_id (앱이 죽어 있을 때 FCM 이 쓰는 값)
 *   3) 서버 FirebasePushSender 의 AndroidNotification.channelId
 *
 * MainActivity.onCreate 에서 부른다. FCM 토큰 등록이 로그인 이후에만 일어나므로 알림이
 * 도착하는 시점에는 앱이 최소 한 번 실행돼 채널이 이미 있다.
 *
 * 주의: 이미 만들어진 채널의 중요도는 createNotificationChannel 로 바꿀 수 없다. Android 가
 * 사용자 설정을 덮어쓰지 않으려고 무시한다. 중요도를 조정해야 하면 채널 id 를 새로 파거나
 * 앱을 지우고 다시 깔아야 한다.
 */
object NotificationChannels {

    const val ALERTS = "potner_alerts"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ALERTS,
            "식물 알림",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "물 부족, 센서 이상, 개화, 분갈이 시기를 알립니다."
            enableVibration(true)
            // 잠금 화면에서 문구까지 보여 준다. 무엇을 해야 하는지가 알림의 내용이라
            // 제목만 보여 주면 열어 봐야 한다.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}
