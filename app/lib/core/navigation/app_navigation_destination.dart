import 'package:flutter/material.dart';

enum AppNavigationDestination {
  home(
    label: '홈',
    path: '/',
    icon: Icons.home_outlined,
    selectedIcon: Icons.home_rounded,
  ),
  growth(
    label: '성장기록',
    path: '/growth',
    icon: Icons.local_florist_outlined,
    selectedIcon: Icons.local_florist_rounded,
  ),
  alerts(
    label: '알림',
    path: '/alerts',
    icon: Icons.notifications_none_rounded,
    selectedIcon: Icons.notifications_rounded,
  ),
  my(
    label: '마이',
    path: '/my',
    icon: Icons.person_outline_rounded,
    selectedIcon: Icons.person_rounded,
  );

  const AppNavigationDestination({
    required this.label,
    required this.path,
    required this.icon,
    required this.selectedIcon,
  });

  final String label;
  final String path;
  final IconData icon;
  final IconData selectedIcon;
}
