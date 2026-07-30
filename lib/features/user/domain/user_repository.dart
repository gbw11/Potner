import 'package:potner_app/features/auth/domain/user.dart';

/// 사용자 알림 수신 설정이다.
class NotificationSettings {
  const NotificationSettings({
    required this.allEnabled,
    required this.pushEnabled,
    required this.plantCareEnabled,
    required this.marketingEnabled,
  });

  /// 마스터 스위치다. 꺼지면 세부 설정과 무관하게 발송되지 않는다.
  final bool allEnabled;
  final bool pushEnabled;
  final bool plantCareEnabled;

  /// 회원가입의 선택 동의 항목이라 기본이 꺼짐이다.
  final bool marketingEnabled;
}

abstract class UserRepository {
  Future<User> changeNickname(String nickname);

  /// 성공하면 서버가 모든 Refresh Token 을 폐기하므로 재로그인이 필요하다.
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  });

  /// 계정을 탈퇴 상태로 바꾼다. 직후부터 모든 요청이 거부된다.
  Future<void> withdraw();

  Future<NotificationSettings> getNotificationSettings();

  /// null 인 항목은 보내지 않아 기존 값을 유지한다. 변경 후 전체 설정을 돌려준다.
  Future<NotificationSettings> updateNotificationSettings({
    bool? allEnabled,
    bool? pushEnabled,
    bool? plantCareEnabled,
    bool? marketingEnabled,
  });
}
