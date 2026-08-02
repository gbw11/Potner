import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/storage/token_storage.dart';
import 'package:potner_app/core/platform/native_arrival_session_bridge.dart';
import 'package:potner_app/features/auth/data/auth_api.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/user.dart';

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return AuthRepositoryImpl(
    ref.watch(authApiProvider),
    ref.watch(tokenStorageProvider),
    ref.watch(nativeArrivalSessionBridgeProvider),
  );
});

class AuthRepositoryImpl implements AuthRepository {
  AuthRepositoryImpl(
    this._authApi,
    this._tokenStorage, [
    this._arrivalSessionBridge = const NativeArrivalSessionBridge(),
  ]);

  final AuthApi _authApi;
  final TokenStorage _tokenStorage;
  final NativeArrivalSessionBridge _arrivalSessionBridge;

  @override
  Future<User?> restoreSession() async {
    final tokens = await _tokenStorage.readTokens();
    if (tokens == null) {
      return null;
    }
    return _authApi.getCurrentUser();
  }

  @override
  Future<User> signup({
    required String email,
    required String password,
    required String nickname,
  }) {
    return _authApi.signup(
      email: email,
      password: password,
      nickname: nickname,
    );
  }

  @override
  Future<User> login({required String email, required String password}) async {
    final tokens = await _authApi.login(email: email, password: password);
    try {
      await _tokenStorage.writeTokens(
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
      );
      return await _authApi.getCurrentUser();
    } catch (_) {
      await _tokenStorage.clearTokens();
      rethrow;
    }
  }

  @override
  Future<void> logout() async {
    try {
      await _arrivalSessionBridge.clearSessionBestEffort();
      // Native arrival cleanup may refresh an expired access token while sending CANCEL.
      // Re-read the pair so logout revokes the latest rotated refresh token.
      final tokens = await _tokenStorage.readTokens();
      if (tokens != null) {
        await _authApi.logout(tokens.refreshToken);
      }
    } finally {
      await _tokenStorage.clearTokens();
    }
  }

  @override
  Future<void> clearSession() {
    return _tokenStorage.clearTokens();
  }
}
