import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/command/data/command_repository_impl.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_command_repository.dart';
import 'support/fake_device_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

/// 목적지 이동은 지도 좌표가 입력되어 있어야 발행된다. 이 화면은 좌표를 보지 않는 유일한 통로라
/// 지도를 만들기 전에 바퀴를 시험할 방법이 여기뿐이므로, 발행이 끊기지 않는 것을 테스트로 고정한다.
Future<FakeDeviceCommandRepository> _pumpToDrivePage(
  WidgetTester tester, {
  List<ManagedRobot>? robots,
}) async {
  final commandRepository = FakeDeviceCommandRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        deviceRepositoryProvider.overrideWithValue(
          FakeDeviceRepository(robots: robots),
        ),
        deviceCommandRepositoryProvider.overrideWithValue(commandRepository),
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
    find.byKey(const Key('menu_item_robot_drive')),
    280,
    scrollable: menuScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('menu_item_robot_drive')));
  await tester.pumpAndSettle();
  return commandRepository;
}

void main() {
  testWidgets('방향 버튼을 누르면 그 방향을 배정된 식물로 보낸다', (tester) async {
    final repository = await _pumpToDrivePage(tester);

    expect(find.byKey(const Key('robot_drive_page')), findsOneWidget);

    await tester.tap(find.byKey(const Key('robot_drive_FORWARD')));
    await tester.pumpAndSettle();

    expect(repository.driveCalls, hasLength(1));
    expect(repository.driveCalls.single.direction, DriveDirection.forward);
    expect(repository.driveCalls.single.plantId, 'plant-rose');

    final status = tester.widget<Text>(
      find.byKey(const Key('robot_drive_status')),
    );
    // 회신 계약이 없다. 발행까지만 말해 주고 스스로 멈춘다는 것을 밝힌다.
    expect(status.data, contains('전진 명령을 보냈어요'));
    expect(status.data, contains('스스로 멈춰요'));
  });

  testWidgets('좌우 버튼은 제자리 회전을 보낸다', (tester) async {
    final repository = await _pumpToDrivePage(tester);

    await tester.tap(find.byKey(const Key('robot_drive_LEFT')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('robot_drive_RIGHT')));
    await tester.pumpAndSettle();

    expect(
      repository.driveCalls.map((call) => call.direction),
      [DriveDirection.left, DriveDirection.right],
    );
  });

  /// 움직이는 로봇을 세울 수 없는 버튼은 안전장치가 아니다. 앞선 요청이 날아가는 중에도 정지가
  /// 눌려야 하므로 버튼을 잠그지 않는다.
  testWidgets('앞선 요청이 끝나기 전에도 정지를 보낼 수 있다', (tester) async {
    final repository = await _pumpToDrivePage(tester);
    repository.driveDelay = const Duration(milliseconds: 400);

    await tester.tap(find.byKey(const Key('robot_drive_FORWARD')));
    await tester.pump(const Duration(milliseconds: 100));
    expect(
      tester
          .widget<Text>(find.byKey(const Key('robot_drive_status')))
          .data,
      contains('보내는 중'),
    );

    await tester.tap(find.byKey(const Key('robot_drive_STOP')));
    await tester.pumpAndSettle();

    expect(
      repository.driveCalls.map((call) => call.direction),
      [DriveDirection.forward, DriveDirection.stop],
    );
    // 늦게 온 전진 응답이 정지 문구를 덮어쓰면 사용자가 무엇이 마지막인지 알 수 없다.
    expect(
      tester
          .widget<Text>(find.byKey(const Key('robot_drive_status')))
          .data,
      contains('정지 명령을 보냈어요'),
    );
  });

  testWidgets('발행이 실패하면 이유를 보여 준다', (tester) async {
    final repository = await _pumpToDrivePage(tester);
    repository.driveError = Exception('boom');

    await tester.tap(find.byKey(const Key('robot_drive_FORWARD')));
    await tester.pumpAndSettle();

    expect(
      tester
          .widget<Text>(find.byKey(const Key('robot_drive_status')))
          .data,
      contains('오류가 발생했습니다'),
    );
  });

  testWidgets('배정된 식물이 없으면 방향 버튼을 내린다', (tester) async {
    // 명령은 식물 단위다. 배정이 없으면 서버가 보낼 대상을 정할 수 없다.
    final repository = await _pumpToDrivePage(
      tester,
      robots: const [
        ManagedRobot(
          robotId: 'robot-unassigned',
          deviceUid: 'potner-unassigned',
          name: '미배정 로봇',
          connectionStatus: DeviceConnectionStatus.offline,
          devices: [],
        ),
      ],
    );

    expect(find.byKey(const Key('robot_drive_unavailable')), findsOneWidget);
    expect(find.byKey(const Key('robot_drive_FORWARD')), findsNothing);
    expect(repository.driveCalls, isEmpty);
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
