const apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.2.2:8080/api/v1',
);

String get normalizedApiBaseUrl {
  final value = apiBaseUrl.trim();
  if (value.isEmpty) {
    throw StateError('API_BASE_URL must not be empty.');
  }
  return value.endsWith('/') ? value : '$value/';
}
