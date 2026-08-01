import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/features/arrival/data/home_geofence_service.dart';
import 'package:potner_app/features/arrival/domain/home_geofence_state.dart';

final homeGeofenceControllerProvider =
    NotifierProvider<HomeGeofenceController, HomeGeofenceViewState>(
      HomeGeofenceController.new,
    );

enum HomeGeofenceEnableResult { enabled, backgroundPermissionRequired, failed }

class HomeGeofenceViewState {
  const HomeGeofenceViewState({
    this.status = const HomeGeofenceStatus(),
    this.busy = false,
    this.homeLatitude,
    this.homeLongitude,
    this.approachRadius = 300,
    this.cancelRadius = 550,
    this.message = '귀가 감지 상태를 확인하고 있어요.',
  });

  final HomeGeofenceStatus status;
  final bool busy;
  final double? homeLatitude;
  final double? homeLongitude;
  final double approachRadius;
  final double cancelRadius;
  final String message;

  bool get hasHomeLocation => homeLatitude != null && homeLongitude != null;

  HomeGeofenceViewState copyWith({
    HomeGeofenceStatus? status,
    bool? busy,
    double? homeLatitude,
    double? homeLongitude,
    double? approachRadius,
    double? cancelRadius,
    String? message,
  }) {
    return HomeGeofenceViewState(
      status: status ?? this.status,
      busy: busy ?? this.busy,
      homeLatitude: homeLatitude ?? this.homeLatitude,
      homeLongitude: homeLongitude ?? this.homeLongitude,
      approachRadius: approachRadius ?? this.approachRadius,
      cancelRadius: cancelRadius ?? this.cancelRadius,
      message: message ?? this.message,
    );
  }
}

class HomeGeofenceController extends Notifier<HomeGeofenceViewState> {
  late HomeGeofenceService _service;

  @override
  HomeGeofenceViewState build() {
    _service = ref.read(homeGeofenceServiceProvider);
    Future.microtask(load);
    return const HomeGeofenceViewState();
  }

  Future<void> load() async {
    if (state.busy) {
      return;
    }
    state = state.copyWith(busy: true);
    try {
      final status = await _service.getStatus();
      state = HomeGeofenceViewState(
        status: status,
        homeLatitude: status.homeLatitude,
        homeLongitude: status.homeLongitude,
        approachRadius: status.approachRadius,
        cancelRadius: status.cancelRadius,
        message: _statusMessage(status),
      );
    } catch (error) {
      state = state.copyWith(busy: false, message: _errorMessage(error));
    }
  }

  Future<bool> useCurrentLocationAsHome() async {
    if (state.busy || state.status.enabled) {
      return false;
    }
    state = state.copyWith(busy: true, message: '현재 위치를 확인하고 있어요.');
    try {
      var status = await _service.getStatus();
      if (!status.preciseLocationGranted) {
        await _service.requestForegroundPermission();
        status = await _service.getStatus();
      }
      if (!status.preciseLocationGranted) {
        state = state.copyWith(
          status: status,
          busy: false,
          message: '정확한 위치 권한이 필요합니다. 위치 권한에서 정확한 위치를 켜 주세요.',
        );
        return false;
      }
      if (!status.locationServicesEnabled) {
        state = state.copyWith(
          status: status,
          busy: false,
          message: '휴대전화의 위치 서비스를 켜 주세요.',
        );
        return false;
      }
      final location = await _service.getCurrentLocation();
      state = state.copyWith(
        status: status,
        busy: false,
        homeLatitude: location.latitude,
        homeLongitude: location.longitude,
        message: '현재 위치를 집으로 설정했습니다. 오차 약 ${location.accuracy.round()}m',
      );
      return true;
    } catch (error) {
      state = state.copyWith(busy: false, message: _errorMessage(error));
      return false;
    }
  }

  Future<HomeGeofenceEnableResult> enable() async {
    if (state.busy) {
      return HomeGeofenceEnableResult.failed;
    }
    state = state.copyWith(busy: true, message: '귀가 감지를 준비하고 있어요.');
    try {
      var status = await _service.getStatus();
      if (!status.preciseLocationGranted) {
        await _service.requestForegroundPermission();
        status = await _service.getStatus();
      }
      if (!status.preciseLocationGranted) {
        state = state.copyWith(
          status: status,
          busy: false,
          message: '대략적 위치가 아닌 정확한 위치 권한이 필요합니다.',
        );
        return HomeGeofenceEnableResult.failed;
      }
      if (status.permission != HomeLocationPermission.background) {
        state = state.copyWith(
          status: status,
          busy: false,
          message: '앱이 닫혀 있어도 감지하려면 위치 권한을 항상 허용으로 바꿔 주세요.',
        );
        return HomeGeofenceEnableResult.backgroundPermissionRequired;
      }
      if (!status.locationServicesEnabled) {
        state = state.copyWith(
          status: status,
          busy: false,
          message: '휴대전화의 위치 서비스를 켠 뒤 다시 시도해 주세요.',
        );
        return HomeGeofenceEnableResult.failed;
      }
      if (!status.googlePlayServicesAvailable) {
        state = state.copyWith(
          status: status,
          busy: false,
          message: 'Google Play 서비스를 사용할 수 없어 귀가 감지를 켤 수 없습니다.',
        );
        return HomeGeofenceEnableResult.failed;
      }

      var latitude = state.homeLatitude;
      var longitude = state.homeLongitude;
      if (latitude == null || longitude == null) {
        final location = await _service.getCurrentLocation();
        latitude = location.latitude;
        longitude = location.longitude;
      }
      final registered = await _service.register(
        latitude: latitude,
        longitude: longitude,
        approachRadius: state.approachRadius,
        cancelRadius: state.cancelRadius,
      );
      state = HomeGeofenceViewState(
        status: registered,
        homeLatitude: latitude,
        homeLongitude: longitude,
        approachRadius: state.approachRadius,
        cancelRadius: state.cancelRadius,
        message: _statusMessage(registered),
      );
      return HomeGeofenceEnableResult.enabled;
    } catch (error) {
      state = state.copyWith(busy: false, message: _errorMessage(error));
      return HomeGeofenceEnableResult.failed;
    }
  }

