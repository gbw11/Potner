import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_problem.dart';

enum ApiExceptionKind {
  problem,
  connection,
  timeout,
  cancelled,
  invalidResponse,
  unknown,
}

class ApiException implements Exception {
  const ApiException({
    required this.kind,
    this.problem,
    this.statusCode,
    this.cause,
  });

  final ApiExceptionKind kind;
  final ApiProblem? problem;
  final int? statusCode;
  final Object? cause;

  factory ApiException.fromDio(DioException exception) {
    final nested = exception.error;
    if (nested is ApiException) {
      return nested;
    }

    switch (exception.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
        return ApiException(kind: ApiExceptionKind.timeout, cause: exception);
      case DioExceptionType.connectionError:
      case DioExceptionType.badCertificate:
        return ApiException(
          kind: ApiExceptionKind.connection,
          cause: exception,
        );
      case DioExceptionType.cancel:
        return ApiException(kind: ApiExceptionKind.cancelled, cause: exception);
      case DioExceptionType.badResponse:
        return ApiException(
          kind: ApiExceptionKind.problem,
          problem: ApiProblem.tryParse(exception.response?.data),
          statusCode: exception.response?.statusCode,
          cause: exception,
        );
      case DioExceptionType.unknown:
        return ApiException(kind: ApiExceptionKind.unknown, cause: exception);
    }
  }

  factory ApiException.invalidResponse([Object? cause]) {
    return ApiException(kind: ApiExceptionKind.invalidResponse, cause: cause);
  }

  @override
  String toString() => 'ApiException($kind, ${problem?.code})';
}
