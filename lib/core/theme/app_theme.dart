import 'package:flutter/material.dart';

abstract final class AppColors {
  static const primary = Color(0xFF334F2B);
  static const primaryContainer = Color(0xFF4A6741);
  static const primarySoft = Color(0xFFAFD0A1);
  static const background = Color(0xFFFAF9F4);
  static const surfaceLow = Color(0xFFF5F4EF);
  static const surface = Colors.white;
  static const text = Color(0xFF1B1C19);
  static const textMuted = Color(0xFF5F675C);
  static const outline = Color(0xFFC3C8BD);
  static const error = Color(0xFFBA1A1A);
}

/// 앱 전체 글꼴 배율이다.
///
/// 개별 `fontSize` 를 하나하나 올리지 않고 배율로 처리한다. 글자 크기를 지정한 곳이 화면마다
/// 흩어져 있어 일부만 올리면 크기 관계가 깨지고, 배율은 지정 방식과 무관하게 모든 글자에
/// 똑같이 적용되므로 디자인의 크기 비율이 그대로 유지된다.
///
/// [minimum] 을 1.0 보다 크게 둬서 OS 설정이 기본이어도 앱은 그보다 크게 그린다. [maximum] 은
/// 사용자가 접근성 설정으로 글꼴을 아무리 키워도 이 값에서 멈춘다는 뜻이다 — 제한이 없으면
/// 고정 높이 안의 글자가 넘쳐 "BOTTOM OVERFLOWED BY n PIXELS" 가 뜬다.
///
/// [maximum] 까지 화면이 깨지지 않는 것은 `test/text_scale_test.dart` 가 지킨다. 이 값을
/// 올리려면 그 테스트를 먼저 통과시켜야 한다.
abstract final class AppTextScale {
  static const minimum = 1.1;
  static const maximum = 1.3;
}

abstract final class AppTheme {
  static ThemeData get light {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: AppColors.primary,
      brightness: Brightness.light,
      primary: AppColors.primary,
      surface: AppColors.background,
      error: AppColors.error,
    );

    final base = ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: AppColors.background,
      fontFamilyFallback: const ['Noto Sans KR', 'sans-serif'],
    );

    return base.copyWith(
      textTheme: base.textTheme.copyWith(
        headlineLarge: base.textTheme.headlineLarge?.copyWith(
          color: AppColors.primary,
          fontSize: 32,
          height: 1.18,
          fontWeight: FontWeight.w800,
          letterSpacing: -0.8,
        ),
        headlineMedium: base.textTheme.headlineMedium?.copyWith(
          color: AppColors.primary,
          fontSize: 24,
          height: 1.25,
          fontWeight: FontWeight.w700,
        ),
        bodyLarge: base.textTheme.bodyLarge?.copyWith(
          color: AppColors.text,
          fontSize: 16,
          height: 1.5,
        ),
        bodyMedium: base.textTheme.bodyMedium?.copyWith(
          color: AppColors.textMuted,
          fontSize: 14,
          height: 1.45,
        ),
        labelLarge: base.textTheme.labelLarge?.copyWith(
          fontSize: 16,
          fontWeight: FontWeight.w700,
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.surface,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 17,
        ),
        hintStyle: const TextStyle(color: Color(0xFF8A9187)),
        prefixIconColor: AppColors.primary,
        suffixIconColor: AppColors.textMuted,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.outline),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.outline),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.error, width: 1.5),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.primaryContainer,
          foregroundColor: Colors.white,
          disabledBackgroundColor: AppColors.outline,
          minimumSize: const Size.fromHeight(56),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
        ),
      ),
      snackBarTheme: const SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
