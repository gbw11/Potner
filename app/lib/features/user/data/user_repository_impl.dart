import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/user/data/user_api.dart';
import 'package:potner_app/features/user/domain/user_repository.dart';

final userRepositoryProvider = Provider<UserRepository>((ref) {
  return UserRepositoryImpl(UserApi(ref.watch(apiClientProvider).dio));
});

class UserRepositoryImpl implements UserRepository {
  UserRepositoryImpl(this._api);

  final UserApi _api;

  @override
  Future<User> changeNickname(String nickname) {
    return _api.changeNickname(nickname);
  }

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) {
    return _api.changePassword(
      currentPassword: currentPassword,
      newPassword: newPassword,
    );
  }

  @override
  Future<void> withdraw() {
    return _api.withdraw();
  }

  @override
  Future<NotificationSettings> getNotificationSettings() {
    return _api.getNotificationSettings();
  }

  @override
  Future<NotificationSettings> updateNotificationSettings({
    bool? allEnabled,
    bool? pushEnabled,
    bool? plantCareEnabled,
    bool? marketingEnabled,
  }) {
    return _api.updateNotificationSettings(
      allEnabled: allEnabled,
      pushEnabled: pushEnabled,
      plantCareEnabled: plantCareEnabled,
      marketingEnabled: marketingEnabled,
    );
  }
}
