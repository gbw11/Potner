class User {
  const User({
    required this.userId,
    required this.email,
    required this.nickname,
  });

  final String userId;
  final String email;
  final String nickname;

  factory User.fromJson(Map<Object?, Object?> json) {
    final userId = json['userId'];
    final email = json['email'];
    final nickname = json['nickname'];
    if (userId is! String ||
        userId.isEmpty ||
        email is! String ||
        email.isEmpty ||
        nickname is! String ||
        nickname.isEmpty) {
      throw const FormatException('Invalid user response.');
    }
    return User(userId: userId, email: email, nickname: nickname);
  }
}
