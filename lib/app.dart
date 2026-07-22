import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/router/app_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';

class PotnerApp extends ConsumerWidget {
  const PotnerApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);

    return MaterialApp.router(
      debugShowCheckedModeBanner: false,
      title: 'Potner',
      theme: AppTheme.light,
      routerConfig: router,
    );
  }
}
