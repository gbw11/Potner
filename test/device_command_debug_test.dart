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

/// 자동 케어는 센서값과 지도 좌표가 갖춰져야 트리거된다. 시연 자리에서 그 조건을 만들 수 없을 때
/// 손으로 명령을 보내는 경로라, 발행과 회신 반영이 끊기지 않는 것을 테스트로 고정한다.
Future<FakeDeviceCommandRepository> _pumpToPanel(
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
    find.byKey(const Key('menu_item_devices')),
    280,
    scrollable: menuScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('menu_item_devices')));
  await tester.pumpAndSettle();
  return commandRepository;
}

/// 수동 명령 패널까지 스크롤한다.
///
/// 위에 자동 케어 회차 카드가 있어 패널이 첫 화면에 다 들어오지 않는다. 목록이 지연 빌드라
/// 보이지 않는 동안에는 위젯 자체가 없으므로 [WidgetTester.ensureVisible] 로는 집을 수 없다.
Future<void> _scrollTo(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(
    find.byKey(key),
    200,
    scrollable: find.descendant(
      of: find.byKey(const Key('robot_list')),
      matching: find.byType(Scrollable),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('급수 명령을 보내고 장치 회신을 반영한다', (tester) async {
    final repository = await _pumpToPanel(tester);
    repository.nextRequestId = 'request-water';
    // 회신이 이미 도착한 상태로 두면 첫 폴링에서 종결된다.
    repository.commands.add(
      const DeviceCommandRecord(
        requestId: 'request-water',
        type: DeviceCommandType.water,
        status: DeviceCommandStatus.ok,
        initiator: CommandInitiator.user,
        dispensedMl: 348.5,
      ),
    );

    await _scrollTo(tester, const Key('device_command_debug_WATER'));
    expect(find.byKey(const Key('device_command_debug_panel')), findsOneWidget);

    await tester.tap(find.byKey(const Key('device_command_debug_WATER')));
    await tester.pump();
    // 발행 응답은 issued 까지만 말해 준다. 결과는 폴링이 가져온다.
    expect(find.textContaining('장치 회신을 기다립니다'), findsOneWidget);

    // 폴링 간격이 2초다.
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(repository.issueCalls, hasLength(1));
    expect(repository.issueCalls.single.type, DeviceCommandType.water);
    // 급수·촬영·송풍은 목적지를 받지 않는다. 넣으면 서버가 400 이다.
    expect(repository.issueCalls.single.destination, isNull);
    expect(find.textContaining('348.5ml'), findsOneWidget);
  });

  testWidgets('이동 명령은 고른 목적지를 함께 보낸다', (tester) async {
    final repository = await _pumpToPanel(tester);
    repository.nextRequestId = 'request-navigate';
    repository.commands.add(
      const DeviceCommandRecord(
        requestId: 'request-navigate',
        type: DeviceCommandType.navigate,
        status: DeviceCommandStatus.ok,
        initiator: CommandInitiator.user,
        destination: RobotLocationType.waterStation,
      ),
    );

    await _scrollTo(tester, const Key('device_command_debug_NAVIGATE'));
    await tester.tap(find.byKey(const Key('device_command_debug_NAVIGATE')));
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(repository.issueCalls, hasLength(1));
    expect(repository.issueCalls.single.type, DeviceCommandType.navigate);
    expect(
      repository.issueCalls.single.destination,
      RobotLocationType.waterStation,
    );
    expect(find.textContaining('도착'), findsOneWidget);
  });

  testWidgets('회신이 TIMED_OUT 이면 원인을 볼 곳을 알려준다', (tester) async {
    final repository = await _pumpToPanel(tester);
    repository.nextRequestId = 'request-fan';
    repository.commands.add(
      const DeviceCommandRecord(
        requestId: 'request-fan',
        type: DeviceCommandType.fan,
        status: DeviceCommandStatus.timedOut,
        initiator: CommandInitiator.user,
      ),
    );

    await _scrollTo(tester, const Key('device_command_debug_FAN'));
    await tester.tap(find.byKey(const Key('device_command_debug_FAN')));
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(find.textContaining('회신이 없어'), findsOneWidget);
  });

  testWidgets('표정 버튼은 폴링 없이 발행하고 덮어쓰기를 알려 준다', (tester) async {
    final repository = await _pumpToPanel(tester);

    await _scrollTo(tester, const Key('device_expression_debug_SAD'));
    await tester.tap(find.byKey(const Key('device_expression_debug_SAD')));
    await tester.pumpAndSettle();

    expect(repository.expressionCalls, hasLength(1));
    expect(repository.expressionCalls.single.expression, PlantExpression.sad);
    // 표정에는 result 회신 계약이 없다. 기다릴 대상이 없으므로 이력 폴링을 하지 않는다.
    expect(repository.issueCalls, isEmpty);
    // 패널 상단의 정적 라벨에도 같은 문구가 있으므로 상태 줄만 본다.
    final status = tester.widget<Text>(
      find.byKey(const Key('device_command_debug_status')),
    );
    expect(status.data, contains('우울 표정을 보냈어요'));
    expect(status.data, contains('30초 뒤'));
  });

  testWidgets('배정된 식물이 없으면 명령 버튼을 내린다', (tester) async {
    // 명령은 식물 단위다. 배정이 없으면 서버가 보낼 대상을 정할 수 없다.
    await _pumpToPanel(
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

    expect(
      find.byKey(const Key('device_command_debug_unavailable')),
      findsOneWidget,
    );
    expect(find.byKey(const Key('device_command_debug_WATER')), findsNothing);
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
