import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/arrival/data/arrival_repository_impl.dart';
import 'package:potner_app/features/arrival/domain/arrival_models.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_device_repository.dart';
import 'support/fake_arrival_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<({FakeDeviceRepository device, FakeArrivalRepository arrival})>
_pumpToDeviceManagement(WidgetTester tester) async {
  final deviceRepository = FakeDeviceRepository();
  final arrivalRepository = FakeArrivalRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        deviceRepositoryProvider.overrideWithValue(deviceRepository),
        arrivalRepositoryProvider.overrideWithValue(arrivalRepository),
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
  return (device: deviceRepository, arrival: arrivalRepository);
}

Future<void> _scrollRobotListTo(WidgetTester tester, Finder target) async {
  final robotListScrollable = find.descendant(
    of: find.byKey(const Key('robot_list')),
    matching: find.byType(Scrollable),
  );
  await tester.scrollUntilVisible(target, 180, scrollable: robotListScrollable);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('device management lists robots with battery and robot state', (
    tester,
  ) async {
    await _pumpToDeviceManagement(tester);
    await _scrollRobotListTo(
      tester,
      find.byKey(const Key('robot_card_robot-1')),
    );

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
    final repositories = await _pumpToDeviceManagement(tester);

    await _scrollRobotListTo(
      tester,
      find.byKey(const Key('reissue_token_robot-1')),
    );
    await tester.tap(find.byKey(const Key('reissue_token_robot-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('reissue_confirm')));
    await tester.pumpAndSettle();

    expect(repositories.device.reissuedRobotIds, ['robot-1']);
    expect(find.text('reissued-token-5678'), findsOneWidget);

    await tester.tap(find.byKey(const Key('reissued_token_done')));
    await tester.pumpAndSettle();
  });

  testWidgets('device management unassigns a plant after confirmation', (
    tester,
  ) async {
    final repositories = await _pumpToDeviceManagement(tester);

    await _scrollRobotListTo(tester, find.byKey(const Key('unassign_robot-1')));
    await tester.tap(find.byKey(const Key('unassign_robot-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('unassign_confirm')));
    await tester.pumpAndSettle();

    expect(repositories.device.unassignedPlantIds, ['plant-rose']);
    expect(find.byKey(const Key('unassign_robot-1')), findsNothing);
  });

  testWidgets('device management deletes a robot after confirmation', (
    tester,
  ) async {
    final repositories = await _pumpToDeviceManagement(tester);

    await _scrollRobotListTo(
      tester,
      find.byKey(const Key('delete_robot_robot-1')),
    );
    await tester.tap(find.byKey(const Key('delete_robot_robot-1')));
    await tester.pumpAndSettle();
    // 서버가 소프트 해제라 "삭제" 가 아니라 "해제" 로 말한다.
    expect(find.text('기기 해제'), findsOneWidget);
    expect(find.textContaining('같은 코드로 다시 등록할 수 있어요'), findsOneWidget);

    await tester.tap(find.byKey(const Key('delete_robot_confirm')));
    await tester.pumpAndSettle();

    expect(repositories.device.deletedRobotIds, ['robot-1']);
    expect(find.byKey(const Key('robot_card_robot-1')), findsNothing);
  });

  testWidgets('arrival debug buttons share visitId and show Jetson OK', (
    tester,
  ) async {
    final repositories = await _pumpToDeviceManagement(tester);

    await tester.ensureVisible(find.byKey(const Key('arrival_debug_start')));
    await tester.tap(find.byKey(const Key('arrival_debug_start')));
    await tester.pumpAndSettle();

    expect(repositories.arrival.calls, hasLength(1));
    expect(
      repositories.arrival.calls.first.eventType,
      ArrivalEventType.approach,
    );
    expect(find.text('Jetson이 GREETING 위치 도착을 확인했어요.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('arrival_debug_cancel')));
    await tester.pumpAndSettle();

    expect(repositories.arrival.calls, hasLength(2));
    expect(repositories.arrival.calls.last.eventType, ArrivalEventType.cancel);
    expect(
      repositories.arrival.calls.last.visitId,
      repositories.arrival.calls.first.visitId,
    );
    expect(
      find.text('Jetson이 HOME 위치 복귀를 확인했어요.'),
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
