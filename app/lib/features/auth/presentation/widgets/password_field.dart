import 'package:flutter/material.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/widgets/auth_text_field.dart';

class PasswordField extends StatelessWidget {
  const PasswordField({
    required this.label,
    required this.hint,
    required this.controller,
    required this.focusNode,
    required this.visible,
    required this.onToggleVisibility,
    this.fieldKey,
    this.textInputAction,
    this.autofillHints,
    this.validator,
    this.onChanged,
    this.onFieldSubmitted,
    this.helperText,
    super.key,
  });

  final Key? fieldKey;
  final String label;
  final String hint;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool visible;
  final VoidCallback onToggleVisibility;
  final TextInputAction? textInputAction;
  final Iterable<String>? autofillHints;
  final String? Function(String?)? validator;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onFieldSubmitted;
  final String? helperText;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AuthTextField(
          fieldKey: fieldKey,
          label: label,
          hint: hint,
          controller: controller,
          focusNode: focusNode,
          prefixIcon: Icons.lock_outline_rounded,
          textInputAction: textInputAction,
          autofillHints: autofillHints,
          obscureText: !visible,
          suffixIcon: IconButton(
            tooltip: visible ? '비밀번호 숨기기' : '비밀번호 표시',
            onPressed: onToggleVisibility,
            icon: Icon(
              visible
                  ? Icons.visibility_outlined
                  : Icons.visibility_off_outlined,
            ),
          ),
          validator: validator,
          onChanged: onChanged,
          onFieldSubmitted: onFieldSubmitted,
        ),
        if (helperText != null)
          Padding(
            padding: const EdgeInsets.only(top: 8, left: 2),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(
                  Icons.info_outline_rounded,
                  size: 17,
                  color: AppColors.textMuted,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    helperText!,
                    style: Theme.of(
                      context,
                    ).textTheme.bodySmall?.copyWith(color: AppColors.textMuted),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}
