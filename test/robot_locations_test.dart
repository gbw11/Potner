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

Future<FakeDeviceRepository> _pumpToLocations(WidgetTester tester) async {
  final deviceRepository = FakeDeviceRepository();
  deviceRepository.locations['robot-1'] = [
    RobotLocationInfo(
      locationId: 'loc-water',
      type: RobotLocationType.waterStation,
      stationCode: 'station-01',
      poseConfigured: false,
      waterLow: true,
      waterLowAt: DateTime.utc(2026, 7, 30, 9),
    ),
  ];
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

  final robotListScrollable = find.descendant(
    of: find.byKey(const Key('robot_list')),
    matching: find.byType(Scrollable),
  );
  await tester.scrollUntilVisible(
    find.byKey(const Key('robot_locations_robot-1')),
    180,
    scrollable: robotListScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('robot_locations_robot-1')));
  await tester.pumpAndSettle();
  return deviceRepository;
}

void main() {
  testWidgets(
    'robot locations shows four types with station state and water-low badge',
    (tester) async {
      await _pumpToLocations(tester);

      expect(find.text('스테이션·위치 설정'), findsOneWidget);
      // 급수뿐 아니라 촬영·송풍도 같은 자리에서 이루어지므로 라벨에 '급수' 를 넣지 않는다.
      expect(find.text('스테이션'), findsOneWidget);
      expect(find.text('대기 장소'), findsOneWidget);
      expect(find.text('햇빛 자리'), findsOneWidget);
      expect(find.text('마중 지점'), findsOneWidget);

      expect(find.byKey(const Key('water_low_badge')), findsOneWidget);
      expect(find.textContaining('station-01'), findsOneWidget);
      expect(find.text('좌표 입력 전이에요'), findsOneWidget);
      expect(find.byKey(const Key('register_location_home')), findsOneWidget);
    },
  );

  testWidgets('registers a home location without a station code', (
    tester,
  ) async {
    final deviceRepository = await _pumpToLocations(tester);

    await tester.ensureVisible(find.byKey(const Key('register_location_home')));
    await tester.tap(find.byKey(const Key('register_location_home')));
    await tester.pumpAndSettle();

    expect(deviceRepository.registerLocationCalls, hasLength(1));
    expect(
      deviceRepository.registerLocationCalls.single.type,
      RobotLocationType.home,
    );
    expect(deviceRepository.registerLocationCalls.single.stationCode, isNull);
    expect(find.byKey(const Key('edit_pose_home')), findsOneWidget);
  });

  testWidgets('saves a pose after validating the yaw range', (tester) async {
    final deviceRepository = await _pumpToLocations(tester);

    await tester.ensureVisible(find.byKey(const Key('edit_pose_waterStation')));
    await tester.tap(find.byKey(const Key('edit_pose_waterStation')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('pose_x')), '1.25');
    await tester.enterText(find.byKey(const Key('pose_y')), '-0.5');
    await tester.enterText(find.byKey(const Key('pose_yaw')), '9');
    await tester.tap(find.byKey(const Key('pose_submit')));
    await tester.pumpAndSettle();

    expect(find.text('yaw 는 -3.1416 ~ 3.1416 범위여야 해요.'), findsOneWidget);
    expect(deviceRepository.poseUpdates, isEmpty);

    await tester.enterText(find.byKey(const Key('pose_yaw')), '1.57');
    await tester.tap(find.byKey(const Key('pose_submit')));
    await tester.pumpAndSettle();

    expect(deviceRepository.poseUpdates, hasLength(1));
    final pose = deviceRepository.poseUpdates.single;
    expect(pose.type, RobotLocationType.waterStation);
    expect(pose.x, 1.25);
    expect(pose.y, -0.5);
    expect(pose.yaw, 1.57);
    expect(find.textContaining('좌표  x 1.25'), findsOneWidget);
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
