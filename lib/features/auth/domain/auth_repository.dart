import 'package:potner_app/features/auth/domain/user.dart';

abstract interface class AuthRepository {
  Future<User?> restoreSession();

  Future<User> signup({
    required String email,
    required String password,
    required String nickname,
  });

  Future<User> login({required String email, required String password});

  Future<void> logout();

  Future<void> clearSession();
}
