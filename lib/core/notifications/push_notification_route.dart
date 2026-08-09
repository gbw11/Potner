import 'package:potner_app/core/notifications/push_notification_message.dart';

/// 알림을 눌렀을 때 열 화면을 정한다.
///
/// 서버가 푸시 `data` 의 `route` 에 앱 경로를 넣어 보낸다. 지금 보내는 값은 이상·물 부족은
/// `/alerts`, 개화는 `/growth/photos`, 생장 단계는 `/growth`, 분갈이는 `/repotting` 이다.
///
/// 서버 값을 그대로 믿고 `go` 하지 않는다. 서버가 새 알림 종류를 먼저 배포하면 구버전 앱이
/// 모르는 경로를 받게 되고, 그러면 라우터의 errorBuilder 가 "화면을 찾을 수 없습니다" 를
/// 띄운다. 알림을 눌렀는데 오류 화면이 나오는 것보다 알림 목록이 열리는 편이 낫다 — 어떤
/// 알림이든 목록에는 남아 있으므로 사용자가 거기서 내용을 볼 수 있다.
const _knownRoutes = <String>{
  '/alerts',
  '/growth',
  '/growth/photos',
  '/repotting',
};

/// 모르는 경로일 때 대신 열 화면이다.
const _fallbackRoute = '/alerts';

/// 포토 로그를 특정 날짜로 여는 질의 항목이다. 화면 쪽과 이름을 맞춘다.
const photoLogDateQueryParameter = 'date';

/// 열어야 할 경로다. 열 것이 없으면 null 이다.
String? resolvePushRoute(PushNotificationMessage message) {
  final route = message.data['route']?.trim();
  if (route == null || route.isEmpty) {
    // route 가 아예 없는 알림이다. 어디로 보내야 할지 서버가 말하지 않았으므로 아무것도
    // 하지 않는다 - 앱을 열어 준 것만으로 이미 사용자의 의도는 이뤄졌다.
    return null;
  }
  if (!_knownRoutes.contains(route)) {
    return _fallbackRoute;
  }
  if (route == '/growth/photos') {
    // 개화 알림은 꽃이 핀 날짜를 함께 보낸다. 꽃이 폈다는 소식을 누른 사용자가 보고 싶은
    // 것은 그날 사진이지 전체 목록이 아니다.
    //
    // 날짜는 경로가 아니라 질의로 붙인다. 위 화이트리스트가 완전 일치 검사라 경로에 섞으면
    // 모르는 경로가 되어 알림 목록으로 되돌아간다.
    final date = _isoDateOrNull(message.data['bloomDate']);
    if (date != null) {
      return '$route?$photoLogDateQueryParameter=$date';
    }
  }
  return route;
}

/// `yyyy-MM-dd` 형태이고 실제로 존재하는 날짜일 때만 돌려준다.
///
/// 형식이 어긋나면 날짜 없이 포토 로그를 연다. 서버가 형식을 바꾸거나 값이 비었다고 해서
/// 알림이 아무 데도 가지 못하게 만들 이유는 없다.
String? _isoDateOrNull(String? raw) {
  final value = raw?.trim();
  // 길이를 먼저 본다. tryParse 는 '2026-8-1' 처럼 자리수가 다른 값도 받아들인다.
  if (value == null || value.length != 10) {
    return null;
  }
  final parsed = DateTime.tryParse(value);
  if (parsed == null) {
    return null;
  }
  // tryParse 는 범위를 벗어난 값을 거절하지 않고 넘긴다 — '2026-13-40' 은 2027-02-09 가
  // 된다. 그대로 두면 엉뚱한 날 사진이 열리므로, 다시 적었을 때 원래 값과 같은지 본다.
  // 어긋나면 날짜 없이 전체 목록을 여는 편이 낫다.
  return _formatIsoDate(parsed) == value ? value : null;
}

String _formatIsoDate(DateTime date) {
  final year = date.year.toString().padLeft(4, '0');
  final month = date.month.toString().padLeft(2, '0');
  final day = date.day.toString().padLeft(2, '0');
  return '$year-$month-$day';
}
