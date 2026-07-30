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

Future<FakeSensorRepository> _pumpToEnvironment(WidgetTester tester) async {
  final sensorRepository = FakeSensorRepository();
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
