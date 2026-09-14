import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/diary/data/diary_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_diary_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

Future<void> _pumpToDiary(WidgetTester tester) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        diaryRepositoryProvider.overrideWithValue(FakeDiaryRepository()),
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
  // 메뉴 항목이 늘어나면 화면 밖으로 밀린다. 눌러야 할 것을 먼저 화면 안으로 들인다.
  await tester.ensureVisible(find.byKey(const Key('menu_item_plant_diary')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('menu_item_plant_diary')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('diary calendar marks today and opens the diary detail', (
    tester,
  ) async {
    await _pumpToDiary(tester);

    expect(find.text('식물 일기'), findsWidgets);
    expect(find.text('날짜 선택하기'), findsOneWidget);

    final today = DateTime.now();
    expect(
      find.text('${today.year}년 ${today.month}월 ${today.day}일'),
      findsOneWidget,
    );
    expect(find.byKey(const Key('diary_preview_card')), findsOneWidget);
    expect(find.text('나의 첫 번째 꽃망울'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('diary_preview_card')));
    await tester.tap(find.byKey(const Key('diary_preview_card')));
    await tester.pumpAndSettle();

    expect(find.text('일기 상세'), findsOneWidget);
    expect(find.text('나의 첫 번째 꽃망울'), findsOneWidget);
    expect(find.textContaining('오늘은 정말 특별한 날이다'), findsOneWidget);
    expect(find.text('이 날은 촬영된 사진이 없어요.'), findsOneWidget);
  });

  testWidgets('diary calendar shows an empty message for a day without diary', (
    tester,
  ) async {
    await _pumpToDiary(tester);

    // 어제 날짜를 선택한다. 달이 바뀌는 1일이면 이전 달로 넘어가는 대신 2일을 고른다.
    final today = DateTime.now();
    final target = today.day > 1
        ? DateTime(today.year, today.month, today.day - 1)
        : DateTime(today.year, today.month, 2);
    final key =
        'diary_day_${target.year}-${target.month.toString().padLeft(2, '0')}-'
        '${target.day.toString().padLeft(2, '0')}';

    await tester.ensureVisible(find.byKey(Key(key)));
    await tester.tap(find.byKey(Key(key)));
    await tester.pumpAndSettle();

    expect(find.text('이 날은 기록된 일기가 없어요.'), findsOneWidget);
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
