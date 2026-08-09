import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/core/api/api_problem.dart';
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

/// 회차는 첫 명령의 응답만으로 끝나지 않는다. 이동 → 작업 → 복귀가 이어지는 동안 앱이 어디까지
/// 갔는지 말할 수 있어야 시연 자리에서 "지금 뭐 하는 중인가" 에 답할 수 있으므로, 체인을 잇는
/// 판단을 테스트로 고정한다.
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

Future<void> _startCareRun(WidgetTester tester, CareRunPurpose purpose) async {
  final button = find.byKey(Key('care_run_${purpose.wireName}'));
  await tester.ensureVisible(button);
  await tester.pumpAndSettle();
  await tester.tap(button);
  await tester.pump();
}

String _statusOf(WidgetTester tester) {
  return tester.widget<Text>(find.byKey(const Key('care_run_status'))).data!;
}

/// 회차의 첫 명령이다. 발행 응답과 requestId·발행 시각이 같아야 폴링이 같은 회차로 집는다.
DeviceCommandRecord _navigateToStation({
  DeviceCommandStatus status = DeviceCommandStatus.ok,
}) {
  return DeviceCommandRecord(
    requestId: 'care-run-1',
    type: DeviceCommandType.navigate,
    status: status,
    initiator: CommandInitiator.auto,
    destination: RobotLocationType.waterStation,
    issuedAt: DateTime(2026, 8, 9, 10),
  );
}

DeviceCommandRecord _step({
  required String requestId,
  required DeviceCommandType type,
  required int minute,
  DeviceCommandStatus status = DeviceCommandStatus.ok,
  RobotLocationType? destination,
  double? dispensedMl,
  int? runSeconds,
  String? errorMessage,
}) {
  return DeviceCommandRecord(
    requestId: requestId,
    type: type,
    status: status,
    initiator: CommandInitiator.auto,
    destination: destination,
    dispensedMl: dispensedMl,
    runSeconds: runSeconds,
    errorMessage: errorMessage,
    issuedAt: DateTime(2026, 8, 9, 10, minute),
  );
}

