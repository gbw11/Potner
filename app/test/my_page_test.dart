import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/user/data/user_repository_impl.dart';

import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';
import 'support/fake_user_repository.dart';

Future<FakeUserRepository> _pumpToMyPage(WidgetTester tester) async {
  final userRepository = FakeUserRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        userRepositoryProvider.overrideWithValue(userRepository),
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

  await tester.tap(find.byKey(const Key('bottom_nav_my')));
  await tester.pumpAndSettle();
  return userRepository;
}

void main() {
  testWidgets('my page shows the profile summary and account actions', (
    tester,
  ) async {
    await _pumpToMyPage(tester);

    expect(find.byKey(const Key('my_page')), findsOneWidget);
    expect(find.text('포트너'), findsOneWidget);
    expect(find.text('식물 3개 관리 중'), findsOneWidget);
    expect(find.byKey(const Key('my_profile')), findsOneWidget);
    expect(find.byKey(const Key('my_withdraw')), findsOneWidget);
  });

  testWidgets('profile page changes the nickname', (tester) async {
    final userRepository = await _pumpToMyPage(tester);

    await tester.tap(find.byKey(const Key('my_profile')));
    await tester.pumpAndSettle();

    expect(find.text('member@example.com'), findsOneWidget);
    expect(find.text('3 개'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('profile_nickname')), '새닉네임');
    await tester.tap(find.byKey(const Key('profile_nickname_save')));
    await tester.pumpAndSettle();

    expect(userRepository.nicknameChanges, ['새닉네임']);

    await tester.tap(find.byKey(const Key('profile_page_back')));
    await tester.pumpAndSettle();
    expect(find.text('새닉네임'), findsOneWidget);
  });

  testWidgets('password change succeeds and returns to the login screen', (
    tester,
  ) async {
    final userRepository = await _pumpToMyPage(tester);

    await tester.tap(find.byKey(const Key('my_profile')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(
      find.byKey(const Key('profile_change_password')),
    );
    await tester.tap(find.byKey(const Key('profile_change_password')));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('password_current')),
      'password1',
    );
    await tester.enterText(find.byKey(const Key('password_new')), 'newpass12');
    await tester.enterText(
      find.byKey(const Key('password_confirm')),
      'newpass12',
    );
    await tester.ensureVisible(find.byKey(const Key('password_submit')));
    await tester.tap(find.byKey(const Key('password_submit')));
    await tester.pumpAndSettle();

    expect(userRepository.passwordChanges, hasLength(1));
    expect(userRepository.passwordChanges.single.current, 'password1');
    expect(userRepository.passwordChanges.single.next, 'newpass12');
    expect(find.text('Welcome Back'), findsOneWidget);
  });

  testWidgets('withdrawal deletes the account and returns to login', (
    tester,
  ) async {
    final userRepository = await _pumpToMyPage(tester);

    await tester.ensureVisible(find.byKey(const Key('my_withdraw')));
    await tester.tap(find.byKey(const Key('my_withdraw')));
    await tester.pumpAndSettle();

    expect(find.text('정말 탈퇴 하시겠어요?'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('withdraw_confirm')));
    await tester.tap(find.byKey(const Key('withdraw_confirm')));
    await tester.pumpAndSettle();

    expect(userRepository.withdrawCalls, 1);
    expect(find.text('Welcome Back'), findsOneWidget);
  });

  testWidgets('withdrawal can be cancelled', (tester) async {
    final userRepository = await _pumpToMyPage(tester);

    await tester.ensureVisible(find.byKey(const Key('my_withdraw')));
    await tester.tap(find.byKey(const Key('my_withdraw')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('withdraw_cancel')));
    await tester.tap(find.byKey(const Key('withdraw_cancel')));
    await tester.pumpAndSettle();

    expect(userRepository.withdrawCalls, 0);
    expect(find.byKey(const Key('my_page')), findsOneWidget);
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
