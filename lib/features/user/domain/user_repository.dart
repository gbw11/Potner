import 'package:potner_app/features/auth/domain/user.dart';

abstract class UserRepository {
  Future<User> changeNickname(String nickname);

  /// 성공하면 서버가 모든 Refresh Token 을 폐기하므로 재로그인이 필요하다.
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  });

  /// 계정을 탈퇴 상태로 바꾼다. 직후부터 모든 요청이 거부된다.
  Future<void> withdraw();
}
