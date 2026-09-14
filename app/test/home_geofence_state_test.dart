import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/arrival/domain/home_geofence_state.dart';

void main() {
  test('native geofence status maps registration and persisted values', () {
    final status = HomeGeofenceStatus.fromMap({
      'enabled': true,
      'permissionStatus': 'BACKGROUND',
      'preciseLocationGranted': true,
      'locationServicesEnabled': true,
      'googlePlayServicesAvailable': true,
      'registrationStatus': 'REGISTERED',
      'zoneState': 'OUTSIDE_ARMED',
      'deliveryState': 'APPROACH_PENDING',
      'homeLatitude': 37.501,
      'homeLongitude': 127.039,
      'approachRadius': 300.0,
      'cancelRadius': 550.0,
      'lastTransitionAtEpochMs': 1_786_000_000_000,
      'lastErrorCode': null,
    });

    expect(status.enabled, isTrue);
    expect(status.permission, HomeLocationPermission.background);
    expect(status.preciseLocationGranted, isTrue);
    expect(status.registration, HomeGeofenceRegistration.registered);
    expect(status.zone, HomeGeofenceZone.outsideArmed);
    expect(status.delivery, HomeGeofenceDelivery.approachPending);
    expect(status.homeLatitude, 37.501);
    expect(status.cancelRadius, 550);
    expect(status.lastTransitionAt, isNotNull);
  });
}
