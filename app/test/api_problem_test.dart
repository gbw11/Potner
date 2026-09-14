import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/core/api/api_problem.dart';

void main() {
  test('ApiProblem parses field errors', () {
    final problem = ApiProblem.tryParse({
      'title': 'Invalid request',
      'status': 400,
      'detail': '요청값이 유효하지 않습니다.',
      'code': 'INVALID_REQUEST',
      'errors': {'email': '올바른 이메일 형식이어야 합니다.'},
    });

    expect(problem?.code, 'INVALID_REQUEST');
    expect(problem?.errors['email'], '올바른 이메일 형식이어야 합니다.');
  });

  test('ApiProblem accepts a response without errors', () {
    final problem = ApiProblem.tryParse({
      'title': 'Login failed',
      'status': 401,
      'code': 'LOGIN_FAILED',
    });

    expect(problem?.errors, isEmpty);
    expect(ApiProblem.tryParse('not-json-object'), isNull);
  });
}
