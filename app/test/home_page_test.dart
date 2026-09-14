import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';

import 'support/fake_home_repository.dart';

void main() {
  testWidgets('home dashboard follows the selected plant', (tester) async {
    final homeRepository = FakeHomeRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(
            _AuthenticatedHomeRepository(),
          ),
          homeRepositoryProvider.overrideWithValue(homeRepository),
        ],
        child: const PotnerApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('home_page')), findsOneWidget);
    expect(find.text('안녕하세요, 포트너님!'), findsOneWidget);
    expect(find.text('아주 좋아요!'), findsOneWidget);

    await tester.tap(find.byKey(const Key('home_plant_dropdown')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('몬스테라').last);
    await tester.pumpAndSettle();

    expect(find.text('조금 목말라요'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const Key('home_sensor_illuminance')),
      300,
      scrollable: find.descendant(
        of: find.byKey(const Key('home_scroll_view')),
        matching: find.byType(Scrollable),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('850 lux'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const Key('home_sensor_illuminance')),
        matching: find.text('적정'),
      ),
      findsNothing,
    );
    expect(homeRepository.requestedPlantIds, ['plant-rose', 'plant-monstera']);
  });

  testWidgets(
    'home dashboard shows the signed-in user empty state without plants',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(320, 700));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            authRepositoryProvider.overrideWithValue(
              _AuthenticatedHomeRepository(),
            ),
            homeRepositoryProvider.overrideWithValue(
              FakeHomeRepository(plants: const [], dashboards: const {}),
            ),
          ],
          child: const PotnerApp(),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('home_empty')), findsOneWidget);
      expect(find.byKey(const Key('home_robot_status_button')), findsOneWidget);
      expect(find.text('휴식 중'), findsNothing);

      final alertCenter = tester.getCenter(
        find.byKey(const Key('home_alerts_button')),
      );
      final statusCenter = tester.getCenter(
        find.byKey(const Key('home_robot_status_button')),
      );
      final profileCenter = tester.getCenter(
        find.byKey(const Key('home_my_button')),
      );
      expect(alertCenter.dx, lessThan(statusCenter.dx));
      expect(statusCenter.dx, lessThan(profileCenter.dx));

      await tester.tap(find.byKey(const Key('home_robot_status_button')));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.text('휴식 중'), findsOneWidget);

      expect(find.text('안녕하세요, 포트너님!'), findsOneWidget);
      expect(find.text('아직 등록된 식물이 없어요'), findsOneWidget);
      expect(
        find.byKey(const Key('home_empty_register_plant')),
        findsOneWidget,
      );
      expect(find.text('안녕하세요, 제니님!'), findsNothing);
      expect(find.text('로지'), findsNothing);
      expect(find.text('아주 좋아요!'), findsNothing);
      expect(find.byKey(const Key('home_plant_selector')), findsNothing);
    },
  );
}

class _AuthenticatedHomeRepository implements AuthRepository {
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
  Future<User?> restoreSession() async {
    return const User(
      userId: 'user-id',
      email: 'member@example.com',
      nickname: '포트너',
    );
  }

  @override
  Future<User> signup({
    required String email,
    required String password,
    required String nickname,
  }) async {
    return User(userId: 'new-user', email: email, nickname: nickname);
  }
}
