import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/auth/presentation/auth_validators.dart';

void main() {
  group('AuthValidators', () {
    test('accepts a valid signup password', () {
      expect(AuthValidators.signupPassword('password1'), isNull);
    });

    test('rejects passwords without a number or with whitespace', () {
      expect(AuthValidators.signupPassword('onlyletters'), isNotNull);
      expect(AuthValidators.signupPassword('password 1'), isNotNull);
    });

    test('normal email rules reject malformed and oversized values', () {
      expect(AuthValidators.email('member@example.com'), isNull);
      expect(AuthValidators.email('not-an-email'), isNotNull);
      final oversized = '${List.filled(250, 'a').join()}@test.com';
      expect(AuthValidators.email(oversized), isNotNull);
    });
  });
}
