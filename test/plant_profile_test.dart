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

Future<FakePlantRepository> _pumpToProfile(WidgetTester tester) async {
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

  await tester.tap(find.byKey(const Key('open_menu_button')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('menu_item_plants')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('plant_card_plant-rose')));
  await tester.pumpAndSettle();
  return plantRepository;
}

void main() {
  testWidgets('plant profile shows detail and saves an edited name', (
    tester,
  ) async {
    final plantRepository = await _pumpToProfile(tester);

    expect(find.text('식물 프로필'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '로지'), findsOneWidget);
    expect(find.text('2026년 3월 20일'), findsOneWidget);
    expect(find.textContaining('D+'), findsOneWidget);
    final environmentButton = tester.widget<FilledButton>(
      find.byKey(const Key('profile_environment')),
    );
    expect(
      environmentButton.style?.backgroundColor?.resolve(<WidgetState>{}),
      const Color(0xFFCCEBC0),
    );
    expect(
      environmentButton.style?.foregroundColor?.resolve(<WidgetState>{}),
      const Color(0xFF334F2B),
    );

    await tester.enterText(find.byKey(const Key('profile_name')), '로즈');
    await tester.ensureVisible(find.byKey(const Key('profile_save')));
    await tester.tap(find.byKey(const Key('profile_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.updatePlantCalls, hasLength(1));
    final call = plantRepository.updatePlantCalls.single;
    expect(call.plantId, 'plant-rose');
    expect(call.name, '로즈');
    expect(call.adoptedDate, isNull);
    expect(plantRepository.changedLifeStageIds, isEmpty);
  });

  testWidgets('plant profile changes the growth stage', (tester) async {
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_growth_stage')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile_growth_stage')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('성장기').last);
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('profile_save')));
    await tester.tap(find.byKey(const Key('profile_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.changedLifeStageIds, ['stage-growth']);
    expect(plantRepository.updatePlantCalls, isEmpty);
  });

  testWidgets('plant profile deletes the plant after confirmation', (
    tester,
  ) async {
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_delete')));
    await tester.tap(find.byKey(const Key('profile_delete')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile_delete_confirm')));
    await tester.pumpAndSettle();

    expect(plantRepository.deletedPlantIds, ['plant-rose']);
    expect(find.text('나의 식물'), findsWidgets);
  });

  testWidgets('care settings submits only the changed values', (tester) async {
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    expect(find.text('케어 설정'), findsOneWidget);
    expect(find.byKey(const Key('care_soil_moisture')), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('care_watering_cycle')));
    await tester.enterText(find.byKey(const Key('care_watering_cycle')), '10');

    await tester.ensureVisible(find.byKey(const Key('care_save')));
    await tester.tap(find.byKey(const Key('care_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.growthProfileUpdates, hasLength(1));
    final update = plantRepository.growthProfileUpdates.single;
    expect(update.wateringCycleDays, 10);
    expect(update.soilMoistureMinPct, isNull);
    expect(update.temperatureMinC, isNull);
  });

  testWidgets('care settings resets to species defaults', (tester) async {
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('care_reset')));
    await tester.tap(find.byKey(const Key('care_reset')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('care_reset_confirm')));
    await tester.pumpAndSettle();

    expect(plantRepository.resetCalls, 1);
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
