import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';

/// 회원가입 직후 한 번 보여 주는 완료 화면이다.
///
/// 가입만으로는 로그인되지 않으므로 두 버튼 모두 로그인 화면으로 이어진다.
/// [email]은 로그인 화면의 이메일 칸을 미리 채우는 데 쓴다.
class SignupCompletePage extends StatelessWidget {
  const SignupCompletePage({this.email, super.key});

  final String? email;

  void _goToLogin(BuildContext context) {
    context.go('/login', extra: email);
  }

  @override
  Widget build(BuildContext context) {
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
                          onPressed: () => _goToLogin(context),
                          child: const Text('내 식물 등록하기'),
                        ),
                        const SizedBox(height: 6),
                        TextButton(
                          key: const Key('signup_complete_later'),
                          onPressed: () => _goToLogin(context),
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
