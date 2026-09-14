import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';

/// 회원가입 직후 한 번 보여 주는 완료 화면이다.
///
/// 가입 직후 자동 로그인까지 끝난 상태면 홈으로 이어지고, 자동 로그인이
/// 실패했다면 [email]이 미리 채워진 로그인 화면으로 안내한다.
class SignupCompletePage extends ConsumerWidget {
  const SignupCompletePage({this.email, super.key});

  final String? email;

  void _continue(BuildContext context, WidgetRef ref, {required bool toPlantRegistration}) {
    final isAuthenticated = ref.read(authControllerProvider).isAuthenticated;
    if (isAuthenticated) {
      context.go(toPlantRegistration ? '/plants/register' : '/');
      return;
    }
    context.go('/login', extra: email);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final textTheme = Theme.of(context).textTheme;

    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            return SingleChildScrollView(
              padding: EdgeInsets.symmetric(
                horizontal: constraints.maxWidth < 360 ? 14 : 24,
                vertical: 16,
              ),
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  minHeight: constraints.maxHeight - 32,
                ),
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 480),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const SizedBox(height: 48),
                        Center(
                          child: Image.asset(
                            'assets/images/auth/potner_logo_login.png',
                            height: constraints.maxWidth < 360 ? 96 : 128,
                            fit: BoxFit.contain,
                            semanticLabel: 'Potner',
                          ),
                        ),
                        const SizedBox(height: 56),
                        Text(
                          'Potner와 함께할 준비, 완료!',
                          textAlign: TextAlign.center,
                          style: textTheme.headlineMedium,
                        ),
                        const SizedBox(height: 18),
                        Text(
                          '회원가입이 완료되었어요!',
                          textAlign: TextAlign.center,
                          style: textTheme.bodyLarge?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          '이제 반려식물의 하루 상태와 활동을 한눈에 보고\n환경 설정도 직접 조절해보세요.',
                          textAlign: TextAlign.center,
                          style: textTheme.bodyMedium,
                        ),
                        const SizedBox(height: 36),
                        Text(
                          '내 식물을 등록하면 맞춤 케어를 시작할 수 있어요.',
                          textAlign: TextAlign.center,
                          style: textTheme.bodyMedium?.copyWith(
                            color: AppColors.textMuted,
                          ),
                        ),
                        const SizedBox(height: 48),
                        FilledButton(
                          key: const Key('signup_complete_register_plant'),
                          onPressed: () => _continue(
                            context,
                            ref,
                            toPlantRegistration: true,
                          ),
                          child: const Text('내 식물 등록하기'),
                        ),
                        const SizedBox(height: 6),
                        TextButton(
                          key: const Key('signup_complete_later'),
                          onPressed: () => _continue(
                            context,
                            ref,
                            toPlantRegistration: false,
                          ),
                          child: const Text(
                            '나중에 할게요',
                            style: TextStyle(
                              color: AppColors.textMuted,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
