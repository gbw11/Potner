import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final nativeArrivalSessionBridgeProvider = Provider<NativeArrivalSessionBridge>(
  (ref) {
    return const NativeArrivalSessionBridge();
  },
);

class NativeArrivalSessionBridge {
  const NativeArrivalSessionBridge();

  static const _channel = MethodChannel('potner/home_geofence');

  Future<void> clearSessionBestEffort() async {
    try {
      await _channel.invokeMethod<void>('clearSession');
    } on MissingPluginException {
      // Android 외 플랫폼과 순수 Dart 테스트에는 네이티브 지오펜스가 없다.
    } on PlatformException {
      // 세션 종료는 지오펜스 정리 실패 때문에 막히면 안 된다.
    }
  }
}
