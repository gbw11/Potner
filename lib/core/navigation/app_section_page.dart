import 'package:flutter/material.dart';
import 'package:potner_app/core/navigation/app_menu_button.dart';
import 'package:potner_app/core/theme/app_theme.dart';

class AppSectionPage extends StatelessWidget {
  const AppSectionPage({
    required this.sectionKey,
    required this.title,
    required this.icon,
    required this.description,
    super.key,
  });

  final Key sectionKey;
  final String title;
  final IconData icon;
  final String description;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: sectionKey,
      appBar: AppBar(
        automaticallyImplyLeading: false,
        title: Text(title),
        actions: const [AppMenuButton(), SizedBox(width: 8)],
      ),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 88,
                  height: 88,
                  decoration: const BoxDecoration(
                    color: Color(0xFFCCEBC0),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(icon, size: 42, color: AppColors.primary),
                ),
                const SizedBox(height: 24),
                Text(title, style: Theme.of(context).textTheme.headlineMedium),
                const SizedBox(height: 10),
                Text(
                  description,
                  textAlign: TextAlign.center,
                  style: Theme.of(
                    context,
                  ).textTheme.bodyLarge?.copyWith(color: AppColors.textMuted),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
