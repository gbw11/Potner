import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/alert/data/alert_repository_impl.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/bloom/data/bloom_repository_impl.dart';
import 'package:potner_app/features/command/data/command_repository_impl.dart';
import 'package:potner_app/features/conversation/data/conversation_repository_impl.dart';
import 'package:potner_app/features/conversation/domain/conversation_models.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/diary/data/diary_repository_impl.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/photo/data/photo_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_alert_repository.dart';
import 'support/fake_bloom_repository.dart';
import 'support/fake_command_repository.dart';
import 'support/fake_conversation_repository.dart';
import 'support/fake_device_repository.dart';
import 'support/fake_diary_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_photo_repository.dart';
import 'support/fake_plant_repository.dart';

/// 시스템 글꼴 크기를 키운 상태에서 화면이 깨지지 않는지 본다.
///
/// 사용자가 안드로이드 접근성 설정으로 글꼴을 키우면 `textScaler` 가 올라가고, 고정 높이
/// 안에 있는 글자가 넘쳐 "BOTTOM OVERFLOWED BY n PIXELS" 가 뜬다. 위젯 테스트는 오버플로를
/// 예외로 올리므로 이 테스트가 그걸 잡는다.
///
/// 1.3 을 쓰는 이유는 앱이 OS 배율을 그 값으로 제한하기 때문이다. 그보다 큰 배율은
/// [PotnerApp] 이 잘라내므로 화면에 도달하지 않는다.
const _maxSupportedScale = 1.3;

Future<void> _pumpApp(WidgetTester tester) async {
  await _pumpFresh(tester);

  await tester.enterText(
    find.byKey(const Key('login_email')),
    'member@example.com',
  );
  await tester.enterText(find.byKey(const Key('login_password')), 'password1');
  // 로그인 화면은 스크롤되므로 글꼴이 커지면 버튼이 접힌 아래로 내려간다.
  await tester.ensureVisible(find.byKey(const Key('login_submit')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('login_submit')));
  await tester.pumpAndSettle();
}

Future<void> _pumpFresh(WidgetTester tester) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        alertRepositoryProvider.overrideWithValue(FakeAlertRepository()),
        bloomRepositoryProvider.overrideWithValue(FakeBloomRepository()),
        photoRepositoryProvider.overrideWithValue(FakePhotoRepository()),
        diaryRepositoryProvider.overrideWithValue(FakeDiaryRepository()),
        deviceRepositoryProvider.overrideWithValue(FakeDeviceRepository()),
        deviceCommandRepositoryProvider.overrideWithValue(
          FakeDeviceCommandRepository(),
        ),
        // 빈 대화가 아니라 말풍선이 그려진 상태로 본다. 배율이 올라갈 때 넘칠 위험은 말풍선과
        // 날짜 구분선·시각 표시가 한 줄에 놓이는 자리에 있다.
        conversationRepositoryProvider.overrideWithValue(
          FakeConversationRepository()
            ..pages[null] = ConversationMessagePage(
              messages: conversationMessages([
                (
                  seq: 0,
                  mine: true,
                  content: '지금 온도랑 습도 어때? 물은 언제 줬어?',
                  utc: DateTime.utc(2026, 8, 4, 1, 5),
                ),
                (
                  seq: 1,
                  mine: false,
                  content: '온도 22도에 습도 55%야. 딱 좋아! 물은 어제 받았어.',
                  utc: DateTime.utc(2026, 8, 4, 1, 6),
                ),
              ]),
            ),
        ),
      ],
      child: const PotnerApp(),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _openMenuItem(WidgetTester tester, String key) async {
  await tester.tap(find.byKey(const Key('open_menu_button')));
  await tester.pumpAndSettle();
  final menuScrollable = find.descendant(
    of: find.byKey(const Key('menu_scroll_view')),
    matching: find.byType(Scrollable),
  );
  await tester.scrollUntilVisible(
    find.byKey(Key(key)),
    240,
    scrollable: menuScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    final dispatcher = TestWidgetsFlutterBinding.instance.platformDispatcher;
    dispatcher.textScaleFactorTestValue = _maxSupportedScale;
    addTearDown(dispatcher.clearTextScaleFactorTestValue);
  });

  testWidgets('글꼴을 키워도 로그인과 회원가입 화면이 넘치지 않는다', (tester) async {
    await _pumpFresh(tester);

    // 로그인 화면이 먼저 뜬다. 여기서 회원가입으로 넘어간다.
    await tester.ensureVisible(find.byKey(const Key('go_to_signup')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('go_to_signup')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('go_to_login')), findsOneWidget);
  });

  testWidgets('글꼴을 키워도 홈과 하단 탭이 넘치지 않는다', (tester) async {
    await _pumpApp(tester);

    for (final tab in const ['home', 'growth', 'alerts', 'my']) {
      await tester.tap(find.byKey(Key('bottom_nav_$tab')));
      await tester.pumpAndSettle();
    }
  });

  // 화면마다 따로 띄운다. 메뉴에서 들어간 화면은 뒤로 가는 방법이 저마다 달라서(하단 탭이
  // 있는 화면, 뒤로 버튼만 있는 화면) 한 테스트에서 이어 돌리면 이동 실패가 오버플로처럼
  // 보인다.
  for (final item in const [
    'menu_item_plants',
    'menu_item_photo_log',
    'menu_item_growth_comparison',
    'menu_item_repotting',
    'menu_item_devices',
    'menu_item_robot_drive',
    'menu_item_conversations',
    'menu_item_alert_history',
    'menu_item_care_settings',
    'menu_item_environment',
    // 알림 설정은 넣지 않는다. FCM 등록이 끝나지 않아 로딩 표시가 계속 돌고 pumpAndSettle 이
    // 끝나지 않는다 — 오버플로와 무관한 실패라 여기서 걸러야 신호가 흐려지지 않는다.
  ]) {
    testWidgets('글꼴을 키워도 $item 화면이 넘치지 않는다', (tester) async {
      await _pumpApp(tester);
      await _openMenuItem(tester, item);
    });
  }
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
