import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/app.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/conversation/data/conversation_repository_impl.dart';
import 'package:potner_app/features/conversation/domain/conversation_models.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';

import 'support/fake_conversation_repository.dart';
import 'support/fake_device_repository.dart';
import 'support/fake_home_repository.dart';
import 'support/fake_plant_repository.dart';

/// 대화 이력은 서버에만 쌓이고 앱이 유일한 열람 창구다. 오래된 것을 거슬러 올라가는 커서와
/// 날짜 구분선이 어긋나면 사용자가 언제 나눈 말인지 알 수 없어, 그 두 가지를 고정한다.
Future<FakeConversationRepository> _pumpToConversation(
  WidgetTester tester, {
  List<ManagedRobot>? robots,
  void Function(FakeConversationRepository)? setup,
}) async {
  final conversationRepository = FakeConversationRepository();
  setup?.call(conversationRepository);

  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        deviceRepositoryProvider.overrideWithValue(
          FakeDeviceRepository(robots: robots),
        ),
        conversationRepositoryProvider.overrideWithValue(
          conversationRepository,
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
  final menuScrollable = find.descendant(
    of: find.byKey(const Key('menu_scroll_view')),
    matching: find.byType(Scrollable),
  );
  await tester.scrollUntilVisible(
    find.byKey(const Key('menu_item_conversations')),
    280,
    scrollable: menuScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('menu_item_conversations')));
  await tester.pumpAndSettle();
  return conversationRepository;
}

DateTime _utc(int month, int day, int hour, int minute) =>
    DateTime.utc(2026, month, day, hour, minute);

