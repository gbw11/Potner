# Potner FCM 연동

## 현재 적용 범위

- Firebase 프로젝트: `potner-b276a`
- Android application ID: `com.potner.app`
- Android 13 이상 알림 권한 요청
- 로그인 및 세션 복원 후 기기 등록
- FCM 토큰 및 Firebase 설치 ID(FID) 갱신 감지
- 로그아웃 전 기기 등록 해제
- 전경 메시지 앱 내 표시
- 백그라운드 알림의 Android 시스템 알림 표시

현재 시연 대상은 Google Play 서비스가 포함된 Android Emulator다. iOS는
`GoogleService-Info.plist`, APNs 인증 키, Push Notifications 및 Background
Modes capability를 별도로 설정한 후 검증해야 한다.

## 인증 생명주기

1. 로그인 또는 저장된 세션 복원에 성공한다.
2. 사용자에게 알림 권한을 요청한다.
3. Firebase 설치 ID와 현재 FCM 토큰을 가져온다.
4. 서버에 기기 정보를 등록한다.
5. 토큰 또는 설치 ID가 변경되면 서버 등록 정보를 갱신한다.
6. 로그아웃 시 서버의 기기 등록을 해제한 후 로컬 인증 정보를 정리한다.

기기 등록 실패는 로그인 자체를 실패시키지 않는다. 다음 로그인이나 세션
복원 시 다시 등록을 시도한다. FCM 토큰과 설치 ID는 로그에 기록하지 않는다.

## 백엔드 API 계약

API prefix는 기존과 동일한 `/api/v1`이며 두 API 모두 Access Token 인증이
필요하다.

### 기기 등록 및 갱신

```http
PUT /api/v1/users/me/fcm-tokens/{installationId}
Authorization: Bearer {accessToken}
Content-Type: application/json
```

```json
{
  "token": "FCM registration token",
  "platform": "ANDROID"
}
```

권장 성공 응답은 `204 No Content`다. 같은 사용자와 설치 ID로 반복 요청해도
안전하도록 upsert로 처리하고, 토큰과 마지막 확인 시각을 갱신한다. 한 사용자가
여러 기기를 사용할 수 있도록 설치 ID별 레코드를 유지한다.

### 기기 등록 해제

```http
DELETE /api/v1/users/me/fcm-tokens/{installationId}
Authorization: Bearer {accessToken}
```

권장 성공 응답은 `204 No Content`다. 이미 삭제된 설치 ID에 대한 반복 요청도
성공으로 처리하는 것을 권장한다.

## 서버 발송 payload

백그라운드에서 Android 시스템 알림을 표시하려면 서버 메시지에
`notification.title`과 `notification.body`를 포함한다. 화면 이동에 필요한
값은 `data`에 함께 전달한다.

```json
{
  "notification": {
    "title": "Potner",
    "body": "식물의 상태를 확인해 주세요."
  },
  "data": {
    "type": "PLANT_ALERT",
    "alertId": "alert UUID",
    "plantId": "plant UUID",
    "route": "/alerts"
  }
}
```

`data`만 보내면 현재 Android 구현에서는 백그라운드에 보이는 시스템 알림이
자동으로 만들어지지 않는다. 서버는 사용자 알림 설정의
`allEnabled`, `pushEnabled` 및 카테고리별 설정을 확인한 뒤 발송해야 한다.

## 검증 결과

Pixel 8 Android Emulator에서 다음을 확인했다.

- Firebase 초기화 성공
- FCM 토큰 및 Firebase 설치 ID 발급 성공
- Android 알림 권한 허용
- Potner가 백그라운드인 상태에서 Firebase Console 테스트 메시지 수신
- Android 시스템 알림에 제목과 본문 표시
- 토큰 갱신 및 로그아웃 등록 해제 단위 테스트 통과

Firebase 서비스 계정 키는 앱이나 저장소에 추가하지 않는다. 서버 발송 기능에서
사용하며 백엔드의 비밀 환경변수 또는 Secret Manager로 관리한다.
