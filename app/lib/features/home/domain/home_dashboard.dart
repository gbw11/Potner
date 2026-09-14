enum HomeSensorType { soilMoisture, illuminance, temperature, humidity }

enum HomeSensorStatus { low, normal, high, notApplicable, noData, stale }

class HomePlant {
  const HomePlant({
    required this.id,
    required this.name,
    required this.createdAt,
    this.adoptedDate,
    this.thumbnailUrl,
    this.imageUrl,
  });

  final String id;
  final String name;
  final DateTime createdAt;
  final DateTime? adoptedDate;
  final String? thumbnailUrl;
  final String? imageUrl;
}

class HomeMood {
  const HomeMood({
    required this.grade,
    required this.headline,
    required this.detail,
  });

  final String grade;
  final String headline;
  final String detail;
}

class HomeSensorReading {
  const HomeSensorReading({
    required this.type,
    required this.unit,
    required this.status,
    this.value,
    this.measuredAt,
  });

  final HomeSensorType type;
  final String unit;
  final num? value;
  final DateTime? measuredAt;
  final HomeSensorStatus status;
}

class HomeDashboard {
  const HomeDashboard({
    required this.plant,
    required this.mood,
    required this.sensors,
    this.latestPhotoUrl,
  });

  final HomePlant plant;
  final HomeMood mood;
  final List<HomeSensorReading> sensors;
  final String? latestPhotoUrl;

  HomeSensorReading? sensor(HomeSensorType type) {
    for (final sensor in sensors) {
      if (sensor.type == type) {
        return sensor;
      }
    }
    return null;
  }

  DateTime? get latestMeasuredAt {
    DateTime? latest;
    for (final sensor in sensors) {
      final measuredAt = sensor.measuredAt;
      if (measuredAt != null &&
          (latest == null || measuredAt.isAfter(latest))) {
        latest = measuredAt;
      }
    }
    return latest;
  }
}
