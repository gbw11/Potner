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

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({this.initialEmail, super.key});

  final String? initialEmail;

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _emailFocus = FocusNode();
  final _passwordFocus = FocusNode();

  Map<String, String> _serverErrors = const {};
  String? _formError;
  bool _passwordVisible = false;

  @override
  void initState() {
    super.initState();
    _emailController.text = widget.initialEmail ?? '';
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    _emailFocus.dispose();
    _passwordFocus.dispose();
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
      title: 'Welcome Back',
      subtitle: '식물들이 기다리고 있었어요!',
      form: AutofillGroup(
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AuthTextField(
                fieldKey: const Key('login_email'),
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
                onFieldSubmitted: (_) => _passwordFocus.requestFocus(),
              ),
              const SizedBox(height: 20),
              PasswordField(
                fieldKey: const Key('login_password'),
                label: '비밀번호',
                hint: '비밀번호를 입력하세요',
                controller: _passwordController,
                focusNode: _passwordFocus,
                visible: _passwordVisible,
                onToggleVisibility: () =>
                    setState(() => _passwordVisible = !_passwordVisible),
                textInputAction: TextInputAction.done,
                autofillHints: const [AutofillHints.password],
                validator: (value) =>
                    _serverErrors['password'] ??
                    AuthValidators.loginPassword(value),
                onChanged: (_) => _clearServerError('password'),
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
              const SizedBox(height: 24),
              FilledButton(
                key: const Key('login_submit'),
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
                          Text('로그인'),
                          SizedBox(width: 10),
                          Icon(Icons.arrow_forward_rounded),
                        ],
                      ),
              ),
              const SizedBox(height: 22),
              // Row 가 아니라 Wrap 이다. 글꼴을 키우면 두 요소가 한 줄에 안 들어가는데,
              // Row 는 그때 넘치고 Wrap 은 줄을 바꾼다.
              Wrap(
                alignment: WrapAlignment.center,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    '계정이 없으신가요?',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  TextButton(
                    key: const Key('go_to_signup'),
                    onPressed: isSubmitting
                        ? null
                        : () => context.push('/signup'),
                    child: const Text(
                      '회원가입',
                      style: TextStyle(
                        color: AppColors.primary,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
      footer: const SizedBox.shrink(),
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

    final result = await ref
        .read(authControllerProvider.notifier)
        .login(
          email: _emailController.text,
          password: _passwordController.text,
        );
    if (!mounted) {
      return;
    }

    if (result.isSuccess) {
      TextInput.finishAutofillContext();
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
