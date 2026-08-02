import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/auth_validators.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/auth/presentation/widgets/form_error.dart';
import 'package:potner_app/features/auth/presentation/widgets/password_field.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';
import 'package:potner_app/features/user/data/user_repository_impl.dart';

/// 비밀번호 변경이다. 성공하면 서버가 모든 세션을 끊으므로 재로그인으로 보낸다.
class PasswordChangePage extends ConsumerStatefulWidget {
  const PasswordChangePage({super.key});

  @override
  ConsumerState<PasswordChangePage> createState() => _PasswordChangePageState();
}

class _PasswordChangePageState extends ConsumerState<PasswordChangePage> {
  final _formKey = GlobalKey<FormState>();
  final _currentController = TextEditingController();
  final _newController = TextEditingController();
  final _confirmController = TextEditingController();
  final _currentFocus = FocusNode();
  final _newFocus = FocusNode();
  final _confirmFocus = FocusNode();

  bool _currentVisible = false;
  bool _newVisible = false;
  bool _confirmVisible = false;
  bool _isSubmitting = false;
  String? _formError;

  @override
  void dispose() {
    _currentController.dispose();
    _newController.dispose();
    _confirmController.dispose();
    _currentFocus.dispose();
    _newFocus.dispose();
    _confirmFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('비밀번호 변경'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('password_change_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/my/profile'),
        ),
      ),
      body: SafeArea(
        child: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onTap: () => FocusManager.instance.primaryFocus?.unfocus(),
          child: SingleChildScrollView(
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Center(
                        child: Container(
                          width: 88,
                          height: 88,
                          alignment: Alignment.center,
                          decoration: const BoxDecoration(
                            color: Color(0xFFCCEBC0),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(
                            Icons.lock_outline_rounded,
                            size: 38,
                            color: AppColors.primary,
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),
                      const Text(
                        '소중한 정보를 보호하기 위해\n정기적으로 비밀번호를 변경해 주세요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppColors.textMuted,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 24),
                      PasswordField(
                        fieldKey: const Key('password_current'),
                        label: '현재 비밀번호',
                        hint: '현재 비밀번호를 입력하세요',
                        controller: _currentController,
                        focusNode: _currentFocus,
                        visible: _currentVisible,
                        onToggleVisibility: () =>
                            setState(() => _currentVisible = !_currentVisible),
                        textInputAction: TextInputAction.next,
                        onFieldSubmitted: (_) => _newFocus.requestFocus(),
                        validator: (value) => (value == null || value.isEmpty)
                            ? '현재 비밀번호를 입력해 주세요.'
                            : null,
                        onChanged: (_) => _clearFormError(),
                      ),
                      const SizedBox(height: 16),
                      PasswordField(
                        fieldKey: const Key('password_new'),
                        label: '새 비밀번호',
                        hint: '새 비밀번호를 입력하세요',
                        controller: _newController,
                        focusNode: _newFocus,
                        visible: _newVisible,
                        onToggleVisibility: () =>
                            setState(() => _newVisible = !_newVisible),
                        textInputAction: TextInputAction.next,
                        onFieldSubmitted: (_) => _confirmFocus.requestFocus(),
                        validator: AuthValidators.signupPassword,
                        onChanged: (_) => _clearFormError(),
                        helperText: '8자 이상, 영문과 숫자를 포함해 주세요',
                      ),
                      const SizedBox(height: 16),
                      PasswordField(
                        fieldKey: const Key('password_confirm'),
                        label: '새 비밀번호 확인',
                        hint: '비밀번호를 한번 더 입력하세요',
                        controller: _confirmController,
                        focusNode: _confirmFocus,
                        visible: _confirmVisible,
                        onToggleVisibility: () =>
                            setState(() => _confirmVisible = !_confirmVisible),
                        textInputAction: TextInputAction.done,
                        validator: (value) =>
                            AuthValidators.passwordConfirmation(
                              value,
                              _newController.text,
                            ),
                        onChanged: (_) => _clearFormError(),
                        onFieldSubmitted: (_) {
                          if (!_isSubmitting) {
                            _submit();
                          }
                        },
                      ),
                      if (_formError != null) ...[
                        const SizedBox(height: 16),
                        FormError(message: _formError!),
                      ],
                      const SizedBox(height: 26),
                      FilledButton(
                        key: const Key('password_submit'),
                        onPressed: _isSubmitting ? null : _submit,
                        child: _isSubmitting
                            ? const SizedBox.square(
                                dimension: 22,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2.4,
                                  color: Colors.white,
                                ),
                              )
                            : const Text('변경 완료'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _clearFormError() {
    if (_formError != null) {
      setState(() => _formError = null);
    }
  }

  Future<void> _submit() async {
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() => _formError = null);
    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }

    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isSubmitting = true);
    try {
      await ref
          .read(userRepositoryProvider)
          .changePassword(
            currentPassword: _currentController.text,
            newPassword: _newController.text,
          );
      if (!mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          const SnackBar(content: Text('비밀번호를 변경했어요. 다시 로그인해 주세요.')),
        );
      // 서버가 Refresh Token 을 모두 폐기했으므로 로컬 세션도 정리한다.
      await ref.read(authControllerProvider.notifier).continueWithoutSession();
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isSubmitting = false;
        _formError = plantErrorMessage(error, '비밀번호를 변경하지 못했습니다.');
      });
    }
  }
}
