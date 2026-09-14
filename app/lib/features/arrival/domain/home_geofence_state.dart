enum HomeLocationPermission { denied, foregroundOnly, background }

enum HomeGeofenceZone { disabled, initializing, outsideArmed, insideLocked }

enum HomeGeofenceDelivery {
  none,
  approachPending,
  approachActive,
  cancelPending,
}

enum HomeGeofenceRegistration { notRegistered, registering, registered, error }

class HomeGeofenceStatus {
  const HomeGeofenceStatus({
    this.enabled = false,
    this.permission = HomeLocationPermission.denied,
    this.preciseLocationGranted = false,
    this.locationServicesEnabled = false,
    this.googlePlayServicesAvailable = false,
    this.registration = HomeGeofenceRegistration.notRegistered,
    this.zone = HomeGeofenceZone.disabled,
    this.delivery = HomeGeofenceDelivery.none,
    this.homeLatitude,
    this.homeLongitude,
    this.approachRadius = 300,
    this.cancelRadius = 550,
    this.lastTransitionAt,
    this.lastErrorCode,
  });

  factory HomeGeofenceStatus.fromMap(Map<Object?, Object?> map) {
    return HomeGeofenceStatus(
      enabled: map['enabled'] == true,
      permission: switch (map['permissionStatus']) {
        'BACKGROUND' => HomeLocationPermission.background,
        'FOREGROUND_ONLY' => HomeLocationPermission.foregroundOnly,
        _ => HomeLocationPermission.denied,
      },
      preciseLocationGranted: map['preciseLocationGranted'] == true,
      locationServicesEnabled: map['locationServicesEnabled'] == true,
      googlePlayServicesAvailable: map['googlePlayServicesAvailable'] == true,
      registration: switch (map['registrationStatus']) {
        'REGISTERING' => HomeGeofenceRegistration.registering,
        'REGISTERED' => HomeGeofenceRegistration.registered,
        'ERROR' => HomeGeofenceRegistration.error,
        _ => HomeGeofenceRegistration.notRegistered,
      },
      zone: switch (map['zoneState']) {
        'INITIALIZING' => HomeGeofenceZone.initializing,
        'OUTSIDE_ARMED' => HomeGeofenceZone.outsideArmed,
        'INSIDE_LOCKED' => HomeGeofenceZone.insideLocked,
        _ => HomeGeofenceZone.disabled,
      },
      delivery: switch (map['deliveryState']) {
        'APPROACH_PENDING' => HomeGeofenceDelivery.approachPending,
        'APPROACH_ACTIVE' => HomeGeofenceDelivery.approachActive,
        'CANCEL_PENDING' => HomeGeofenceDelivery.cancelPending,
        _ => HomeGeofenceDelivery.none,
      },
      homeLatitude: _number(map['homeLatitude']),
      homeLongitude: _number(map['homeLongitude']),
      approachRadius: _number(map['approachRadius']) ?? 300,
      cancelRadius: _number(map['cancelRadius']) ?? 550,
      lastTransitionAt: _epochDateTime(map['lastTransitionAtEpochMs']),
      lastErrorCode: _nonEmptyString(map['lastErrorCode']),
    );
  }

  final bool enabled;
  final HomeLocationPermission permission;
  final bool preciseLocationGranted;
  final bool locationServicesEnabled;
  final bool googlePlayServicesAvailable;
  final HomeGeofenceRegistration registration;
  final HomeGeofenceZone zone;
  final HomeGeofenceDelivery delivery;
  final double? homeLatitude;
  final double? homeLongitude;
  final double approachRadius;
  final double cancelRadius;
  final DateTime? lastTransitionAt;
  final String? lastErrorCode;

  bool get hasHomeLocation => homeLatitude != null && homeLongitude != null;

  static double? _number(Object? value) =>
      value is num ? value.toDouble() : null;

  static DateTime? _epochDateTime(Object? value) => value is int
      ? DateTime.fromMillisecondsSinceEpoch(value)
      : value is num
      ? DateTime.fromMillisecondsSinceEpoch(value.toInt())
      : null;

  static String? _nonEmptyString(Object? value) =>
      value is String && value.trim().isNotEmpty ? value : null;
}

class CurrentHomeLocation {
  const CurrentHomeLocation({
    required this.latitude,
    required this.longitude,
    required this.accuracy,
    required this.timestamp,
  });

  factory CurrentHomeLocation.fromMap(Map<Object?, Object?> map) {
    final latitude = map['latitude'];
    final longitude = map['longitude'];
    final accuracy = map['accuracy'];
    final timestamp = map['timestampEpochMs'];
    if (latitude is! num ||
        longitude is! num ||
        accuracy is! num ||
        timestamp is! num) {
      throw const FormatException('현재 위치 응답 형식이 올바르지 않습니다.');
    }
    return CurrentHomeLocation(
      latitude: latitude.toDouble(),
      longitude: longitude.toDouble(),
      accuracy: accuracy.toDouble(),
      timestamp: DateTime.fromMillisecondsSinceEpoch(timestamp.toInt()),
    );
  }

  final double latitude;
  final double longitude;
  final double accuracy;
  final DateTime timestamp;
}
