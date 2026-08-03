import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/photo/data/photo_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';

import 'support/fake_home_repository.dart';
import 'support/fake_photo_repository.dart';
import 'support/fake_plant_repository.dart';

Future<FakePlantRepository> _pumpToProfile(
  WidgetTester tester, {
  FakePhotoRepository? photoRepository,
  GrowthProfile? growthProfile,
}) async {
  final plantRepository = FakePlantRepository();
  if (growthProfile != null) {
    plantRepository.growthProfile = growthProfile;
  }
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(plantRepository),
        photoRepositoryProvider.overrideWithValue(
          photoRepository ?? FakePhotoRepository(),
        ),
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

  testWidgets('대표 사진 변경 시트는 올리기와 포토 로그에서 고르기를 함께 제공한다', (tester) async {
    final photoRepository = FakePhotoRepository();
    await _pumpToProfile(tester, photoRepository: photoRepository);

    await tester.tap(find.byKey(const Key('profile_photo_button')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('profile_photo_camera')), findsOneWidget);
    expect(find.byKey(const Key('profile_photo_gallery')), findsOneWidget);
    expect(find.byKey(const Key('profile_photo_from_log')), findsOneWidget);
    // 대표 사진이 없는 식물에는 해제할 것이 없다.
    expect(find.byKey(const Key('profile_photo_clear')), findsNothing);
  });

  testWidgets('포토 로그에서 고른 사진을 대표로 지정한다', (tester) async {
    final photoRepository = FakePhotoRepository();
    await _pumpToProfile(tester, photoRepository: photoRepository);

    await tester.tap(find.byKey(const Key('profile_photo_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile_photo_from_log')));
    await tester.pumpAndSettle();

    // 고를 때는 최신이 위로 온다. 서버는 타임랩스 순서(오래된 순)로 내려준다.
    expect(find.byKey(const Key('profile_photo_option_photo-new')), findsOne);
    expect(find.byKey(const Key('profile_photo_option_photo-old')), findsOne);

    await tester.tap(find.byKey(const Key('profile_photo_option_photo-new')));
    await tester.pumpAndSettle();

    expect(photoRepository.representativeCalls, hasLength(1));
    expect(photoRepository.representativeCalls.single.plantId, 'plant-rose');
    expect(photoRepository.representativeCalls.single.photoId, 'photo-new');
    expect(photoRepository.uploadCalls, isEmpty);
    expect(find.text('대표 사진을 변경했어요.'), findsOneWidget);
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

  testWidgets('급수량이 비어 있어도 슬라이더가 보이고 저장하면 값이 들어간다', (tester) async {
    // 값이 없을 때 슬라이더를 감추면 값을 넣을 방법이 사라진다. 새로 등록한 식물은 급수량이
    // 비어 있어서 자동 급수가 무엇을 줄지 정하지 못했다.
    final plantRepository = await _pumpToProfile(
      tester,
      growthProfile: const GrowthProfile(
        plantId: 'plant-rose',
        customized: false,
        soilMoistureMinPct: 40,
        soilMoistureMaxPct: 50,
        temperatureMinC: 18,
        temperatureMaxC: 26,
        humidityMinPct: 50,
        humidityMaxPct: 70,
        wateringCycleDays: 7,
      ),
    );

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    final section = find.byKey(const Key('care_watering_ml'));
    expect(section, findsOneWidget);
    await tester.ensureVisible(section);
    await tester.pumpAndSettle();
    expect(find.text('아직 저장된 급수량이 없어요. 저장하면 이 값으로 설정됩니다.'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('care_save')));
    await tester.tap(find.byKey(const Key('care_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.growthProfileUpdates, hasLength(1));
    expect(plantRepository.growthProfileUpdates.single.recommendedWateringMl, 200);
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
