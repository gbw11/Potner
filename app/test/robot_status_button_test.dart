import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/device/domain/robot_status.dart';
import 'package:potner_app/features/device/presentation/controllers/robot_status_controller.dart';
import 'package:potner_app/features/home/presentation/widgets/robot_status_button.dart';

void main() {
  test('robot status controller exposes and updates the temporary status', () {
    final container = ProviderContainer();
    addTearDown(container.dispose);

    expect(container.read(robotStatusControllerProvider), RobotStatus.resting);

    container
        .read(robotStatusControllerProvider.notifier)
        .updateStatus(RobotStatus.takingWind);

    expect(
      container.read(robotStatusControllerProvider),
      RobotStatus.takingWind,
    );
  });

  const cases = <RobotStatus, ({IconData icon, Color color, String label})>{
    RobotStatus.drinkingWater: (
      icon: Icons.water_drop_outlined,
      color: Color(0xFF4A90C2),
      label: '물 마시는 중',
    ),
    RobotStatus.takingSunlight: (
      icon: Icons.wb_sunny_outlined,
      color: Color(0xFFE0A52B),
      label: '햇빛 쬐는 중',
    ),
    RobotStatus.takingWind: (
      icon: Icons.air_rounded,
      color: Color(0xFF5F9F8C),
      label: '바람 쐬는 중',
    ),
    RobotStatus.resting: (
      icon: Icons.bedtime_outlined,
      color: Color(0xFF7C709A),
      label: '휴식 중',
    ),
  };

  for (final MapEntry(key: status, value: expected) in cases.entries) {
    testWidgets('robot status button shows ${status.name} below the icon', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: RobotStatusButton(status: status)),
        ),
      );

      final icon = tester.widget<Icon>(
        find.byKey(Key('home_robot_status_icon_${status.name}')),
      );
      expect(icon.icon, expected.icon);
      expect(icon.color, expected.color);
      expect(find.text(expected.label), findsNothing);

      await tester.tap(find.byKey(const Key('home_robot_status_button')));
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text(expected.label), findsOneWidget);
      expect(
        tester.getRect(find.text(expected.label)).top,
        greaterThan(
          tester
              .getRect(find.byKey(const Key('home_robot_status_button')))
              .bottom,
        ),
      );
    });
  }
}
