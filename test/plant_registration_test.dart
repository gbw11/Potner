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

void main() {
  testWidgets('plant registration submits selections and returns to menu', (
    tester,
  ) async {
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
    await tester.enterText(
      find.byKey(const Key('login_password')),
      'password1',
    );
    await tester.tap(find.byKey(const Key('login_submit')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('menu_item_plant_registration')));
    await tester.pumpAndSettle();

    expect(find.text('내 식물 등록하기'), findsOneWidget);
    expect(find.text('새로운 식물 친구를 소개해 주세요!'), findsOneWidget);
    expect(find.byKey(const Key('plant_photo_button')), findsOneWidget);

    await tester.tap(find.byKey(const Key('plant_photo_button')));
    await tester.pumpAndSettle();
    expect(find.text('식물 사진 등록'), findsOneWidget);
    expect(find.byKey(const Key('plant_photo_camera')), findsOneWidget);
    expect(find.byKey(const Key('plant_photo_gallery')), findsOneWidget);
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('plant_name')), '바질이');

    await tester.ensureVisible(find.byKey(const Key('plant_category')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('plant_category')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('허브').last);
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('plant_species')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('plant_species')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('바질').last);
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('plant_growth_stage')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('plant_growth_stage')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('모종기').last);
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('plant_submit')));
    await tester.tap(find.byKey(const Key('plant_submit')));
    await tester.pumpAndSettle();

    expect(plantRepository.createCalls, hasLength(1));
    final call = plantRepository.createCalls.single;
    expect(call.name, '바질이');
    expect(call.speciesId, 'species-basil');
    expect(call.lifeStageId, 'stage-seedling');
    expect(call.adoptedDate, isNull);

    expect(find.byKey(const Key('menu_page')), findsOneWidget);
  });

  testWidgets('plant registration requires every selection before submit', (
    tester,
  ) async {
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
    await tester.enterText(
      find.byKey(const Key('login_password')),
      'password1',
    );
    await tester.tap(find.byKey(const Key('login_submit')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('menu_item_plant_registration')));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('plant_submit')));
    await tester.tap(find.byKey(const Key('plant_submit')));
    await tester.pumpAndSettle();

    expect(find.text('식물 이름을 입력해 주세요.'), findsOneWidget);
    expect(find.text('대분류를 선택해 주세요.'), findsOneWidget);
    expect(find.text('소분류를 선택해 주세요.'), findsOneWidget);
    expect(find.text('자람 수준을 선택해 주세요.'), findsOneWidget);
    expect(plantRepository.createCalls, isEmpty);
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
