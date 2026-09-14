class AuthTokens {
  const AuthTokens({
    required this.accessToken,
    required this.refreshToken,
    required this.tokenType,
    required this.accessTokenExpiresIn,
  });

  final String accessToken;
  final String refreshToken;
  final String tokenType;
  final int accessTokenExpiresIn;

  factory AuthTokens.fromJson(Map<Object?, Object?> json) {
    final accessToken = json['accessToken'];
    final refreshToken = json['refreshToken'];
    final tokenType = json['tokenType'];
    final expiresInValue = json['accessTokenExpiresIn'];
    final expiresIn = expiresInValue is int
        ? expiresInValue
        : int.tryParse(expiresInValue?.toString() ?? '');

    if (accessToken is! String ||
        accessToken.isEmpty ||
        refreshToken is! String ||
        refreshToken.isEmpty ||
        tokenType is! String ||
        tokenType.toLowerCase() != 'bearer' ||
        expiresIn == null ||
        expiresIn <= 0) {
      throw const FormatException('Invalid token response.');
    }

    return AuthTokens(
      accessToken: accessToken,
      refreshToken: refreshToken,
      tokenType: tokenType,
      accessTokenExpiresIn: expiresIn,
    );
  }
}
