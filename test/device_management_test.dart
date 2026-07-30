import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_device_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<FakeDeviceRepository> _pumpToDeviceManagement(
  WidgetTester tester,
) async {
  final deviceRepository = FakeDeviceRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        deviceRepositoryProvider.overrideWithValue(deviceRepository),
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
  final menuScrollable = find.descendant(
    of: find.byKey(const Key('menu_scroll_view')),
    matching: find.byType(Scrollable),
  );
  await tester.scrollUntilVisible(
    find.byKey(const Key('menu_item_devices')),
    280,
    scrollable: menuScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('menu_item_devices')));
  await tester.pumpAndSettle();
  return deviceRepository;
}

void main() {
  testWidgets('device management lists robots with battery and robot state', (
    tester,
  ) async {
    await _pumpToDeviceManagement(tester);

    expect(find.text('장치 관리'), findsWidgets);
    expect(find.text('포트니'), findsOneWidget);
    expect(find.text('85%'), findsOneWidget);
    expect(find.text('온라인'), findsWidgets);
    expect(find.text('케어 중 (급수·송풍)'), findsOneWidget);
    expect(find.text('새싹이'), findsOneWidget);
    expect(find.text('수집 전'), findsOneWidget);
    expect(find.textContaining('스테이션(라즈베리파이)'), findsOneWidget);
  });

  testWidgets('device management reissues the upload token', (tester) async {
    final deviceRepository = await _pumpToDeviceManagement(tester);

    await tester.ensureVisible(find.byKey(const Key('reissue_token_robot-1')));
    await tester.tap(find.byKey(const Key('reissue_token_robot-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('reissue_confirm')));
    await tester.pumpAndSettle();

    expect(deviceRepository.reissuedRobotIds, ['robot-1']);
    expect(find.text('reissued-token-5678'), findsOneWidget);

    await tester.tap(find.byKey(const Key('reissued_token_done')));
    await tester.pumpAndSettle();
  });

  testWidgets('device management unassigns a plant after confirmation', (
    tester,
  ) async {
    final deviceRepository = await _pumpToDeviceManagement(tester);

    await tester.ensureVisible(find.byKey(const Key('unassign_robot-1')));
    await tester.tap(find.byKey(const Key('unassign_robot-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('unassign_confirm')));
    await tester.pumpAndSettle();

    expect(deviceRepository.unassignedPlantIds, ['plant-rose']);
    expect(find.byKey(const Key('unassign_robot-1')), findsNothing);
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