void main() {
  testWidgets('급수 회차는 복귀까지 이어진 뒤에 끝난다', (tester) async {
    final repository = await _pumpToPanel(tester);
    // 이력은 최신순으로 오므로 일부러 뒤섞어 둔다. 회차는 발행 순으로 읽혀야 한다.
    repository.commands.addAll([
      _step(
        requestId: 'care-run-return',
        type: DeviceCommandType.navigate,
        minute: 3,
        destination: RobotLocationType.home,
      ),
      _step(
        requestId: 'care-run-fan',
        type: DeviceCommandType.fan,
        minute: 2,
        runSeconds: 12,
      ),
      _step(
        requestId: 'care-run-water',
        type: DeviceCommandType.water,
        minute: 1,
        dispensedMl: 348.5,
      ),
      _navigateToStation(),
    ]);

    await _startCareRun(tester, CareRunPurpose.watering);
    // 발행 응답은 첫 명령까지만 말해 준다. 나머지 단계는 폴링이 가져온다.
    expect(_statusOf(tester), contains('스테이션(으)로 이동'));

    // 폴링 간격이 2초다.
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(repository.careRunCalls, hasLength(1));
    expect(repository.careRunCalls.single.purpose, CareRunPurpose.watering);
    expect(repository.careRunCalls.single.plantId, 'plant-rose');
    // 앱은 회차 종류만 고른다. 명령을 직접 발행하면 순서가 두 벌로 갈린다.
    expect(repository.issueCalls, isEmpty);

    expect(_statusOf(tester), contains('급수 회차를 마쳤어요'));
    expect(_statusOf(tester), contains('348.5ml'));
    // 네 단계가 모두 이력에 보여야 어디까지 갔는지 눈으로 확인된다.
    expect(find.textContaining('물 주기 · 348.5ml'), findsOneWidget);
    expect(find.textContaining('송풍 · 12초'), findsOneWidget);
    expect(find.textContaining('대기 장소로 복귀'), findsWidgets);
  });

  testWidgets('건너뛴 급수는 실패가 아니라 회차를 계속 이어간다', (tester) async {
    // 라즈베리의 과급수 가드가 막으면 SKIPPED 가 온다. 실패로 보면 회차가 급수 단계에서
    // 끝난 것처럼 보이지만, 실제로는 서버가 송풍·복귀까지 잇는다.
    final repository = await _pumpToPanel(tester);
    repository.commands.addAll([
      _navigateToStation(),
      _step(
        requestId: 'care-run-water',
        type: DeviceCommandType.water,
        minute: 1,
        status: DeviceCommandStatus.skipped,
        dispensedMl: 0,
      ),
      _step(
        requestId: 'care-run-fan',
        type: DeviceCommandType.fan,
        minute: 2,
        runSeconds: 10,
      ),
      _step(
        requestId: 'care-run-return',
        type: DeviceCommandType.navigate,
        minute: 3,
        destination: RobotLocationType.home,
      ),
    ]);

    await _startCareRun(tester, CareRunPurpose.watering);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(_statusOf(tester), contains('물은 주지 않았어요'));
    // "0ml 나갔다" 로 말하면 펌프가 고장 난 것처럼 읽힌다.
    expect(_statusOf(tester), isNot(contains('0ml')));
    expect(find.textContaining('물 주기 · 건너뜀'), findsOneWidget);
    expect(find.textContaining('송풍 · 10초'), findsOneWidget);
  });

  testWidgets('복귀 전까지는 회차를 끝내지 않는다', (tester) async {
    final repository = await _pumpToPanel(tester);
    // 급수까지만 끝나고 송풍·복귀는 아직 발행되지 않은 상태다.
    repository.commands.addAll([
      _navigateToStation(),
      _step(
        requestId: 'care-run-water',
        type: DeviceCommandType.water,
        minute: 1,
        dispensedMl: 300,
      ),
    ]);

    await _startCareRun(tester, CareRunPurpose.watering);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();

    expect(_statusOf(tester), contains('다음 단계를 잇는 중'));
    // 회차가 도는 동안 두 번째 회차를 시작하면 로봇이 두 지시를 받는다.
    expect(
      tester
          .widget<ButtonStyleButton>(
            find.byKey(const Key('care_run_DRYING')),
          )
          .onPressed,
      isNull,
    );

    // 진행 중인 폴링을 남긴 채 테스트를 끝내면 다음 프레임에서 타이머가 살아 있다.
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('중간 단계가 실패하면 회차를 거기서 끝낸다', (tester) async {
    final repository = await _pumpToPanel(tester);
    repository.commands.addAll([
      _navigateToStation(),
      _step(
        requestId: 'care-run-water',
        type: DeviceCommandType.water,
        minute: 1,
        status: DeviceCommandStatus.error,
        errorMessage: '물탱크가 비었습니다.',
      ),
    ]);

    await _startCareRun(tester, CareRunPurpose.watering);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(_statusOf(tester), '물탱크가 비었습니다.');
    // 실패로 끝났으면 다시 시작할 수 있어야 한다.
    expect(
      tester
          .widget<ButtonStyleButton>(
            find.byKey(const Key('care_run_WATERING')),
          )
          .onPressed,
      isNotNull,
    );
  });

  testWidgets('한 단계가 타임아웃이면 로봇이 남아 있을 수 있다고 알린다', (tester) async {
    final repository = await _pumpToPanel(tester);
    repository.commands.add(
      _navigateToStation(status: DeviceCommandStatus.timedOut),
    );

    await _startCareRun(tester, CareRunPurpose.capture);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(_statusOf(tester), contains('회신이 없어'));
    expect(_statusOf(tester), contains('스테이션에 남아'));
  });

  testWidgets('재배치는 이어지는 단계가 없어 첫 이동으로 끝난다', (tester) async {
    final repository = await _pumpToPanel(tester);
    repository.commands.add(
      DeviceCommandRecord(
        requestId: 'care-run-1',
        type: DeviceCommandType.navigate,
        status: DeviceCommandStatus.ok,
        initiator: CommandInitiator.auto,
        destination: RobotLocationType.sunlight,
        issuedAt: DateTime(2026, 8, 9, 10),
      ),
    );

    await _startCareRun(tester, CareRunPurpose.relocation);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(repository.careRunCalls.single.purpose, CareRunPurpose.relocation);
    expect(_statusOf(tester), contains('햇빛 자리로 옮겼어요'));
  });

  testWidgets('시작을 거절당하면 서버가 준 이유를 그대로 보여준다', (tester) async {
    final repository = await _pumpToPanel(tester);
    // 시작 거절은 이유가 여러 갈래다(꺼진 케어·작업 중인 로봇·급수량 미설정). 앱에서 갈래를
    // 다시 만들면 서버가 이유를 늘릴 때마다 문구가 어긋난다.
    repository.careRunError = const ApiException(
      kind: ApiExceptionKind.problem,
      statusCode: 409,
      problem: ApiProblem(
        code: 'Care run robot busy',
        detail: '로봇이 다른 작업을 수행 중입니다. 끝난 뒤에 다시 시도해 주세요.',
      ),
    );

    await _startCareRun(tester, CareRunPurpose.drying);
    await tester.pumpAndSettle();

    expect(_statusOf(tester), '로봇이 다른 작업을 수행 중입니다. 끝난 뒤에 다시 시도해 주세요.');
    // 거절은 발행 전이므로 기다릴 대상이 없다.
    expect(repository.getCommandsCalls, isZero);
  });

  testWidgets('배정된 식물이 없으면 회차 카드를 내린다', (tester) async {
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

    expect(find.byKey(const Key('care_run_panel')), findsNothing);
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
