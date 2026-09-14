import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_device_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<FakeDeviceRepository> _pumpToDeviceRegistration(
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
  await tester.tap(find.byKey(const Key('menu_item_device_registration')));
  await tester.pumpAndSettle();
  return deviceRepository;
}

void main() {
  testWidgets(
    'device registration registers robot, both boards, assignment and shows the token once',
    (tester) async {
      final deviceRepository = await _pumpToDeviceRegistration(tester);

      expect(find.text('디바이스를 연결해 주세요'), findsOneWidget);

      await tester.enterText(
        find.byKey(const Key('device_potner_code')),
        'potner-robot-01',
      );
      await tester.enterText(
        find.byKey(const Key('device_robot_name')),
        '포트니',
      );
      await tester.enterText(
        find.byKey(const Key('device_raspberry_code')),
        'raspberry-01',
      );
      await tester.enterText(
        find.byKey(const Key('device_jetson_code')),
        'jetson-01',
      );

      await tester.ensureVisible(find.byKey(const Key('device_plant')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('device_plant')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('로지 (장미)').last);
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.byKey(const Key('device_submit')));
      await tester.tap(find.byKey(const Key('device_submit')));
      await tester.pumpAndSettle();

      expect(deviceRepository.registerRobotCalls, hasLength(1));
      expect(
        deviceRepository.registerRobotCalls.single.deviceUid,
        'potner-robot-01',
      );
      expect(deviceRepository.registerRobotCalls.single.name, '포트니');
      // MQTT 는 보드 코드로만 오간다. 라즈베리와 젯슨이 각자 등록되어야 각 보드의 신호가
      // 서버에 귀속된다 — 로봇 코드로는 브로커에 아무것도 오지 않는다.
      expect(deviceRepository.registerIotDeviceCalls, hasLength(2));
      expect(
        deviceRepository.registerIotDeviceCalls
            .map((call) => (call.deviceUid, call.deviceType))
            .toList(),
        [
          ('raspberry-01', IotDeviceType.raspberryPi),
          ('jetson-01', IotDeviceType.jetsonOrin),
        ],
      );
      expect(deviceRepository.assignCalls, hasLength(1));
      expect(deviceRepository.assignCalls.single.plantId, 'plant-rose');

      expect(find.byKey(const Key('device_upload_token')), findsOneWidget);
      expect(find.text('token-plain-text-1234'), findsOneWidget);

      await tester.tap(find.byKey(const Key('device_token_done')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('menu_page')), findsOneWidget);
    },
  );

  testWidgets('device registration validates the codes before submit', (
    tester,
  ) async {
    final deviceRepository = await _pumpToDeviceRegistration(tester);

    await tester.enterText(
      find.byKey(const Key('device_potner_code')),
      '한글코드',
    );
    await tester.ensureVisible(find.byKey(const Key('device_submit')));
    await tester.tap(find.byKey(const Key('device_submit')));
    await tester.pumpAndSettle();

    expect(find.text('코드는 영문, 숫자, 하이픈, 밑줄만 사용할 수 있습니다.'), findsOneWidget);
    expect(find.text('Potner 이름을 입력해 주세요.'), findsOneWidget);
    expect(deviceRepository.registerRobotCalls, isEmpty);
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
