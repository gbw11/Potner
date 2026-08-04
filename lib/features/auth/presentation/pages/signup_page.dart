import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/domain/auth_state.dart';
import 'package:potner_app/features/auth/presentation/auth_validators.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/auth/presentation/widgets/auth_layout.dart';
import 'package:potner_app/features/auth/presentation/widgets/auth_text_field.dart';
import 'package:potner_app/features/auth/presentation/widgets/form_error.dart';
import 'package:potner_app/features/auth/presentation/widgets/password_field.dart';

class SignupPage extends ConsumerStatefulWidget {
  const SignupPage({super.key});

  @override
  ConsumerState<SignupPage> createState() => _SignupPageState();
}

class _SignupPageState extends ConsumerState<SignupPage> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  final _nicknameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _passwordConfirmController = TextEditingController();
  final _emailFocus = FocusNode();
  final _nicknameFocus = FocusNode();
  final _passwordFocus = FocusNode();
  final _passwordConfirmFocus = FocusNode();

  Map<String, String> _serverErrors = const {};
  String? _formError;
  bool _passwordVisible = false;
  bool _passwordConfirmVisible = false;

  @override
  void dispose() {
    _emailController.dispose();
    _nicknameController.dispose();
    _passwordController.dispose();
    _passwordConfirmController.dispose();
    _emailFocus.dispose();
    _nicknameFocus.dispose();
    _passwordFocus.dispose();
    _passwordConfirmFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isSubmitting = ref.watch(
      authControllerProvider.select(
        (state) => state.status == AuthStatus.authenticating,
      ),
    );

    return AuthLayout(
      title: '회원가입',
      subtitle: 'Potner와 함께 시작하세요\n내 식물을 위한 든든한 파트너.',
      form: AutofillGroup(
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AuthTextField(
                fieldKey: const Key('signup_email'),
                label: '이메일',
                hint: '이메일을 입력하세요',
                controller: _emailController,
                focusNode: _emailFocus,
                prefixIcon: Icons.mail_outline_rounded,
                keyboardType: TextInputType.emailAddress,
                textInputAction: TextInputAction.next,
                autofillHints: const [AutofillHints.email],
                validator: (value) =>
                    _serverErrors['email'] ?? AuthValidators.email(value),
                onChanged: (_) => _clearServerError('email'),
                onFieldSubmitted: (_) => _nicknameFocus.requestFocus(),
              ),
              const SizedBox(height: 18),
              AuthTextField(
                fieldKey: const Key('signup_nickname'),
                label: '닉네임',
                hint: '닉네임을 입력하세요',
                controller: _nicknameController,
                focusNode: _nicknameFocus,
                prefixIcon: Icons.spa_outlined,
                textInputAction: TextInputAction.next,
                validator: (value) =>
                    _serverErrors['nickname'] ?? AuthValidators.nickname(value),
                onChanged: (_) => _clearServerError('nickname'),
                onFieldSubmitted: (_) => _passwordFocus.requestFocus(),
              ),
              const SizedBox(height: 18),
              PasswordField(
                fieldKey: const Key('signup_password'),
                label: '비밀번호',
                hint: '비밀번호를 입력하세요',
                controller: _passwordController,
                focusNode: _passwordFocus,
                visible: _passwordVisible,
                onToggleVisibility: () =>
                    setState(() => _passwordVisible = !_passwordVisible),
                textInputAction: TextInputAction.next,
                autofillHints: const [AutofillHints.newPassword],
                validator: (value) =>
                    _serverErrors['password'] ??
                    AuthValidators.signupPassword(value),
                onChanged: (_) {
                  _clearServerError('password');
                  if (_passwordConfirmController.text.isNotEmpty) {
                    _formKey.currentState?.validate();
                  }
                },
                onFieldSubmitted: (_) => _passwordConfirmFocus.requestFocus(),
                helperText: '8자 이상 72자 이하, 영문과 숫자를 포함해 주세요',
              ),
              const SizedBox(height: 18),
              PasswordField(
                fieldKey: const Key('signup_password_confirm'),
                label: '비밀번호 확인',
                hint: '비밀번호를 다시 입력하세요',
                controller: _passwordConfirmController,
                focusNode: _passwordConfirmFocus,
                visible: _passwordConfirmVisible,
                onToggleVisibility: () => setState(
                  () => _passwordConfirmVisible = !_passwordConfirmVisible,
                ),
                textInputAction: TextInputAction.done,
                autofillHints: const [AutofillHints.newPassword],
                validator: (value) => AuthValidators.passwordConfirmation(
                  value,
                  _passwordController.text,
                ),
                onChanged: (_) => _clearServerError('passwordConfirm'),
                onFieldSubmitted: (_) {
                  if (!isSubmitting) {
                    _submit();
                  }
                },
              ),
              if (_formError != null) ...[
                const SizedBox(height: 18),
                FormError(message: _formError!),
              ],
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 22),
                child: Row(
                  children: [
                    Expanded(child: Divider()),
                    Padding(
                      padding: EdgeInsets.symmetric(horizontal: 12),
                      child: Icon(
                        Icons.eco_outlined,
                        size: 20,
                        color: AppColors.primarySoft,
                      ),
                    ),
                    Expanded(child: Divider()),
                  ],
                ),
              ),
              FilledButton(
                key: const Key('signup_submit'),
                onPressed: isSubmitting ? null : _submit,
                child: isSubmitting
                    ? const SizedBox.square(
                        dimension: 22,
                        child: CircularProgressIndicator(
                          strokeWidth: 2.4,
                          color: Colors.white,
                        ),
                      )
                    : const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text('가입 완료'),
                          SizedBox(width: 10),
                          Icon(Icons.arrow_forward_rounded),
                        ],
                      ),
              ),
            ],
          ),
        ),
      ),
      // 로그인 화면과 같은 이유로 Wrap 이다. 글꼴을 키우면 한 줄에 안 들어간다.
      footer: Wrap(
        alignment: WrapAlignment.center,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text('이미 계정이 있으신가요?', style: Theme.of(context).textTheme.bodyMedium),
          TextButton(
            key: const Key('go_to_login'),
            onPressed: isSubmitting ? null : () => context.go('/login'),
            child: const Text(
              '로그인',
              style: TextStyle(
                color: AppColors.primary,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _clearServerError(String field) {
    if (!_serverErrors.containsKey(field) && _formError == null) {
      return;
    }
    setState(() {
      _serverErrors = Map<String, String>.from(_serverErrors)..remove(field);
      _formError = null;
    });
  }

  Future<void> _submit() async {
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _serverErrors = const {};
      _formError = null;
    });
    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }

    final email = _emailController.text.trim().toLowerCase();
    final result = await ref
        .read(authControllerProvider.notifier)
        .signup(
          email: email,
          password: _passwordController.text,
          nickname: _nicknameController.text,
        );
    if (!mounted) {
      return;
    }

    if (result.isSuccess) {
      TextInput.finishAutofillContext();
      // 가입 API는 토큰을 발급하지 않으므로 방금 계정으로 즉시 로그인한다.
      // 실패해도 완료 화면은 그대로 보여 주고, 화면이 로그인 경로로 안내한다.
      await ref
          .read(authControllerProvider.notifier)
          .login(email: email, password: _passwordController.text);
      if (!mounted) {
        return;
      }
      context.go('/signup/complete', extra: email);
      return;
    }

    final failure = result.failure!;
    setState(() {
      _serverErrors = failure.fieldErrors;
      _formError = failure.fieldErrors.isEmpty ? failure.message : null;
    });
    _formKey.currentState?.validate();
  }
}
