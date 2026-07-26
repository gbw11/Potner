import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/notifications/push_notification_controller.dart';
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
      if (previous?.eventSequence == next.eventSequence ||
          next.lastForegroundMessage == null) {
        return;
      }
      final message = next.lastForegroundMessage!;
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
    );
  }
}
