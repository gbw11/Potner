import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:potner_app/core/platform/native_arrival_session_bridge.dart';

final tokenStorageProvider = Provider<TokenStorage>((ref) {
  return SecureTokenStorage(
    const FlutterSecureStorage(),
    ref.watch(nativeArrivalSessionBridgeProvider),
  );
});

class StoredTokens {
  const StoredTokens({required this.accessToken, required this.refreshToken});

  final String accessToken;
  final String refreshToken;
}

abstract interface class TokenStorage {
  Future<StoredTokens?> readTokens();

  Future<void> writeTokens({
    required String accessToken,
    required String refreshToken,
  });

  Future<void> clearTokens();
}

class SecureTokenStorage implements TokenStorage {
  SecureTokenStorage(
    this._storage, [
    this._arrivalSessionBridge = const NativeArrivalSessionBridge(),
  ]);

  static const _accessTokenKey = 'potner.access_token';
  static const _refreshTokenKey = 'potner.refresh_token';

  final FlutterSecureStorage _storage;
  final NativeArrivalSessionBridge _arrivalSessionBridge;

  @override
  Future<StoredTokens?> readTokens() async {
    final values = await Future.wait([
      _storage.read(key: _accessTokenKey),
      _storage.read(key: _refreshTokenKey),
    ]);
    final accessToken = values[0];
    final refreshToken = values[1];

    if (accessToken == null ||
        accessToken.isEmpty ||
        refreshToken == null ||
        refreshToken.isEmpty) {
      if (accessToken != null || refreshToken != null) {
        await clearTokens();
      } else {
        await _arrivalSessionBridge.clearSessionBestEffort();
      }
      return null;
    }

    return StoredTokens(accessToken: accessToken, refreshToken: refreshToken);
  }

  @override
  Future<void> writeTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    try {
      await _storage.write(key: _accessTokenKey, value: accessToken);
      await _storage.write(key: _refreshTokenKey, value: refreshToken);
    } catch (_) {
      await clearTokens();
      rethrow;
    }
  }

  @override
  Future<void> clearTokens() async {
    await _arrivalSessionBridge.clearSessionBestEffort();
    await Future.wait([
      _storage.delete(key: _accessTokenKey),
      _storage.delete(key: _refreshTokenKey),
    ]);
  }
}
