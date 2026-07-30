import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/alert/data/alert_repository_impl.dart';
import 'package:potner_app/features/bloom/data/bloom_repository_impl.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_alert_repository.dart';
import 'support/fake_bloom_repository.dart';
import 'support/fake_device_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

void main() {
  testWidgets('authenticated routes share the bottom navigation', (
    tester,
  ) async {
    final repository = _NavigationTestAuthRepository();
    await _pumpAuthenticatedApp(tester, repository);

    expect(find.byKey(const Key('home_page')), findsOneWidget);
    expect(find.byKey(const Key('bottom_nav_home')), findsOneWidget);
    expect(find.byKey(const Key('bottom_nav_growth')), findsOneWidget);
    expect(find.byKey(const Key('bottom_nav_alerts')), findsOneWidget);
    expect(find.byKey(const Key('bottom_nav_my')), findsOneWidget);

    await tester.tap(find.byKey(const Key('bottom_nav_growth')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('growth_hub_page')), findsOneWidget);

    await tester.tap(find.byKey(const Key('bottom_nav_alerts')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('alerts_page')), findsOneWidget);

    await tester.tap(find.byKey(const Key('bottom_nav_my')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('my_page')), findsOneWidget);
  });

  testWidgets('full menu exposes sections and connects existing actions', (
    tester,
  ) async {
    final repository = _NavigationTestAuthRepository();
    await _pumpAuthenticatedApp(tester, repository);

    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('menu_page')), findsOneWidget);
    expect(find.text('식물 관리'), findsOneWidget);
    expect(find.text('성장 기록'), findsOneWidget);

    final plantRegistration = find.byKey(
      const Key('menu_item_plant_registration'),
    );
    final deviceRegistration = find.byKey(
      const Key('menu_item_device_registration'),
    );
    final careSettings = find.byKey(const Key('menu_item_care_settings'));

    expect(plantRegistration, findsOneWidget);
    expect(deviceRegistration, findsOneWidget);
    expect(careSettings, findsOneWidget);
    expect(
      tester.getTopLeft(plantRegistration).dy,
      lessThan(tester.getTopLeft(deviceRegistration).dy),
    );
    expect(
      tester.getTopLeft(deviceRegistration).dy,
      lessThan(tester.getTopLeft(careSettings).dy),
    );

    await tester.tap(deviceRegistration);
    await tester.pumpAndSettle();
    expect(find.text('디바이스를 연결해 주세요'), findsOneWidget);

    await tester.tap(find.byKey(const Key('device_registration_back')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('device_management_back')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();

    final serviceAlertsTitle = find.text('서비스 알림');
    await tester.scrollUntilVisible(
      serviceAlertsTitle,
      120,
      scrollable: find.descendant(
        of: find.byKey(const Key('menu_scroll_view')),
        matching: find.byType(Scrollable),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('서비스 알림'), findsOneWidget);

    await tester.ensureVisible(
      find.byKey(const Key('menu_item_alert_history')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('menu_item_alert_history')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('alerts_page')), findsOneWidget);

    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    final menuScrollable = find.descendant(
      of: find.byKey(const Key('menu_scroll_view')),
      matching: find.byType(Scrollable),
    );
    await tester.scrollUntilVisible(
      find.byKey(const Key('menu_item_logout')),
      280,
      scrollable: menuScrollable,
    );
    await tester.pumpAndSettle();
    expect(find.text('계정 및 설정'), findsOneWidget);
    await tester.tap(find.byKey(const Key('menu_item_logout')));
    await tester.pumpAndSettle();

    expect(repository.logoutCalls, 1);
    expect(find.text('Welcome Back'), findsOneWidget);
  });
}

Future<void> _pumpAuthenticatedApp(
  WidgetTester tester,
  _NavigationTestAuthRepository repository,
) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(repository),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        deviceRepositoryProvider.overrideWithValue(FakeDeviceRepository()),
        alertRepositoryProvider.overrideWithValue(FakeAlertRepository()),
        bloomRepositoryProvider.overrideWithValue(FakeBloomRepository()),
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
}

class _NavigationTestAuthRepository implements AuthRepository {
  int logoutCalls = 0;

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
  Future<void> logout() async {
    logoutCalls += 1;
  }

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
