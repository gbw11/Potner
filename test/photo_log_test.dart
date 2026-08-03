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

  testWidgets('타임랩스는 오래된 사진부터 재생하고 재생용 축소본을 쓴다', (tester) async {
    await _pumpToPhotoLog(tester);

    await tester.tap(find.byKey(const Key('photo_log_timelapse')));
    await tester.pumpAndSettle();

    // 서버가 오래된 순으로 내려주므로 첫 프레임은 가장 오래된 사진이다.
    Image frame() => tester.widget<Image>(find.byKey(const Key('timelapse_frame')));
    expect(
      (frame().image as NetworkImage).url,
      'https://images.test/old-play.jpg',
    );
    expect(find.text('1 / 2일차'), findsOneWidget);

    // 원본이 아니라 playbackUrl 을 쓴다. 원본은 수 MB 라 연속 재생에서 끊긴다.
    expect((frame().image as NetworkImage).url, isNot(contains('old.jpg')));

    // 기본 배속에서 한 프레임은 800ms 다.
    await tester.pump(const Duration(milliseconds: 900));
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
