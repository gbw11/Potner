import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/notifications/push_notification_controller.dart';
import 'package:potner_app/core/notifications/push_notification_route.dart';
import 'package:potner_app/core/notifications/push_notification_state.dart';
import 'package:potner_app/core/router/app_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';

class PotnerApp extends ConsumerStatefulWidget {
  const PotnerApp({super.key});

  @override
  ConsumerState<PotnerApp> createState() => _PotnerAppState();
}

class _PotnerAppState extends ConsumerState<PotnerApp> {
  final _messengerKey = GlobalKey<ScaffoldMessengerState>();

  @override
  Widget build(BuildContext context) {
    final router = ref.watch(appRouterProvider);
    ref.listen<PushNotificationState>(pushNotificationControllerProvider, (
      previous,
      next,
    ) {
      if (previous?.eventSequence == next.eventSequence) {
        return;
      }

      // 알림을 눌러서 앱이 열린 경우다. 어느 쪽이 새 메시지인지는 참조가 바뀐 것으로 가른다
      // — copyWith 가 다른 쪽 값을 그대로 남기므로 null 검사만으로는 구분되지 않는다.
      final opened = next.lastOpenedMessage;
      if (opened != null && opened != previous?.lastOpenedMessage) {
        final route = resolvePushRoute(opened);
        if (route != null) {
          router.go(route);
        }
        // 화면이 열렸으면 스낵바까지 띄울 이유가 없다.
        return;
      }

      final message = next.lastForegroundMessage;
      if (message == null || message == previous?.lastForegroundMessage) {
        return;
      }
      final title = message.title?.trim();
      final body = message.body?.trim();
      final text = [
        if (title != null && title.isNotEmpty) title,
        if (body != null && body.isNotEmpty) body,
      ].join('\n');

      _messengerKey.currentState
        ?..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(text.isEmpty ? '새 알림이 도착했습니다.' : text)),
        );
    });

    return MaterialApp.router(
      debugShowCheckedModeBanner: false,
      title: 'Potner',
      theme: AppTheme.light,
      scaffoldMessengerKey: _messengerKey,
      routerConfig: router,
      // 날짜 선택기처럼 Flutter 가 문구를 직접 만드는 위젯이 있다. 이걸 붙이지 않으면
      // 한국어 화면 안에서 "Select date"·"CANCEL" 이 영어로 뜬다.
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [Locale('ko'), Locale('en')],
      locale: const Locale('ko'),
      // 글꼴 배율을 여기서 한 번에 정한다. 화면마다 흩어진 fontSize 를 건드리지 않고도 모든
      // 글자가 같은 비율로 커지므로 디자인의 크기 관계가 유지된다. 상한을 두는 이유는
      // AppTextScale 에 적어 두었다.
      builder: (context, child) {
        final media = MediaQuery.of(context);
        return MediaQuery(
          data: media.copyWith(
            textScaler: media.textScaler.clamp(
              minScaleFactor: AppTextScale.minimum,
              maxScaleFactor: AppTextScale.maximum,
            ),
          ),
          child: child!,
        );
      },
    );
  }
}
