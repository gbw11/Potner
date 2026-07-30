import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/alert/data/alert_repository_impl.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/bloom/data/bloom_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_alert_repository.dart';
import 'support/fake_bloom_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<(FakeAlertRepository, FakeBloomRepository)> _pumpToAlerts(
  WidgetTester tester,
) async {
  final alertRepository = FakeAlertRepository();
  final bloomRepository = FakeBloomRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        alertRepositoryProvider.overrideWithValue(alertRepository),
        bloomRepositoryProvider.overrideWithValue(bloomRepository),
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

  await tester.tap(find.byKey(const Key('bottom_nav_alerts')));
  await tester.pumpAndSettle();
  return (alertRepository, bloomRepository);
}

void main() {
  testWidgets('alerts page composes messages per metric and groups sections', (
    tester,
  ) async {
    await _pumpToAlerts(tester);

    expect(find.text('이상 알림'), findsOneWidget);
    expect(find.text('개화 알림'), findsOneWidget);
    expect(find.text('알림 이력'), findsOneWidget);

    expect(find.text('로지의 습도(89%)가 너무 높습니다.'), findsOneWidget);
    expect(find.text('로지의 온도(10℃)가 너무 낮습니다.'), findsOneWidget);
    expect(find.text('로지가 꽃을 피웠어요!'), findsOneWidget);
  });

  testWidgets('tapping an unread alert marks it read', (tester) async {
    final (alertRepository, _) = await _pumpToAlerts(tester);

    await tester.tap(find.byKey(const Key('alert_card_alert-active')));
    await tester.pumpAndSettle();

    expect(alertRepository.readAlertIds, ['alert-active']);
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
