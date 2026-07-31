import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';
import 'package:potner_app/features/user/data/user_repository_impl.dart';

/// 회원 탈퇴다. 되돌릴 수 없는 동작이므로 화면 전체가 경고 역할을 한다.
class WithdrawPage extends ConsumerStatefulWidget {
  const WithdrawPage({super.key});

  @override
  ConsumerState<WithdrawPage> createState() => _WithdrawPageState();
}

class _WithdrawPageState extends ConsumerState<WithdrawPage> {
  bool _isSubmitting = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('회원 탈퇴'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('withdraw_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/my'),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 28),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Center(
                    child: Container(
                      width: 110,
                      height: 110,
                      decoration: const BoxDecoration(
                        color: Colors.white,
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(
                        Icons.person_remove_outlined,
                        size: 52,
                        color: AppColors.error,
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  Text(
                    '정말 탈퇴 하시겠어요?',
                    textAlign: TextAlign.center,
                    style: Theme.of(
                      context,
                    ).textTheme.headlineMedium?.copyWith(color: AppColors.text),
                  ),
                  const SizedBox(height: 10),
                  const Text.rich(
                    TextSpan(
                      text: '탈퇴 버튼 선택시, 계정은 삭제되며\n',
                      children: [
                        TextSpan(
                          text: '복구되지 않습니다.',
                          style: TextStyle(
                            color: AppColors.error,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ],
                    ),
                    textAlign: TextAlign.center,
                    style: TextStyle(height: 1.5),
                  ),
                  const SizedBox(height: 24),
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(22),
                    ),
                    child: const Column(
                      children: [
                        _WarningRow(
                          icon: Icons.local_florist_outlined,
                          text: '성장 중인 모든 반려 식물 정보가 소멸됩니다.',
                        ),
                        SizedBox(height: 12),
                        _WarningRow(
                          icon: Icons.menu_book_outlined,
                          text: '과거의 소중한 성장 일지들이 영구 삭제됩니다.',
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 40),
                  FilledButton(
                    key: const Key('withdraw_confirm'),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.error,
                    ),
                    onPressed: _isSubmitting ? null : _withdraw,
                    child: _isSubmitting
                        ? const SizedBox.square(
                            dimension: 22,
                            child: CircularProgressIndicator(
                              strokeWidth: 2.4,
                              color: Colors.white,
                            ),
                          )
                        : const Text('탈퇴'),
                  ),
                  const SizedBox(height: 12),
                  FilledButton.tonal(
                    key: const Key('withdraw_cancel'),
                    onPressed: _isSubmitting
                        ? null
                        : () => returnFromSharedPage(
                            context,
                            fallbackLocation: '/my',
                          ),
                    child: const Text('취소'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _withdraw() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isSubmitting = true);
    try {
      await ref.read(userRepositoryProvider).withdraw();
      if (!mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          const SnackBar(content: Text('탈퇴가 완료되었습니다. 그동안 함께해 주셔서 감사했어요.')),
        );
      // 탈퇴 직후부터 모든 요청이 거부되므로 로컬 세션만 정리한다.
      await ref.read(authControllerProvider.notifier).continueWithoutSession();
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _isSubmitting = false);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '탈퇴를 처리하지 못했습니다.'))),
        );
    }
  }
}

class _WarningRow extends StatelessWidget {
  const _WarningRow({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 20, color: AppColors.primary),
        const SizedBox(width: 10),
        Expanded(child: Text(text, style: const TextStyle(height: 1.4))),
      ],
    );
  }
}
