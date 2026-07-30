import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_shell.dart';
import 'package:potner_app/core/navigation/app_section_page.dart';
import 'package:potner_app/core/router/splash_page.dart';
import 'package:potner_app/features/device/presentation/pages/device_management_page.dart';
import 'package:potner_app/features/device/presentation/pages/device_registration_page.dart';
import 'package:potner_app/features/auth/domain/auth_state.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/auth/presentation/pages/login_page.dart';
import 'package:potner_app/features/auth/presentation/pages/signup_complete_page.dart';
import 'package:potner_app/features/auth/presentation/pages/signup_page.dart';
import 'package:potner_app/features/home/presentation/home_page.dart';
import 'package:potner_app/features/menu/presentation/pages/menu_page.dart';
import 'package:potner_app/features/plant/presentation/pages/care_settings_page.dart';
import 'package:potner_app/features/plant/presentation/pages/plant_list_page.dart';
import 'package:potner_app/features/plant/presentation/pages/plant_profile_page.dart';
import 'package:potner_app/features/plant/presentation/pages/plant_registration_page.dart';
import 'package:potner_app/features/plant/presentation/pages/registered_plants_page.dart';

final _rootNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'root');

final appRouterProvider = Provider<GoRouter>((ref) {
  late final GoRouter router;
  router = GoRouter(
    navigatorKey: _rootNavigatorKey,
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
      GoRoute(
        path: '/signup/complete',
        builder: (context, state) => SignupCompletePage(
          email: state.extra is String ? state.extra! as String : null,
        ),
      ),
      StatefulShellRoute.indexedStack(
        builder: (context, state, navigationShell) {
          return AppNavigationShell(navigationShell: navigationShell);
        },
        branches: [
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/',
                builder: (context, state) => const HomePage(),
                routes: [
                  GoRoute(
                    path: 'devices',
                    builder: (context, state) => const DeviceManagementPage(),
                    routes: [
                      GoRoute(
                        path: 'register',
                        builder: (context, state) =>
                            const DeviceRegistrationPage(),
                      ),
                    ],
                  ),
                  GoRoute(
                    path: 'plants',
                    builder: (context, state) => const PlantListPage(),
                    routes: [
                      GoRoute(
                        path: 'register',
                        builder: (context, state) =>
                            const PlantRegistrationPage(),
                      ),
                      GoRoute(
                        path: ':plantId',
                        builder: (context, state) => PlantProfilePage(
                          plantId: state.pathParameters['plantId']!,
                        ),
                        routes: [
                          GoRoute(
                            path: 'care',
                            builder: (context, state) => CareSettingsPage(
                              plantId: state.pathParameters['plantId']!,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ],
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/growth',
                builder: (context, state) => const AppSectionPage(
                  sectionKey: Key('section_page_growth'),
                  title: '성장기록',
                  icon: Icons.local_florist_outlined,
                  description: '성장 기록 화면은 다음 작업에서 연결합니다.',
                ),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/alerts',
                builder: (context, state) => const AppSectionPage(
                  sectionKey: Key('section_page_alerts'),
                  title: '알림',
                  icon: Icons.notifications_none_rounded,
                  description: '알림 내역 화면은 다음 작업에서 연결합니다.',
                ),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/my',
                builder: (context, state) => const AppSectionPage(
                  sectionKey: Key('section_page_my'),
                  title: '마이',
                  icon: Icons.person_outline_rounded,
                  description: '마이페이지는 다음 작업에서 연결합니다.',
                ),
                routes: [
                  GoRoute(
                    path: 'plants',
                    builder: (context, state) => const RegisteredPlantsPage(),
                  ),
                ],
              ),
            ],
          ),
        ],
      ),
      GoRoute(
        parentNavigatorKey: _rootNavigatorKey,
        path: '/menu',
        pageBuilder: (context, state) => CustomTransitionPage<void>(
          key: state.pageKey,
          transitionDuration: const Duration(milliseconds: 320),
          reverseTransitionDuration: const Duration(milliseconds: 240),
          child: const MenuPage(),
          transitionsBuilder: (context, animation, secondaryAnimation, child) {
            return SlideTransition(
              position:
                  Tween<Offset>(
                    begin: const Offset(-1, 0),
                    end: Offset.zero,
                  ).animate(
                    CurvedAnimation(
                      parent: animation,
                      curve: Curves.easeOutCubic,
                    ),
                  ),
              child: child,
            );
          },
        ),
      ),
    ],
    redirect: (context, state) {
      final auth = ref.read(authControllerProvider);
      final path = state.uri.path;
      final isPublic =
          path == '/login' || path == '/signup' || path == '/signup/complete';
      final requiresSplash =
          auth.status == AuthStatus.initializing ||
          (auth.status == AuthStatus.failure && auth.isRestoring);

      if (requiresSplash) {
        return path == '/splash' ? null : '/splash';
      }

      if (auth.isAuthenticated) {
        // 가입 완료 화면은 자동 로그인 뒤에도 보여 줘야 하므로 홈으로 돌리지 않는다.
        if ((isPublic && path != '/signup/complete') || path == '/splash') {
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
  return uri.path != '/login' &&
      !uri.path.startsWith('/signup') &&
      uri.path != '/splash';
}
