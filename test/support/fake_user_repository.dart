import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/user/domain/user_repository.dart';

class FakeUserRepository implements UserRepository {
  final List<String> nicknameChanges = [];
  final List<({String current, String next})> passwordChanges = [];
  int withdrawCalls = 0;
  Object? changePasswordError;

  @override
  Future<User> changeNickname(String nickname) async {
    nicknameChanges.add(nickname);
    return User(
      userId: 'user-id',
      email: 'member@example.com',
      nickname: nickname,
    );
  }

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    final error = changePasswordError;
    if (error != null) {
      throw error;
    }
    passwordChanges.add((current: currentPassword, next: newPassword));
  }

  @override
  Future<void> withdraw() async {
    withdrawCalls += 1;
  }
}
