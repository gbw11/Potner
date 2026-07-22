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
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: Padding(
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Image.asset(
                    'assets/images/auth/potner_logo_login.png',
                    height: 110,
                    semanticLabel: 'Potner',
                  ),
                  const SizedBox(height: 34),
                  if (failure == null) ...[
                    const CircularProgressIndicator(color: AppColors.primary),
                    const SizedBox(height: 18),
                    Text(
                      '로그인 상태를 확인하고 있어요',
                      style: Theme.of(context).textTheme.bodyLarge,
                    ),
                  ] else ...[
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
    );
  }
}
