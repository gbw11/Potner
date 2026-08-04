import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/domain/auth_state.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';

class SplashPage extends ConsumerWidget {
  const SplashPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authControllerProvider);
    final failure = authState.status == AuthStatus.failure
        ? authState.failure
        : null;

    return Scaffold(
      body: Stack(
        children: [
          // 디자인의 모서리 잎 장식. 좌하단은 같은 이미지를 뒤집어 쓴다.
          Positioned(
            top: -16,
            right: -20,
            child: IgnorePointer(
              child: Opacity(
                opacity: 0.2,
                child: Image.asset(
                  'assets/images/auth/botanical_accent.png',
                  width: 190,
                  fit: BoxFit.contain,
                ),
              ),
            ),
          ),
          Positioned(
            bottom: -16,
            left: -20,
            child: IgnorePointer(
              child: Opacity(
                opacity: 0.16,
                child: Transform.rotate(
                  angle: 3.14159,
                  child: Image.asset(
                    'assets/images/auth/botanical_accent.png',
                    width: 210,
                    fit: BoxFit.contain,
                  ),
                ),
              ),
            ),
          ),
          SafeArea(
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 360),
                child: Padding(
                  padding: const EdgeInsets.all(28),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      TweenAnimationBuilder<double>(
                        tween: Tween(begin: 0, end: 1),
                        duration: const Duration(milliseconds: 700),
                        curve: Curves.easeOut,
                        builder: (context, value, child) => Opacity(
                          opacity: value,
                          child: Transform.translate(
                            offset: Offset(0, 12 * (1 - value)),
                            child: child,
                          ),
                        ),
                        child: Column(
                          children: [
                            Image.asset(
                              'assets/images/auth/potner_logo_login.png',
                              height: 150,
                              semanticLabel: 'Potner',
                            ),
                            const SizedBox(height: 18),
                            const Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  Icons.eco_outlined,
                                  size: 14,
                                  color: AppColors.primarySoft,
                                ),
                                SizedBox(width: 8),
                                // Flexible 이라야 글꼴을 키웠을 때 줄을 바꾼다. 없으면
                                // 자연 너비를 그대로 요구해 304px 안에서 넘친다.
                                Flexible(
                                  child: Text(
                                    '반려식물의 든든한 파트너',
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      color: AppColors.textMuted,
                                      fontWeight: FontWeight.w600,
                                      letterSpacing: 2,
                                    ),
                                  ),
                                ),
                                SizedBox(width: 8),
                                Icon(
                                  Icons.eco_outlined,
                                  size: 14,
                                  color: AppColors.primarySoft,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 40),
                      if (failure == null)
                        const SizedBox.square(
                          dimension: 26,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.6,
                            color: AppColors.primarySoft,
                          ),
                        )
                      else ...[
                        const Icon(
                          Icons.cloud_off_outlined,
                          size: 42,
                          color: AppColors.textMuted,
                        ),
                        const SizedBox(height: 14),
                        Text(
                          failure.message,
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyLarge,
                        ),
                        const SizedBox(height: 22),
                        FilledButton(
                          onPressed: () => ref
                              .read(authControllerProvider.notifier)
                              .restoreSession(),
                          child: const Text('다시 시도'),
                        ),
                        const SizedBox(height: 8),
                        TextButton(
                          onPressed: () => ref
                              .read(authControllerProvider.notifier)
                              .continueWithoutSession(),
                          child: const Text('저장된 로그인 정보 지우기'),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
