import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';
import 'package:potner_app/features/user/data/user_repository_impl.dart';

/// 내 정보다. 닉네임을 바로 고치고 비밀번호 변경으로 잇는다.
class ProfilePage extends ConsumerStatefulWidget {
  const ProfilePage({super.key});

  @override
  ConsumerState<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends ConsumerState<ProfilePage> {
  late final TextEditingController _nicknameController;
  bool _isSaving = false;

  @override
  void initState() {
    super.initState();
    _nicknameController = TextEditingController(
      text: ref.read(authControllerProvider).user?.nickname ?? '',
    );
  }

  @override
  void dispose() {
    _nicknameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(authControllerProvider).user;
    final plantCount = ref
        .watch(myPlantsProvider)
        .whenOrNull(data: (plants) => plants.length);

    return Scaffold(
      appBar: AppBar(
        title: const Text('내 정보'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('profile_page_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/my'),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _InfoCard(
                    label: '닉네임',
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            key: const Key('profile_nickname'),
                            controller: _nicknameController,
                            enabled: !_isSaving,
                            maxLength: 50,
                            decoration: const InputDecoration(
                              counterText: '',
                              isDense: true,
                              border: InputBorder.none,
                              enabledBorder: InputBorder.none,
                              focusedBorder: InputBorder.none,
                              filled: false,
                              contentPadding: EdgeInsets.zero,
                            ),
                            style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                        FilledButton.tonal(
                          key: const Key('profile_nickname_save'),
                          // 테마가 버튼을 전폭으로 잡으므로 Row 안에서는 크기를 좁힌다.
                          style: FilledButton.styleFrom(
                            minimumSize: const Size(64, 40),
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                          ),
                          onPressed: _isSaving ? null : _saveNickname,
                          child: _isSaving
                              ? const SizedBox.square(
                                  dimension: 16,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Text('수정'),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 14),
                  _InfoCard(
                    label: '이메일',
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            user?.email ?? '',
                            style: const TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        const Icon(
                          Icons.mail_outline_rounded,
                          color: AppColors.textMuted,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 14),
                  Material(
                    color: AppColors.surface,
                    borderRadius: BorderRadius.circular(20),
                    clipBehavior: Clip.antiAlias,
                    elevation: 1,
                    shadowColor: AppColors.primary.withValues(alpha: 0.08),
                    child: InkWell(
                      key: const Key('profile_plant_count'),
                      onTap: () => context.go(
                        withPreviousNavigationLocation(context, '/my/plants'),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.all(18),
                        child: Row(
                          children: [
                            Container(
                              width: 46,
                              height: 46,
                              decoration: BoxDecoration(
                                color: const Color(0xFFCCEBC0),
                                borderRadius: BorderRadius.circular(14),
                              ),
                              child: const Icon(
                                Icons.local_florist_outlined,
                                color: AppColors.primary,
                              ),
                            ),
                            const SizedBox(width: 14),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text(
                                    '등록 식물 개수',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: AppColors.textMuted,
                                    ),
                                  ),
                                  Text(
                                    plantCount == null ? '-' : '$plantCount 개',
                                    style: const TextStyle(
                                      fontSize: 20,
                                      fontWeight: FontWeight.w800,
                                      color: AppColors.text,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const Icon(
                              Icons.chevron_right_rounded,
                              color: AppColors.textMuted,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 26),
                  FilledButton(
                    key: const Key('profile_change_password'),
                    onPressed: () => context.go(
                      withPreviousNavigationLocation(context, '/my/password'),
                    ),
                    child: const Text('비밀번호 변경'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _saveNickname() async {
    final messenger = ScaffoldMessenger.of(context);
    final nickname = _nicknameController.text.trim();
    final current = ref.read(authControllerProvider).user?.nickname;
    if (nickname.isEmpty) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('닉네임을 입력해 주세요.')));
      return;
    }
    if (nickname == current) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('변경된 내용이 없어요.')));
      return;
    }

    setState(() => _isSaving = true);
    try {
      final updated = await ref
          .read(userRepositoryProvider)
          .changeNickname(nickname);
      if (!mounted) {
        return;
      }
      ref.read(authControllerProvider.notifier).updateUser(updated);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('닉네임을 변경했어요.')));
    } catch (error) {
      if (!mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '닉네임을 변경하지 못했습니다.'))),
        );
    } finally {
      if (mounted) {
        setState(() => _isSaving = false);
      }
    }
  }
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({required this.label, required this.child});

  final String label;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(18, 14, 18, 14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: AppColors.primary.withValues(alpha: 0.08),
            blurRadius: 14,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: const TextStyle(fontSize: 12, color: AppColors.textMuted),
          ),
          const SizedBox(height: 4),
          child,
        ],
      ),
    );
  }
}
