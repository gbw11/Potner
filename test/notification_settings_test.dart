import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/user/data/user_repository_impl.dart';

import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';
import 'support/fake_user_repository.dart';

Future<FakeUserRepository> _pumpToNotificationSettings(
  WidgetTester tester,
) async {
  final userRepository = FakeUserRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        userRepositoryProvider.overrideWithValue(userRepository),
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

  await tester.tap(find.byKey(const Key('bottom_nav_my')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('my_notification_settings')));
  await tester.pumpAndSettle();
  return userRepository;
}

void main() {
  testWidgets('notification settings shows the loaded values', (tester) async {
    await _pumpToNotificationSettings(tester);

    expect(find.text('전체 알림'), findsOneWidget);
    expect(find.text('세부 알림 설정'), findsOneWidget);
    expect(find.text('푸시 알림'), findsOneWidget);
    expect(find.text('식물 케어 알림'), findsOneWidget);
    expect(find.text('이벤트 및 공지사항'), findsOneWidget);

    final marketingSwitch = tester.widget<Switch>(
      find.byKey(const Key('toggle_marketing')),
    );
    expect(marketingSwitch.value, isFalse);
  });

  testWidgets('toggling sends only the changed field', (tester) async {
    final userRepository = await _pumpToNotificationSettings(tester);

    await tester.tap(find.byKey(const Key('toggle_marketing')));
    await tester.pumpAndSettle();

    expect(userRepository.settingUpdates, hasLength(1));
    expect(userRepository.settingUpdates.single['marketingEnabled'], isTrue);
    expect(userRepository.settingUpdates.single['allEnabled'], isNull);
    expect(userRepository.settingUpdates.single['pushEnabled'], isNull);

    final marketingSwitch = tester.widget<Switch>(
      find.byKey(const Key('toggle_marketing')),
    );
    expect(marketingSwitch.value, isTrue);
  });

  testWidgets('turning the master switch off dims the detail tiles', (
    tester,
  ) async {
    final userRepository = await _pumpToNotificationSettings(tester);

    await tester.tap(find.byKey(const Key('toggle_all')));
    await tester.pumpAndSettle();

    expect(userRepository.settingUpdates.single['allEnabled'], isFalse);
    expect(
      find.text('전체 알림이 꺼져 있으면 세부 설정과 무관하게 알림이 오지 않아요.'),
      findsOneWidget,
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
