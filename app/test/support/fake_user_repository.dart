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

  NotificationSettings settings = const NotificationSettings(
    allEnabled: true,
    pushEnabled: true,
    plantCareEnabled: true,
    marketingEnabled: false,
  );
  final List<Map<String, bool?>> settingUpdates = [];

  @override
  Future<NotificationSettings> getNotificationSettings() async => settings;

  @override
  Future<NotificationSettings> updateNotificationSettings({
    bool? allEnabled,
    bool? pushEnabled,
    bool? plantCareEnabled,
    bool? marketingEnabled,
  }) async {
    settingUpdates.add({
      'allEnabled': allEnabled,
      'pushEnabled': pushEnabled,
      'plantCareEnabled': plantCareEnabled,
      'marketingEnabled': marketingEnabled,
    });
    settings = NotificationSettings(
      allEnabled: allEnabled ?? settings.allEnabled,
      pushEnabled: pushEnabled ?? settings.pushEnabled,
      plantCareEnabled: plantCareEnabled ?? settings.plantCareEnabled,
      marketingEnabled: marketingEnabled ?? settings.marketingEnabled,
    );
    return settings;
  }
}
