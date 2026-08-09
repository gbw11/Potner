import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/sensor/data/sensor_repository_impl.dart';
import 'package:potner_app/features/sensor/domain/sensor_models.dart';
import 'package:potner_app/features/sensor/domain/sensor_repository.dart';

import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';
import 'support/fake_sensor_repository.dart';

Future<FakeSensorRepository> _pumpToEnvironment(
  WidgetTester tester, {
  List<CurrentSensor>? currentSensors,
}) async {
  final sensorRepository = FakeSensorRepository();
  if (currentSensors != null) {
    sensorRepository.currentSensorsOverride = currentSensors;
  }
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        sensorRepositoryProvider.overrideWithValue(sensorRepository),
      ],
      child: const PotnerApp(),
    ),
  );
  await tester.pumpAndSettle();

  await tester.enterText(
    find.byKey(const Key('login_email')),
    'member@example.com',
  );
  await tester.enterText(find.byKey(const Key('login_password')), 'password1');
  await tester.tap(find.byKey(const Key('login_submit')));
  await tester.pumpAndSettle();

  await tester.tap(find.byKey(const Key('open_menu_button')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('menu_item_plants')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('plant_card_plant-rose')));
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.byKey(const Key('profile_environment')));
  await tester.tap(find.byKey(const Key('profile_environment')));
  await tester.pumpAndSettle();
  return sensorRepository;
}

void main() {
  testWidgets(
    'environment dashboard shows current sensors, chart and daily light',
    (tester) async {
      await _pumpToEnvironment(tester);

      expect(find.text('환경 정보'), findsOneWidget);
      expect(find.text('현재 환경'), findsOneWidget);
      expect(find.byKey(const Key('current_soilMoisture')), findsOneWidget);
      expect(find.text('56'), findsOneWidget);
      expect(find.text('높음'), findsOneWidget);
      expect(find.text('측정 전'), findsOneWidget);

      expect(find.byKey(const Key('sensor_history_chart')), findsOneWidget);

      expect(find.text('오늘의 광량 진행률'), findsOneWidget);
      expect(find.text('64%'), findsOneWidget);
      expect(find.text('광량 적정'), findsOneWidget);
      expect(find.text('일조 부족'), findsOneWidget);
      expect(find.text('광량 표본 부족'), findsOneWidget);
    },
  );

  testWidgets('오늘의 광량에 실제 수치와 표본 상태를 함께 보여준다', (tester) async {
    // 진행률만으로는 목표를 정할 근거가 안 된다. 목표 150,000 일 때 '1%' 는
    // 750~2,250 사이 어디든이다. 표본·커버리지는 '표본 부족' 의 이유를 읽는 데 쓴다.
    await _pumpToEnvironment(tester);

    expect(find.text('64%'), findsOneWidget);
    expect(find.text('96,000 / 150,000 lux·h'), findsOneWidget);
    expect(find.text('표본 1240건 · 커버리지 86%'), findsOneWidget);
  });

  testWidgets('값이 끊기면 오래됨 대신 몇 시간 전인지 보여준다', (tester) async {
    // '오래됨' 은 5분 전인지 이틀째 끊긴 건지를 구분해 주지 못했다. 홈은 이미 경과
    // 시간으로 쓰고 있어 두 화면의 문구가 어긋나 있었다.
    await _pumpToEnvironment(
      tester,
      currentSensors: FakeSensorRepository.staleSensors(),
    );

    expect(find.text('오래됨'), findsNothing);
    expect(find.text('3시간 전'), findsWidgets);
  });

  testWidgets('그래프에 단위가 붙은 눈금과 범례가 함께 나온다', (tester) async {
    // 축도 단위도 없이 선만 있던 화면은 그 값이 무엇의 몇인지 읽을 수 없었다.
    await _pumpToEnvironment(tester);

    expect(find.byKey(const Key('sensor_history_chart')), findsOneWidget);
    // 무엇이 평균이고 무엇이 기준 범위인지 글자로 밝힌다.
    expect(find.text('평균'), findsOneWidget);
    expect(find.text('그 시간의 최저~최고'), findsOneWidget);
    expect(find.text('적정 범위'), findsOneWidget);
  });

  testWidgets('switching sensor kind and range refetches the history', (
    tester,
  ) async {
    final sensorRepository = await _pumpToEnvironment(tester);

    expect(sensorRepository.historyCalls, hasLength(1));
    expect(sensorRepository.historyCalls.first.kind, SensorKind.soilMoisture);
    expect(
      sensorRepository.historyCalls.first.interval,
      SensorHistoryInterval.hour,
    );

    await tester.ensureVisible(
      find.byKey(const Key('history_kind_temperature')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('history_kind_temperature')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('history_range_week')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('history_range_week')));
    await tester.pumpAndSettle();

    expect(sensorRepository.historyCalls, hasLength(3));
    expect(
      sensorRepository.historyCalls.last.kind,
      SensorKind.temperature,
    );
    expect(
      sensorRepository.historyCalls.last.interval,
      SensorHistoryInterval.day,
    );
  });
}

class _FakeAuthRepository implements AuthRepository {
  @override
  Future<void> clearSession() async {}

  @override
  Future<User> login({required String email, required String password}) async {
    return const User(
      userId: 'user-id',
      email: 'member@example.com',
      nickname: '포트너',
    );
  }

  @override
  Future<void> logout() async {}

  @override
  Future<User?> restoreSession() async => null;

  @override
  Future<User> signup({
    required String email,
    required String password,
    required String nickname,
  }) async {
    return User(userId: 'new-user', email: email, nickname: nickname);
  }
}
