import 'package:potner_app/core/notifications/push_notification_message.dart';

/// 알림을 눌렀을 때 열 화면을 정한다.
///
/// 서버가 푸시 `data` 의 `route` 에 앱 경로를 넣어 보낸다. 지금 보내는 값은 이상·개화·물
/// 부족은 `/alerts`, 생장 단계는 `/growth`, 분갈이는 `/repotting` 이다.
///
/// 서버 값을 그대로 믿고 `go` 하지 않는다. 서버가 새 알림 종류를 먼저 배포하면 구버전 앱이
/// 모르는 경로를 받게 되고, 그러면 라우터의 errorBuilder 가 "화면을 찾을 수 없습니다" 를
/// 띄운다. 알림을 눌렀는데 오류 화면이 나오는 것보다 알림 목록이 열리는 편이 낫다 — 어떤
/// 알림이든 목록에는 남아 있으므로 사용자가 거기서 내용을 볼 수 있다.
const _knownRoutes = <String>{'/alerts', '/growth', '/repotting'};

/// 모르는 경로일 때 대신 열 화면이다.
const _fallbackRoute = '/alerts';

/// 열어야 할 경로다. 열 것이 없으면 null 이다.
String? resolvePushRoute(PushNotificationMessage message) {
  final route = message.data['route']?.trim();
  if (route == null || route.isEmpty) {
    // route 가 아예 없는 알림이다. 어디로 보내야 할지 서버가 말하지 않았으므로 아무것도
    // 하지 않는다 - 앱을 열어 준 것만으로 이미 사용자의 의도는 이뤄졌다.
    return null;
  }
  if (_knownRoutes.contains(route)) {
    return route;
  }
  return _fallbackRoute;
}
