import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/command/data/command_repository_impl.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_command_repository.dart';
import 'support/fake_device_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<FakePlantRepository> _login(WidgetTester tester) async {
  final plantRepository = FakePlantRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(plantRepository),
        deviceRepositoryProvider.overrideWithValue(FakeDeviceRepository()),
        deviceCommandRepositoryProvider.overrideWithValue(
          FakeDeviceCommandRepository(),
        ),
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
  return plantRepository;
}

Future<void> _tapMenuItem(WidgetTester tester, String key) async {
  await tester.tap(find.byKey(const Key('open_menu_button')));
  await tester.pumpAndSettle();
  final menuScrollable = find.descendant(
    of: find.byKey(const Key('menu_scroll_view')),
    matching: find.byType(Scrollable),
  );
  await tester.scrollUntilVisible(
    find.byKey(Key(key)),
    240,
    scrollable: menuScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('메뉴에서 분갈이 방법을 열면 시기·준비물·순서가 보인다', (tester) async {
    await _login(tester);
    await _tapMenuItem(tester, 'menu_item_repotting');

    expect(find.text('분갈이 방법'), findsWidgets);
    expect(find.byKey(const Key('repotting_when')), findsOneWidget);
    expect(find.byKey(const Key('repotting_supplies')), findsOneWidget);
    // 분갈이 뒤 2주가 실제로 식물이 죽는 구간이라 순서만 알려 주고 끝내면 안 된다.
    expect(find.byKey(const Key('repotting_after')), findsOneWidget);
  });

  testWidgets('수동 패널의 분갈이 알림 버튼이 배정된 식물로 발송을 요청한다', (tester) async {
    final plantRepository = await _login(tester);
    await _tapMenuItem(tester, 'menu_item_devices');

    // 위에 자동 케어 회차 카드가 있어 버튼이 첫 화면에 들어오지 않는다. 목록이 지연 빌드라
    // 보이지 않는 동안에는 위젯 자체가 없으므로 ensureVisible 로는 집을 수 없다.
    final button = find.byKey(const Key('device_repotting_reminder_debug'));
    await tester.scrollUntilVisible(
      button,
      200,
      scrollable: find.descendant(
        of: find.byKey(const Key('robot_list')),
        matching: find.byType(Scrollable),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pumpAndSettle();

    // 장치 명령이 아니라 서버가 폰으로 보내는 푸시라 회신을 기다리지 않는다.
    expect(plantRepository.repottingReminderPlantIds, hasLength(1));
    expect(find.text('분갈이 알림을 보냈어요. 폰의 알림을 확인해 주세요.'), findsOneWidget);
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
