import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<FakePlantRepository> _pumpToHome(WidgetTester tester) async {
  final plantRepository = FakePlantRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(plantRepository),
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

void main() {
  testWidgets('my plants list shows the count and every plant', (tester) async {
    await _pumpToHome(tester);

    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('menu_item_plants')));
    await tester.pumpAndSettle();

    expect(find.text('나의 식물'), findsWidgets);
    expect(find.textContaining('3개', findRichText: true), findsOneWidget);
    expect(find.text('로지'), findsOneWidget);
    expect(find.text('장미'), findsOneWidget);
    expect(find.text('마리아'), findsOneWidget);
    expect(find.text('해님'), findsOneWidget);
  });

  testWidgets('registered plants can be deleted with confirmation', (
    tester,
  ) async {
    final plantRepository = await _pumpToHome(tester);

    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('menu_item_registered_plants')));
    await tester.pumpAndSettle();

    expect(find.text('등록 식물'), findsWidgets);
    expect(find.text('총 등록 식물'), findsOneWidget);
    expect(find.text('3개'), findsOneWidget);

    await tester.tap(find.byKey(const Key('delete_plant_plant-rose')));
    await tester.pumpAndSettle();

    expect(find.text('로지 삭제'), findsOneWidget);
    await tester.tap(find.byKey(const Key('plant_delete_confirm')));
    await tester.pumpAndSettle();

    expect(plantRepository.deletedPlantIds, ['plant-rose']);
    expect(find.text('로지'), findsNothing);
    expect(find.text('2개'), findsOneWidget);
  });

  testWidgets('registered plants keeps the plant when deletion is cancelled', (
    tester,
  ) async {
    final plantRepository = await _pumpToHome(tester);

    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('menu_item_registered_plants')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('delete_plant_plant-rose')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('plant_delete_cancel')));
    await tester.pumpAndSettle();

    expect(plantRepository.deletedPlantIds, isEmpty);
    expect(find.text('로지'), findsOneWidget);
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
