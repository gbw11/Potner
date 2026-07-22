import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/router/splash_page.dart';
import 'package:potner_app/features/auth/domain/auth_state.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/auth/presentation/pages/login_page.dart';
import 'package:potner_app/features/auth/presentation/pages/signup_page.dart';
import 'package:potner_app/features/home/presentation/home_page.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  late final GoRouter router;
  router = GoRouter(
    initialLocation: '/splash',
    routes: [
      GoRoute(path: '/splash', builder: (context, state) => const SplashPage()),
      GoRoute(
        path: '/login',
        builder: (context, state) => LoginPage(
          initialEmail: state.extra is String ? state.extra! as String : null,
        ),
      ),
      GoRoute(path: '/signup', builder: (context, state) => const SignupPage()),
      GoRoute(path: '/', builder: (context, state) => const HomePage()),
    ],
    redirect: (context, state) {
      final auth = ref.read(authControllerProvider);
      final path = state.uri.path;
      final isPublic = path == '/login' || path == '/signup';
      final requiresSplash =
          auth.status == AuthStatus.initializing ||
          (auth.status == AuthStatus.failure && auth.isRestoring);

      if (requiresSplash) {
        return path == '/splash' ? null : '/splash';
      }

      if (auth.isAuthenticated) {
        if (isPublic || path == '/splash') {
          final from = state.uri.queryParameters['from'];
          if (_isSafeDestination(from)) {
            return from;
          }
          return '/';
        }
        return null;
      }

      if (auth.status == AuthStatus.authenticating) {
        return null;
      }

      if (isPublic) {
        return null;
      }

      return Uri(
        path: '/login',
        queryParameters: {'from': state.uri.toString()},
      ).toString();
    },
    errorBuilder: (context, state) => Scaffold(
      body: Center(
        child: TextButton(
          onPressed: () => context.go('/'),
          child: const Text('화면을 찾을 수 없습니다. 홈으로 이동'),
        ),
      ),
    ),
  );

  ref.listen<AuthState>(authControllerProvider, (_, _) => router.refresh());
  ref.onDispose(router.dispose);
  return router;
});

bool _isSafeDestination(String? location) {
  if (location == null || !location.startsWith('/')) {
    return false;
  }
  final uri = Uri.tryParse(location);
  if (uri == null) {
    return false;
  }
  return uri.path != '/login' && uri.path != '/signup' && uri.path != '/splash';
}
