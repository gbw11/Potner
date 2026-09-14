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

import 'support/fake_home_repository.dart';
import 'support/fake_photo_repository.dart';
import 'support/fake_plant_repository.dart';

Future<FakePhotoRepository> _pumpToPhotoLog(WidgetTester tester) async {
  final photoRepository = FakePhotoRepository();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
        homeRepositoryProvider.overrideWithValue(FakeHomeRepository()),
        plantRepositoryProvider.overrideWithValue(FakePlantRepository()),
        photoRepositoryProvider.overrideWithValue(photoRepository),
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
  await _tapMenuItem(tester, 'menu_item_photo_log');
  return photoRepository;
}

Future<void> _tapMenuItem(WidgetTester tester, String key) async {
  final menuScrollable = find.descendant(
    of: find.byKey(const Key('menu_scroll_view')),
    matching: find.byType(Scrollable),
  );
  await tester.scrollUntilVisible(
    find.byKey(Key(key)),
    160,
    scrollable: menuScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'photo log lists photos newest first and sets the representative photo',
    (tester) async {
      final photoRepository = await _pumpToPhotoLog(tester);

      expect(find.text('포토 로그'), findsWidgets);
      expect(find.byKey(const Key('photo_card_photo-new')), findsOneWidget);
      expect(find.byKey(const Key('photo_card_photo-old')), findsOneWidget);

      final newTop = tester.getTopLeft(
        find.byKey(const Key('photo_card_photo-new')),
      );
      final oldTop = tester.getTopLeft(
        find.byKey(const Key('photo_card_photo-old')),
      );
      expect(newTop.dy, lessThan(oldTop.dy));

      await tester.tap(find.byKey(const Key('photo_card_photo-new')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('photo_detail_image')), findsOneWidget);
      await tester.tap(find.byKey(const Key('photo_set_representative')));
      await tester.pumpAndSettle();

      expect(photoRepository.representativeCalls, hasLength(1));
      expect(photoRepository.representativeCalls.single.photoId, 'photo-new');
      expect(find.text('대표 사진으로 지정했어요.'), findsOneWidget);
    },
  );

  testWidgets('사진을 길게 눌러 확인을 거쳐 지우면 목록에서 빠진다', (tester) async {
    final photoRepository = await _pumpToPhotoLog(tester);

    await tester.longPress(find.byKey(const Key('photo_card_photo-old')));
    await tester.pumpAndSettle();

    // 되돌릴 수 없으므로 한 번 묻는다.
    expect(find.text('사진 삭제'), findsOneWidget);
    await tester.tap(find.byKey(const Key('photo_delete_confirm')));
    await tester.pumpAndSettle();

    expect(photoRepository.deleteCalls, hasLength(1));
    expect(photoRepository.deleteCalls.single.photoId, 'photo-old');
    // 로컬에서 빼지 않고 재조회로 갱신한다 — 타임랩스·성장 비교가 같은 조회를 쓴다.
    expect(find.byKey(const Key('photo_card_photo-old')), findsNothing);
    expect(find.byKey(const Key('photo_card_photo-new')), findsOneWidget);
    expect(find.text('사진을 지웠어요.'), findsOneWidget);
  });

  testWidgets('삭제 확인에서 그만두면 사진이 남는다', (tester) async {
    final photoRepository = await _pumpToPhotoLog(tester);

    await tester.longPress(find.byKey(const Key('photo_card_photo-old')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('그만두기'));
    await tester.pumpAndSettle();

    expect(photoRepository.deleteCalls, isEmpty);
    expect(find.byKey(const Key('photo_card_photo-old')), findsOneWidget);
  });

  testWidgets('타임랩스는 오래된 사진부터 재생하고 재생용 축소본을 쓴다', (tester) async {
    // 디자인상 진입점은 성장 비교 화면이다 — 전·후 두 장 아래에서 전체 흐름으로 이어진다.
    await _pumpToPhotoLog(tester);
    await tester.tap(find.byKey(const Key('bottom_nav_home')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    await _tapMenuItem(tester, 'menu_item_growth_comparison');

    await tester.ensureVisible(
      find.byKey(const Key('growth_comparison_timelapse')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('growth_comparison_timelapse')));
    // pumpAndSettle 은 쓸 수 없다. 재생 타이머가 프레임을 계속 예약해서 끝까지 돌아 버린다.
    // 화면 전환(300ms)만 넘기고 첫 프레임에서 멈춘다.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    // 서버가 오래된 순으로 내려주므로 첫 프레임은 가장 오래된 사진이다.
    Image frame() => tester.widget<Image>(find.byKey(const Key('timelapse_frame')));
    expect(
      (frame().image as NetworkImage).url,
      'https://images.test/old-play.jpg',
    );
    expect(find.text('1 / 2일차'), findsOneWidget);

    // 원본이 아니라 playbackUrl 을 쓴다. 원본은 수 MB 라 연속 재생에서 끊긴다.
    expect((frame().image as NetworkImage).url, isNot(contains('old.jpg')));

    // 기본 배속은 2x 라 한 프레임이 400ms 다.
    await tester.pump(const Duration(milliseconds: 120));
    expect(
      (frame().image as NetworkImage).url,
      'https://images.test/new-play.jpg',
    );

    // 마지막 프레임에서 멈춘다 — 반복하면 가장 최근 모습을 볼 시간이 없다.
    await tester.pump(const Duration(seconds: 3));
    expect(
      (frame().image as NetworkImage).url,
      'https://images.test/new-play.jpg',
    );
    expect(find.text('재생'), findsOneWidget);
  });

  testWidgets('growth comparison shows before and after photos', (
    tester,
  ) async {
    await _pumpToPhotoLog(tester);

    await tester.tap(find.byKey(const Key('bottom_nav_home')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('open_menu_button')));
    await tester.pumpAndSettle();
    await _tapMenuItem(tester, 'menu_item_growth_comparison');

    expect(find.text('성장 비교'), findsWidgets);
    expect(find.byKey(const Key('comparison_before_image')), findsOneWidget);
    expect(find.byKey(const Key('comparison_after_image')), findsOneWidget);
    expect(find.text('식물 선택'), findsOneWidget);
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
