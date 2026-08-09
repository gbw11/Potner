import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/core/notifications/push_notification_message.dart';
import 'package:potner_app/core/notifications/push_notification_route.dart';

PushNotificationMessage _message(Map<String, String> data) {
  return PushNotificationMessage(data: data);
}

void main() {
  test('서버가 보내는 경로들을 그대로 연다', () {
    // 이상·물 부족은 /alerts, 개화는 /growth/photos, 생장 단계는 /growth,
    // 분갈이는 /repotting 이다.
    for (final route in ['/alerts', '/growth', '/growth/photos', '/repotting']) {
      expect(resolvePushRoute(_message({'route': route})), route);
    }
  });

  test('개화 알림은 꽃이 핀 날짜로 포토 로그를 연다', () {
    // 꽃이 폈다는 소식을 누른 사용자가 찾는 것은 그날 사진이지 전체 목록이 아니다.
    expect(
      resolvePushRoute(
        _message({'route': '/growth/photos', 'bloomDate': '2026-08-10'}),
      ),
      '/growth/photos?date=2026-08-10',
    );
  });

  test('날짜가 형식에 어긋나면 날짜 없이 포토 로그를 연다', () {
    // 알림이 아무 데도 가지 못하는 것보다 전체 목록이라도 열리는 편이 낫다.
    // '2026-13-40' 과 '2026-02-30' 은 DateTime.parse 가 거절하지 않고 다음 달·해로 굴린다.
    // 그대로 두면 엉뚱한 날 사진이 열린다.
    for (final bad in [
      '',
      '  ',
      '2026/08/10',
      '2026-8-1',
      '내일',
      '2026-13-40',
      '2026-02-30',
    ]) {
      expect(
        resolvePushRoute(
          _message({'route': '/growth/photos', 'bloomDate': bad}),
        ),
        '/growth/photos',
      );
    }
  });

  test('날짜는 개화 알림에만 붙인다', () {
    // 다른 알림이 실수로 bloomDate 를 보내도 경로가 달라지면 안 된다.
    expect(
      resolvePushRoute(_message({'route': '/growth', 'bloomDate': '2026-08-10'})),
      '/growth',
    );
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
