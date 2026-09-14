import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';

class AppMenuButton extends StatelessWidget {
  const AppMenuButton({super.key});

  @override
  Widget build(BuildContext context) {
    return IconButton(
      key: const Key('open_menu_button'),
      tooltip: '전체 메뉴',
      onPressed: () => context.push('/menu'),
      icon: const Icon(Icons.menu_rounded, color: AppColors.primary),
    );
  }
}
