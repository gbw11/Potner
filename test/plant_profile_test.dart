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
  PlantDetail? detail,
}) async {
  final plantRepository = FakePlantRepository();
  if (growthProfile != null) {
    plantRepository.growthProfile = growthProfile;
  }
  if (detail != null) {
    plantRepository.detail = detail;
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
  testWidgets('식물 프로필이 사진 아래에 꽃말과 성격 해시태그를 보여준다', (tester) async {
    await _pumpToProfile(tester);

    // 꽃말이 맨 앞이다. 나머지 태그는 성격 키워드다.
    expect(find.byKey(const Key('profile_persona')), findsOneWidget);
    expect(find.text('#좋은 소망'), findsOneWidget);
    expect(find.text('#다정함'), findsOneWidget);
    expect(find.text('#차분함'), findsOneWidget);
    expect(find.text('#꾸준함'), findsOneWidget);
    expect(find.text('바질의 성격'), findsOneWidget);
    // 일기 프롬프트의 역할 지시문("현실적 보호자")이 아니라 성격 문장이 나와야 한다.
    expect(
      find.text('다정하고 차분해요. 서두르는 법이 없고 필요한 만큼만 챙기며 곁을 편안하게 만들어요.'),
      findsOneWidget,
    );
    expect(find.textContaining('보호자'), findsNothing);
  });

  testWidgets('성격이 없는 종이면 성격 카드를 아예 그리지 않는다', (tester) async {
    // 서버가 이 필드를 아직 안 내보내는 배포에서도 같은 상태가 된다. 빈 카드가 남으면
    // 사용자에게는 데이터가 빠진 것으로 보인다.
    await _pumpToProfile(tester, detail: samplePlantDetailWithoutPersona);

    expect(find.byKey(const Key('profile_persona')), findsNothing);
    expect(find.byKey(const Key('profile_name')), findsOneWidget);
  });

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

    // 아무것도 건드리지 않으면 보낼 값이 없으므로 요청 자체를 하지 않는다.
    await tester.ensureVisible(find.byKey(const Key('care_save')));
    await tester.tap(find.byKey(const Key('care_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.growthProfileUpdates, isEmpty);
    expect(find.text('변경된 설정이 없어요.'), findsOneWidget);
  });

  testWidgets('케어 범위를 숫자로 직접 입력해 저장한다', (tester) async {
    // 슬라이더로는 21.5℃ 나 24,500 Lux 처럼 아는 값을 정확히 짚을 수 없었다.
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    // 슬라이더가 남아 있으면 두 가지 조작 방식이 섞여 어느 쪽이 저장되는지 알 수 없다.
    expect(find.byType(RangeSlider), findsNothing);
    expect(find.byType(Slider), findsNothing);

    final maxField = find.byKey(const Key('care_temperature_max'));
    await tester.ensureVisible(maxField);
    await tester.enterText(maxField, '27.5');
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('care_save')));
    await tester.tap(find.byKey(const Key('care_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.growthProfileUpdates, hasLength(1));
    expect(plantRepository.growthProfileUpdates.single.temperatureMaxC, 27.5);
    // 건드리지 않은 항목은 보내지 않는다. 기존 동작 그대로다.
    expect(plantRepository.growthProfileUpdates.single.humidityMinPct, isNull);
  });

  testWidgets('최소가 최대보다 크면 알려주고 그 값을 저장하지 않는다', (tester) async {
    // 슬라이더는 구조상 이 상태가 될 수 없었다. 직접 입력으로 바꾸며 생긴 경우다.
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    final minField = find.byKey(const Key('care_temperature_min'));
    await tester.ensureVisible(minField);
    await tester.enterText(minField, '35');
    await tester.pumpAndSettle();

    expect(find.text('최소가 최대보다 작아야 해요.'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('care_save')));
    await tester.tap(find.byKey(const Key('care_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.growthProfileUpdates, isEmpty);
  });

  testWidgets('한계를 벗어난 값은 범위를 알려주고 저장하지 않는다', (tester) async {
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    final maxField = find.byKey(const Key('care_humidity_max'));
    await tester.ensureVisible(maxField);
    await tester.enterText(maxField, '150');
    await tester.pumpAndSettle();

    expect(find.textContaining('안에서 정해 주세요.'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('care_save')));
    await tester.tap(find.byKey(const Key('care_save')));
    await tester.pumpAndSettle();

    expect(plantRepository.growthProfileUpdates, isEmpty);
  });

  testWidgets('순간 조도 대신 하루 빛의 양을 설정한다', (tester) async {
    // 순간 조도는 서버가 판정에 쓰지 않는다(밤에는 0 lux 가 정상). 설정해도 아무 일이
    // 일어나지 않는 칸이 남아 있으면 급수 주기와 같은 오해를 만든다.
    await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('care_illuminance')), findsNothing);
    final section = find.byKey(const Key('care_daily_light'));
    expect(section, findsOneWidget);
    await tester.ensureVisible(section);
    await tester.pumpAndSettle();
    expect(find.text('하루 빛의 양'), findsWidgets);
    expect(find.textContaining('로봇이 햇빛 자리로 옮겨'), findsOneWidget);
  });

  testWidgets('하루 빛의 양을 바꾸면 허용 범위도 같은 비율로 따라간다', (tester) async {
    // 서버가 min <= target <= max 를 검증한다. 목표만 보내면 예전 목표에 맞춰진 범위가
    // 남아 새 목표가 그 밖으로 나가고 저장이 거부된다.
    final plantRepository = await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    final target = find.byKey(const Key('care_daily_light_value'));
    await tester.ensureVisible(target);
    // 10,000 → 5,000 (절반). 밴드도 7,000~13,000 에서 3,500~6,500 으로 따라가야 한다.
    await tester.enterText(target, '5000');
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('care_save')));
    await tester.tap(find.byKey(const Key('care_save')));
    await tester.pumpAndSettle();

    final update = plantRepository.growthProfileUpdates.single;
    expect(update.dailyLightTargetLuxHour, 5000);
    expect(update.dailyLightMinLuxHour, 3500);
    expect(update.dailyLightMaxLuxHour, 6500);
  });

  testWidgets('급수 주기 입력 대신 센서로 준다는 안내가 뜬다', (tester) async {
    // 서버가 급수 주기를 저장만 하고 읽지 않아 입력 칸을 없앴다. 칸이 남아 있으면
    // "며칠마다 준다고 설정했는데 왜 안 주지" 라는 오해를 만든다.
    await _pumpToProfile(tester);

    await tester.ensureVisible(find.byKey(const Key('profile_care_settings')));
    await tester.tap(find.byKey(const Key('profile_care_settings')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('care_watering_cycle')), findsNothing);
    expect(find.textContaining('토양 수분을 보고 자동으로'), findsOneWidget);
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
