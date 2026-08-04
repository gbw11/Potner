import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/core/notifications/push_notification_message.dart';
import 'package:potner_app/core/notifications/push_notification_route.dart';

PushNotificationMessage _message(Map<String, String> data) {
  return PushNotificationMessage(data: data);
}

void main() {
  test('서버가 보내는 경로들을 그대로 연다', () {
    // 이상·개화·물 부족은 /alerts, 생장 단계는 /growth, 분갈이는 /repotting 이다.
    for (final route in ['/alerts', '/growth', '/repotting']) {
      expect(resolvePushRoute(_message({'route': route})), route);
    }
  });

  test('모르는 경로는 알림 목록으로 돌린다', () {
    // 서버가 새 알림 종류를 먼저 배포한 상황이다. 오류 화면을 띄우는 것보다 목록을 여는 게
    // 낫다 - 어떤 알림이든 목록에는 남아 있다.
    expect(resolvePushRoute(_message({'route': '/something-new'})), '/alerts');
  });

  test('경로가 없으면 아무것도 열지 않는다', () {
    expect(resolvePushRoute(_message({'type': 'SENSOR_ALERT'})), isNull);
    expect(resolvePushRoute(_message({'route': '  '})), isNull);
  });
}
