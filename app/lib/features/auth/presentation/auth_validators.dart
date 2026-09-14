abstract final class AuthValidators {
  static final RegExp _emailPattern = RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$');
  static final RegExp _letterPattern = RegExp(r'[A-Za-z]');
  static final RegExp _numberPattern = RegExp(r'\d');
  static final RegExp _whitespacePattern = RegExp(r'\s');

  static String? email(String? value) {
    final email = value?.trim() ?? '';
    if (email.isEmpty) {
      return '이메일을 입력해 주세요.';
    }
    if (email.length > 255) {
      return '이메일은 255자 이하여야 합니다.';
    }
    if (!_emailPattern.hasMatch(email)) {
      return '올바른 이메일 형식으로 입력해 주세요.';
    }
    return null;
  }

  static String? loginPassword(String? value) {
    final password = value ?? '';
    if (password.isEmpty) {
      return '비밀번호를 입력해 주세요.';
    }
    if (password.length > 72) {
      return '비밀번호는 72자 이하여야 합니다.';
    }
    return null;
  }

  static String? signupPassword(String? value) {
    final password = value ?? '';
    if (password.isEmpty) {
      return '비밀번호를 입력해 주세요.';
    }
    if (password.length < 8 || password.length > 72) {
      return '비밀번호는 8자 이상 72자 이하여야 합니다.';
    }
    if (!_letterPattern.hasMatch(password) ||
        !_numberPattern.hasMatch(password)) {
      return '영문과 숫자를 하나 이상 포함해 주세요.';
    }
    if (_whitespacePattern.hasMatch(password)) {
      return '비밀번호에는 공백을 사용할 수 없습니다.';
    }
    return null;
  }

  static String? nickname(String? value) {
    final nickname = value?.trim() ?? '';
    if (nickname.isEmpty) {
      return '닉네임을 입력해 주세요.';
    }
    if (nickname.length > 50) {
      return '닉네임은 50자 이하여야 합니다.';
    }
    return null;
  }

  static String? passwordConfirmation(String? value, String password) {
    if ((value ?? '').isEmpty) {
      return '비밀번호를 다시 입력해 주세요.';
    }
    if (value != password) {
      return '비밀번호가 일치하지 않습니다.';
    }
    return null;
  }
}
