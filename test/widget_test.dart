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
  testWidgets('login restores the authenticated route with the current user', (
    tester,
  ) async {
    final repository = _FakeAuthRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(repository),
          homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        ],
        child: const PotnerApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Welcome Back'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('login_email')),
      ' MEMBER@EXAMPLE.COM ',
    );
    await tester.enterText(
      find.byKey(const Key('login_password')),
      'password1',
    );
    await tester.tap(find.byKey(const Key('login_submit')));
    await tester.pumpAndSettle();

    expect(repository.loginEmail, 'member@example.com');
    expect(find.text('안녕하세요, 포트너님!'), findsOneWidget);
    expect(find.text('아주 좋아요!'), findsOneWidget);
  });

  testWidgets('signup validates password confirmation before submission', (
    tester,
  ) async {
    final repository = _FakeAuthRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(repository),
          homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        ],
        child: const PotnerApp(),
      ),
    );
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('go_to_signup')));
    await tester.tap(find.byKey(const Key('go_to_signup')));
    await tester.pumpAndSettle();
    expect(find.text('회원가입'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('signup_email')),
      'new@example.com',
    );
    await tester.enterText(find.byKey(const Key('signup_nickname')), '새싹');
    await tester.enterText(
      find.byKey(const Key('signup_password')),
      'password1',
    );
    await tester.enterText(
      find.byKey(const Key('signup_password_confirm')),
      'password2',
    );
    await tester.ensureVisible(find.byKey(const Key('signup_submit')));
    await tester.tap(find.byKey(const Key('signup_submit')));
    await tester.pump();

    expect(find.text('비밀번호가 일치하지 않습니다.'), findsOneWidget);
    expect(repository.signupCalls, 0);
  });

  testWidgets('successful signup auto-logs in and lands home via the complete screen', (
    tester,
  ) async {
    final repository = _FakeAuthRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(repository),
          homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        ],
        child: const PotnerApp(),
      ),
    );
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('go_to_signup')));
    await tester.tap(find.byKey(const Key('go_to_signup')));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('signup_email')),
      'new@example.com',
    );
    await tester.enterText(find.byKey(const Key('signup_nickname')), '새싹');
    await tester.enterText(
      find.byKey(const Key('signup_password')),
      'password1',
    );
    await tester.enterText(
      find.byKey(const Key('signup_password_confirm')),
      'password1',
    );
    await tester.ensureVisible(find.byKey(const Key('signup_submit')));
    await tester.tap(find.byKey(const Key('signup_submit')));
    await tester.pumpAndSettle();

    expect(repository.signupCalls, 1);
    expect(repository.loginEmail, 'new@example.com');
    expect(find.text('Potner와 함께할 준비, 완료!'), findsOneWidget);
    expect(find.text('회원가입이 완료되었어요!'), findsOneWidget);

    // 가입 완료 화면은 스크롤된다. 글꼴 배율 때문에 버튼이 접힌 아래로 내려갈 수 있다.
    await tester.ensureVisible(find.byKey(const Key('signup_complete_later')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('signup_complete_later')));
    await tester.pumpAndSettle();

    expect(find.text('안녕하세요, 포트너님!'), findsOneWidget);
  });
}

class _FakeAuthRepository implements AuthRepository {
  String? loginEmail;
  int signupCalls = 0;

  @override
  Future<void> clearSession() async {}

  @override
  Future<User> login({required String email, required String password}) async {
    loginEmail = email;
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
    signupCalls += 1;
    return User(userId: 'new-user', email: email, nickname: nickname);
  }
}