void main() {
  testWidgets('대화를 시간순으로 보여주고 배정된 식물로 조회한다', (tester) async {
    final repository = await _pumpToConversation(
      tester,
      setup: (fake) {
        fake.pages[null] = ConversationMessagePage(
          conversationId: 'conv-1',
          messages: conversationMessages([
            (seq: 0, mine: true, content: '지금 온도 어때?', utc: _utc(8, 4, 1, 5)),
            (seq: 1, mine: false, content: '22도야, 딱 좋아!', utc: _utc(8, 4, 1, 6)),
          ]),
          nextBeforeSeq: 0,
        );
      },
    );

    expect(find.byKey(const Key('conversation_page')), findsOneWidget);
    expect(find.text('지금 온도 어때?'), findsOneWidget);
    expect(find.text('22도야, 딱 좋아!'), findsOneWidget);

    // 대화는 식물 단위다. 배정된 식물 id 로 조회해야 한다.
    expect(repository.calls, hasLength(1));
    expect(repository.calls.single.plantId, 'plant-rose');
    // 첫 페이지는 커서를 보내지 않는다.
    expect(repository.calls.single.beforeSeq, isNull);
  });

  /// 로봇 말풍선에만 식물 이름을 붙인다. 내 말에 내 이름을 다는 채팅은 없다.
  testWidgets('로봇 말풍선에 식물 이름을 붙인다', (tester) async {
    await _pumpToConversation(
      tester,
      setup: (fake) {
        fake.pages[null] = ConversationMessagePage(
          messages: conversationMessages([
            (seq: 0, mine: true, content: '안녕', utc: _utc(8, 4, 1, 5)),
            (seq: 1, mine: false, content: '안녕!', utc: _utc(8, 4, 1, 6)),
          ]),
        );
      },
    );

    // FakeDeviceRepository 의 배정 식물 이름이 '로지'다.
    expect(find.text('로지'), findsOneWidget);
  });

  /// UTC 를 그대로 쓰면 자정 근처 대화가 전날로 묶인다. 로컬(KST) 기준으로 갈라야 한다.
  testWidgets('날짜가 바뀌면 구분선을 넣는다', (tester) async {
    await _pumpToConversation(
      tester,
      setup: (fake) {
        fake.pages[null] = ConversationMessagePage(
          messages: conversationMessages([
            // KST 8/3 22:00
            (seq: 0, mine: true, content: '어제 말', utc: _utc(8, 3, 13, 0)),
            // KST 8/4 09:00 — 날짜가 바뀐다
            (seq: 1, mine: false, content: '오늘 말', utc: _utc(8, 4, 0, 0)),
          ]),
        );
      },
    );

    expect(find.textContaining('8월 4일'), findsOneWidget);
    expect(find.textContaining('8월 3일'), findsOneWidget);
    // 더 받을 것이 없으므로 대화의 시작임을 알린다.
    expect(find.byKey(const Key('conversation_start_marker')), findsOneWidget);
  });

  /// 더 받을 것이 남아 있으면 가장 오래된 발화가 그 날의 첫 발화라고 단정할 수 없다.
  testWidgets('더 받을 것이 있으면 시작 표시를 내린다', (tester) async {
    await _pumpToConversation(
      tester,
      setup: (fake) {
        fake.pages[null] = ConversationMessagePage(
          messages: conversationMessages([
            (seq: 10, mine: true, content: '중간 말', utc: _utc(8, 4, 1, 0)),
          ]),
          nextBeforeSeq: 10,
          hasMore: true,
        );
      },
    );

    expect(find.byKey(const Key('conversation_start_marker')), findsNothing);
  });

  testWidgets('위로 올리면 커서로 이전 대화를 더 받는다', (tester) async {
    final repository = await _pumpToConversation(
      tester,
      setup: (fake) {
        fake.pages[null] = ConversationMessagePage(
          messages: conversationMessages([
            for (var i = 20; i < 40; i++)
              (
                seq: i,
                mine: i.isEven,
                content: '최근 발화 $i',
                utc: _utc(8, 4, 1, 0),
              ),
          ]),
          nextBeforeSeq: 20,
          hasMore: true,
        );
        fake.pages[20] = ConversationMessagePage(
          messages: conversationMessages([
            (seq: 19, mine: false, content: '더 오래된 발화', utc: _utc(8, 3, 1, 0)),
          ]),
          nextBeforeSeq: 19,
        );
      },
    );

    expect(find.text('더 오래된 발화'), findsNothing);

    // reverse 목록은 AxisDirection.up 이라 오프셋이 커지는 방향이 아래로 미는 쪽(+y)이다.
    // 목록 끝(가장 오래된 쪽)까지 넉넉히 민다 — 컨트롤러는 남은 거리가 300 미만일 때 다음
    // 페이지를 부른다.
    await tester.drag(
      find.byKey(const Key('conversation_list')),
      const Offset(0, 3000),
    );
    await tester.pumpAndSettle();

    expect(repository.calls, hasLength(2));
    // 두 번째 요청은 서버가 준 nextBeforeSeq 를 그대로 넘긴다.
    expect(repository.calls.last.beforeSeq, 20);
    expect(find.text('더 오래된 발화'), findsOneWidget);
  });

  testWidgets('대화가 없으면 안내를 보여 준다', (tester) async {
    // 서버는 대화가 없을 때 404 가 아니라 빈 목록을 준다.
    await _pumpToConversation(tester);

    expect(find.byKey(const Key('conversation_empty')), findsOneWidget);
    expect(find.byKey(const Key('conversation_list')), findsNothing);
  });

  testWidgets('첫 조회가 실패하면 다시 시도할 수 있다', (tester) async {
    final repository = await _pumpToConversation(
      tester,
      setup: (fake) => fake.error = Exception('boom'),
    );

    expect(find.byKey(const Key('conversation_error')), findsOneWidget);

    repository.error = null;
    repository.pages[null] = ConversationMessagePage(
      messages: conversationMessages([
        (seq: 0, mine: false, content: '다시 불러온 말', utc: _utc(8, 4, 1, 0)),
      ]),
    );
    await tester.tap(find.byKey(const Key('conversation_retry')));
    await tester.pumpAndSettle();

    expect(find.text('다시 불러온 말'), findsOneWidget);
  });

  testWidgets('배정된 식물이 없으면 조회하지 않는다', (tester) async {
    final repository = await _pumpToConversation(
      tester,
      robots: const [
        ManagedRobot(
          robotId: 'robot-unassigned',
          deviceUid: 'potner-unassigned',
          name: '미배정 로봇',
          connectionStatus: DeviceConnectionStatus.offline,
          devices: [],
        ),
      ],
    );

    expect(find.byKey(const Key('conversation_unavailable')), findsOneWidget);
    expect(repository.calls, isEmpty);
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
