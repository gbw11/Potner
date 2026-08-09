import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_shell.dart';
import 'package:potner_app/core/router/splash_page.dart';
import 'package:potner_app/features/device/presentation/pages/device_management_page.dart';
import 'package:potner_app/features/device/presentation/pages/device_registration_page.dart';
import 'package:potner_app/features/device/presentation/pages/robot_locations_page.dart';
import 'package:potner_app/features/command/presentation/pages/robot_drive_page.dart';
import 'package:potner_app/features/conversation/presentation/pages/conversation_page.dart';
import 'package:potner_app/features/arrival/presentation/arrival_setting_page.dart';
import 'package:potner_app/features/alert/presentation/pages/alerts_page.dart';
import 'package:potner_app/features/bloom/presentation/pages/bloom_list_page.dart';
import 'package:potner_app/features/diary/presentation/pages/diary_calendar_page.dart';
import 'package:potner_app/features/diary/presentation/pages/diary_detail_page.dart';
import 'package:potner_app/features/growth/presentation/pages/growth_hub_page.dart';
import 'package:potner_app/features/photo/presentation/pages/growth_comparison_page.dart';
import 'package:potner_app/features/photo/presentation/pages/photo_detail_page.dart';
import 'package:potner_app/core/notifications/push_notification_route.dart';
import 'package:potner_app/features/photo/presentation/pages/photo_log_page.dart';
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
import 'package:potner_app/features/plant/presentation/pages/plant_selection_page.dart';
import 'package:potner_app/features/plant/presentation/pages/registered_plants_page.dart';
import 'package:potner_app/features/plant/presentation/pages/repotting_guide_page.dart';
import 'package:potner_app/features/sensor/presentation/pages/environment_dashboard_page.dart';
import 'package:potner_app/features/user/presentation/pages/my_page.dart';
import 'package:potner_app/features/user/presentation/pages/notification_settings_page.dart';
import 'package:potner_app/features/user/presentation/pages/password_change_page.dart';
import 'package:potner_app/features/user/presentation/pages/profile_page.dart';
import 'package:potner_app/features/user/presentation/pages/withdraw_page.dart';

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
                      GoRoute(
                        path: 'drive',
                        builder: (context, state) => const RobotDrivePage(),
                      ),
                      GoRoute(
                        path: 'conversations',
                        builder: (context, state) => const ConversationPage(),
                      ),
                      GoRoute(
                        path: ':robotId/locations',
                        builder: (context, state) => RobotLocationsPage(
                          robotId: state.pathParameters['robotId']!,
                        ),
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
                          GoRoute(
                            path: 'environment',
                            builder: (context, state) =>
                                EnvironmentDashboardPage(
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
                builder: (context, state) => const GrowthHubPage(),
                routes: [
                  GoRoute(
                    path: 'blooms',
                    builder: (context, state) => const BloomListPage(),
                  ),
                  GoRoute(
                    path: 'diary',
                    builder: (context, state) => const DiaryCalendarPage(),
                    routes: [
                      GoRoute(
                        path: ':plantId/:diaryId',
                        builder: (context, state) => DiaryDetailPage(
                          plantId: state.pathParameters['plantId']!,
                          diaryId: state.pathParameters['diaryId']!,
                        ),
                      ),
                    ],
                  ),
                  GoRoute(
                    path: 'photos',
                    // 개화 알림이 그날 사진을 열려고 date 질의를 붙여 온다
                    // (push_notification_route.dart). 값이 없으면 전체 목록이다.
                    builder: (context, state) => PhotoLogPage(
                      initialDate: state
                          .uri
                          .queryParameters[photoLogDateQueryParameter],
                    ),
                    routes: [
                      GoRoute(
                        path: ':plantId/:photoId',
                        builder: (context, state) => PhotoDetailPage(
                          plantId: state.pathParameters['plantId']!,
                          photoId: state.pathParameters['photoId']!,
                        ),
                      ),
                    ],
                  ),
                  GoRoute(
                    path: 'compare',
                    builder: (context, state) => const GrowthComparisonPage(),
                  ),
                ],
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/alerts',
                builder: (context, state) => const AlertsPage(),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/my',
                builder: (context, state) => const MyPage(),
                routes: [
                  GoRoute(
                    path: 'plants',
                    builder: (context, state) => const RegisteredPlantsPage(),
                  ),
                  GoRoute(
                    path: 'profile',
                    builder: (context, state) => const ProfilePage(),
                  ),
                  GoRoute(
                    path: 'password',
                    builder: (context, state) => const PasswordChangePage(),
                  ),
                  GoRoute(
                    path: 'withdraw',
                    builder: (context, state) => const WithdrawPage(),
                  ),
                  GoRoute(
                    path: 'notifications',
                    builder: (context, state) =>
                        const NotificationSettingsPage(),
                  ),
                  GoRoute(
                    path: 'arrival',
                    builder: (context, state) => const ArrivalSettingPage(),
                  ),
                ],
              ),
            ],
          ),
        ],
      ),
      // 분갈이 시기 푸시(data.route)가 가리키는 경로다. 탭 밖에 두는 이유는 이 화면이 특정
      // 식물에 매이지 않는 일반 안내라 탭 어디에서 들어와도 같기 때문이다.
      GoRoute(
        parentNavigatorKey: _rootNavigatorKey,
        path: '/repotting',
        builder: (context, state) => const RepottingGuidePage(),
      ),
      GoRoute(
        parentNavigatorKey: _rootNavigatorKey,
        path: '/plant-selection/care',
        builder: (context, state) => const PlantSelectionPage(
          destination: PlantSelectionDestination.care,
        ),
      ),
      GoRoute(
        parentNavigatorKey: _rootNavigatorKey,
        path: '/plant-selection/environment',
        builder: (context, state) => const PlantSelectionPage(
          destination: PlantSelectionDestination.environment,
        ),
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
