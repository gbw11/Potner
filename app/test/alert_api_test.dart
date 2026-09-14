import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/alert/data/alert_api.dart';
import 'package:potner_app/features/alert/domain/alert_models.dart';

/// 서버 알림 목록 응답 한 건을 흉내낸다. 지표별 문구가 실제 응답을 통과해 나오는지 본다.
Future<List<PlantAlert>> _fetch(List<Map<String, Object?>> alerts) {
  final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1/'));
  dio.interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) => handler.resolve(
        Response<Object?>(
          requestOptions: options,
          statusCode: 200,
          data: <String, Object?>{'alerts': alerts},
        ),
      ),
    ),
  );
  return AlertApi(dio).getAlerts();
}

Map<String, Object?> _alert(String metricType, String deviation) {
  return <String, Object?>{
    'alertId': 'alert-1',
    'plantId': 'plant-1',
    'plantName': '두두',
    'metricType': metricType,
    'deviation': deviation,
    // 물 부족·배수트레이는 불리언 보고라 측정값이 없다. 서버도 null 로 내려준다.
    'measuredValue': null,
    'occurredAt': '2026-08-03T07:28:27',
    'resolvedAt': null,
    'active': true,
    'read': false,
  };
}

void main() {
  test('스테이션 물 부족은 물을 보충하라고 말한다', () async {
    final alerts = await _fetch([_alert('STATION_WATER_LOW', 'LOW')]);

    expect(alerts.single.metric, AlertMetric.stationWaterLow);
    expect(
      alerts.single.message,
      '스테이션에 물이 부족해요. 두두의 급수를 위해 물을 보충해 주세요.',
    );
  });

  test('배수트레이는 비우라고 말한다', () async {
    final alerts = await _fetch([_alert('DRAINAGE_TRAY', 'HIGH')]);

    expect(alerts.single.metric, AlertMetric.drainageTray);
    expect(alerts.single.message, '두두의 배수트레이에 물이 찼어요. 넘치지 않도록 비워 주세요.');
  });

  test('모르는 지표가 와도 죽지 않고 뭉뚱그린 문구로 떨어진다', () async {
    // 서버가 지표를 늘렸는데 앱이 아직 모르는 상황이다. 앱이 죽는 것보다는 낫지만
    // 이 문구가 보이면 위 두 테스트처럼 지표를 추가해야 한다는 신호다.
    final alerts = await _fetch([_alert('SOMETHING_NEW', 'LOW')]);

    expect(alerts.single.metric, AlertMetric.unknown);
    expect(alerts.single.message, '두두의 환경을 확인해 주세요.');
  });

  test('측정값이 있는 지표는 문구에 수치를 넣는다', () async {
    final alerts = await _fetch([
      {..._alert('HUMIDITY', 'HIGH'), 'measuredValue': 89},
    ]);

    expect(alerts.single.message, '두두의 습도(89%)가 너무 높습니다.');
  });
}
