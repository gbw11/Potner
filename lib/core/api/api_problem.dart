class ApiProblem {
  const ApiProblem({
    this.title,
    this.status,
    this.detail,
    this.code,
    this.timestamp,
    this.errors = const {},
  });

  final String? title;
  final int? status;
  final String? detail;
  final String? code;
  final String? timestamp;
  final Map<String, String> errors;

  static ApiProblem? tryParse(Object? data) {
    if (data is! Map) {
      return null;
    }

    final rawErrors = data['errors'];
    final errors = <String, String>{};
    if (rawErrors is Map) {
      for (final entry in rawErrors.entries) {
        if (entry.key is String && entry.value is String) {
          errors[entry.key as String] = entry.value as String;
        }
      }
    }

    return ApiProblem(
      title: _string(data['title']),
      status: _integer(data['status']),
      detail: _string(data['detail']),
      code: _string(data['code']),
      timestamp: _string(data['timestamp']),
      errors: Map.unmodifiable(errors),
    );
  }

  static String? _string(Object? value) {
    return value is String && value.trim().isNotEmpty ? value : null;
  }

  static int? _integer(Object? value) {
    if (value is int) {
      return value;
    }
    return int.tryParse(value?.toString() ?? '');
  }
}
