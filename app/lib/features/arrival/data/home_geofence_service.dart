import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/config/app_config.dart';
import 'package:potner_app/features/arrival/domain/home_geofence_state.dart';

final homeGeofenceServiceProvider = Provider<HomeGeofenceService>((ref) {
  return const HomeGeofenceService();
});

class HomeGeofenceService {
  const HomeGeofenceService();

  static const channel = MethodChannel('potner/home_geofence');

  Future<HomeGeofenceStatus> getStatus() async {
    final value = await channel.invokeMethod<Object?>('getGeofenceStatus');
    return HomeGeofenceStatus.fromMap(_asMap(value));
  }

  Future<void> requestForegroundPermission() async {
    await channel.invokeMethod<Object?>('requestForegroundLocationPermission');
  }

  Future<void> openBackgroundLocationSettings() {
    return channel.invokeMethod<void>('openBackgroundLocationSettings');
  }

  Future<CurrentHomeLocation> getCurrentLocation() async {
    final value = await channel.invokeMethod<Object?>('getCurrentLocation');
    return CurrentHomeLocation.fromMap(_asMap(value));
  }

  Future<HomeGeofenceStatus> register({
    required double latitude,
    required double longitude,
    required double approachRadius,
    required double cancelRadius,
  }) async {
    final value = await channel.invokeMethod<Object?>('registerHomeGeofences', {
      'latitude': latitude,
      'longitude': longitude,
      'approachRadius': approachRadius,
      'cancelRadius': cancelRadius,
      'apiBaseUrl': normalizedApiBaseUrl.substring(
        0,
        normalizedApiBaseUrl.length - 1,
      ),
    });
    return HomeGeofenceStatus.fromMap(_asMap(value));
  }

  Future<HomeGeofenceStatus> remove() async {
    final value = await channel.invokeMethod<Object?>('removeHomeGeofences');
    return HomeGeofenceStatus.fromMap(_asMap(value));
  }

  Map<Object?, Object?> _asMap(Object? value) {
    if (value is Map<Object?, Object?>) {
      return value;
    }
    if (value is Map) {
      return Map<Object?, Object?>.from(value);
    }
    throw const FormatException('지오펜스 응답 형식이 올바르지 않습니다.');
  }
}
