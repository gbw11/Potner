import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/bloom/data/bloom_repository_impl.dart';
import 'package:potner_app/features/diary/data/diary_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_bloom_repository.dart';
import 'support/fake_diary_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<FakeBloomRepository> _pumpToGrowthHub(
  WidgetTester tester, {
  FakePlantRepository? plantRepository,
}) async {
  final bloomRepository = FakeBloomRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(
          plantRepository ?? FakePlantRepository(),
        ),
        diaryRepositoryProvider.overrideWithValue(FakeDiaryRepository()),
        bloomRepositoryProvider.overrideWithValue(bloomRepository),
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

  await tester.tap(find.byKey(const Key('bottom_nav_growth')));
  await tester.pumpAndSettle();
  return bloomRepository;
}

void main() {
  testWidgets('growth hub links to every record screen', (tester) async {
    await _pumpToGrowthHub(tester);

    expect(find.byKey(const Key('growth_hub_page')), findsOneWidget);
    expect(find.text('식물 일기'), findsOneWidget);
    expect(find.text('개화 기록'), findsOneWidget);
    expect(find.text('포토 로그'), findsOneWidget);
    expect(find.text('성장 비교'), findsOneWidget);

    await tester.tap(find.byKey(const Key('growth_hub_diary')));
    await tester.pumpAndSettle();
    expect(find.text('날짜 선택하기'), findsOneWidget);
  });

  testWidgets('bloom list shows records without manual record action', (
    tester,
  ) async {
    await _pumpToGrowthHub(tester);

    await tester.tap(find.byKey(const Key('growth_hub_blooms')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('bloom_card_bloom-1')), findsOneWidget);
    expect(find.text('첫 꽃망울!'), findsOneWidget);
    expect(find.text('직접 기록'), findsOneWidget);
    expect(find.byKey(const Key('bloom_record_button')), findsNothing);
    expect(find.text('개화 기록하기'), findsNothing);
  });

  testWidgets('bloom record can be deleted after confirmation', (tester) async {
    final bloomRepository = await _pumpToGrowthHub(tester);

    await tester.tap(find.byKey(const Key('growth_hub_blooms')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('delete_bloom_bloom-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('bloom_delete_confirm')));
    await tester.pumpAndSettle();

    expect(bloomRepository.deletedBloomIds, ['bloom-1']);
    expect(find.byKey(const Key('bloom_card_bloom-1')), findsNothing);
  });

  testWidgets('bloom list guides users without registered plants', (
    tester,
  ) async {
    await _pumpToGrowthHub(
      tester,
      plantRepository: FakePlantRepository(myPlants: const []),
    );

    await tester.tap(find.byKey(const Key('growth_hub_blooms')));
    await tester.pumpAndSettle();

    expect(find.text('식물을 등록하면 꽃이 피어난 특별한 순간을 볼 수 있어요'), findsOneWidget);
    expect(find.byKey(const Key('bloom_register_plant')), findsOneWidget);
    expect(find.byKey(const Key('bloom_record_button')), findsNothing);
  });

  testWidgets('growth comparison guides users without registered plants', (
    tester,
  ) async {
    await _pumpToGrowthHub(
      tester,
      plantRepository: FakePlantRepository(myPlants: const []),
    );

    await tester.tap(find.byKey(const Key('growth_hub_compare')));
    await tester.pumpAndSettle();

    expect(find.text('식물을 등록하면 성장 사진을 나란히 비교할 수 있어요.'), findsOneWidget);
    expect(
      find.byKey(const Key('growth_comparison_register_plant')),
      findsOneWidget,
    );
  });

  testWidgets('diary and photo log show icons without registered plants', (
    tester,
  ) async {
    await _pumpToGrowthHub(
      tester,
      plantRepository: FakePlantRepository(myPlants: const []),
    );

    await tester.tap(find.byKey(const Key('growth_hub_diary')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('diary_empty_icon')), findsOneWidget);

    await tester.tap(find.byKey(const Key('diary_calendar_back')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('growth_hub_photos')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('photo_log_empty_icon')), findsOneWidget);
  });

  testWidgets('diary detail shows the status report', (tester) async {
    await _pumpToGrowthHub(tester);

    await tester.tap(find.byKey(const Key('growth_hub_diary')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('diary_preview_card')));
    await tester.tap(find.byKey(const Key('diary_preview_card')));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('diary_status_report')));
    await tester.pumpAndSettle();

    expect(find.text('상태 리포트'), findsOneWidget);
    expect(find.text('95'), findsOneWidget);
    expect(find.text('6.5 hours'), findsOneWidget);
    expect(find.text('기록 준비 중'), findsOneWidget);
    expect(find.text('꽃이 피었어요!'), findsOneWidget);
    expect(find.text('-10점'), findsOneWidget);
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
