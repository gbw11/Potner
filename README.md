# potner_app

Potner Flutter mobile application.

## Local Android run

```bash
flutter run \
  --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
```

The Android application ID is `com.potner.app`. Firebase Cloud Messaging setup,
the frontend lifecycle, and the backend API contract are documented in
[`docs/FCM.md`](docs/FCM.md).

## Verification

```bash
flutter analyze
flutter test
flutter build apk --debug
```