  Future<void> disable() async {
    if (state.busy) {
      return;
    }
    state = state.copyWith(busy: true, message: '귀가 감지를 끄고 있어요.');
    try {
      final status = await _service.remove();
      state = state.copyWith(
        status: status,
        busy: false,
        message: '귀가 감지를 껐습니다.',
      );
    } catch (error) {
      state = state.copyWith(busy: false, message: _errorMessage(error));
    }
  }

  Future<void> openBackgroundLocationSettings() {
    return _service.openBackgroundLocationSettings();
  }

  void setApproachRadius(double value) {
    if (state.busy || state.status.enabled) {
      return;
    }
    final rounded = (value / 50).round() * 50.0;
    state = state.copyWith(
      approachRadius: rounded,
      cancelRadius: state.cancelRadius < rounded + 200
          ? rounded + 200
          : state.cancelRadius,
    );
  }

  void setCancelRadius(double value) {
    if (state.busy || state.status.enabled) {
      return;
    }
    final rounded = (value / 50).round() * 50.0;
    state = state.copyWith(
      cancelRadius: rounded < state.approachRadius + 200
          ? state.approachRadius + 200
          : rounded,
    );
  }

  String _statusMessage(HomeGeofenceStatus status) {
    if (!status.enabled) {
      return status.lastErrorCode == null
          ? '현재 귀가 감지가 꺼져 있습니다.'
          : _nativeErrorMessage(status.lastErrorCode!);
    }
    return switch (status.delivery) {
      HomeGeofenceDelivery.approachPending => '귀가 접근 이벤트를 서버로 보내고 있어요.',
      HomeGeofenceDelivery.approachActive => '귀가를 감지해 오린카가 마중 동작을 수행 중이에요.',
      HomeGeofenceDelivery.cancelPending => '마중 취소와 HOME 복귀를 요청하고 있어요.',
      HomeGeofenceDelivery.none => switch (status.zone) {
        HomeGeofenceZone.outsideArmed =>
          '집 밖에서 다음 ${status.approachRadius.round()}m 진입을 기다리고 있어요.',
        HomeGeofenceZone.insideLocked =>
          '집 근처에서는 바로 출발하지 않습니다. '
              '${status.cancelRadius.round()}m 밖으로 나가면 다음 방문이 활성화돼요.',
        _ => '귀가 감지가 활성화되어 있습니다.',
      },
    };
  }

  String _errorMessage(Object error) {
    if (error is PlatformException) {
      return _nativeErrorMessage(error.code);
    }
    if (error is MissingPluginException) {
      return '귀가 감지는 Android 기기에서만 사용할 수 있습니다.';
    }
    return '귀가 감지 설정을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
  }

  String _nativeErrorMessage(String code) {
    return switch (code) {
      'PRECISE_LOCATION_REQUIRED' => '정확한 위치 권한을 허용해 주세요.',
      'FOREGROUND_LOCATION_REQUIRED' => '앱 사용 중 위치 권한을 허용해 주세요.',
      'BACKGROUND_LOCATION_REQUIRED' => '위치 권한을 항상 허용으로 바꿔 주세요.',
      'LOCATION_SERVICES_DISABLED' => '휴대전화의 위치 서비스를 켜 주세요.',
      'GOOGLE_PLAY_SERVICES_UNAVAILABLE' => 'Google Play 서비스를 사용할 수 없습니다.',
      'CURRENT_LOCATION_UNAVAILABLE' => '현재 위치를 확인하지 못했습니다. 야외에서 다시 시도해 주세요.',
      'LOCATION_ACCURACY_TOO_LOW' => 'GPS 정확도가 낮아 이번 감지를 실행하지 않았습니다.',
      'STALE_EVENT' => '늦게 도착한 귀가 이벤트를 폐기했습니다.',
      'INVALID_EVENT_TIME' => '휴대전화 시각이 맞지 않아 이벤트를 처리하지 않았습니다.',
      'AUTH_TOKEN_MISSING' ||
      'TOKEN_REFRESH_FAILED' => '로그인이 만료되었습니다. 다시 로그인해 주세요.',
      _ => '귀가 감지 상태를 확인해 주세요. 오류 코드: $code',
    };
  }
}
