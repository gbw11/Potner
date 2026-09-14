import 'package:potner_app/features/home/domain/home_dashboard.dart';
import 'package:potner_app/features/home/domain/home_repository.dart';

class FakeHomeRepository implements HomeRepository {
  FakeHomeRepository({
    List<HomePlant>? plants,
    Map<String, HomeDashboard>? dashboards,
    this.unreadAlertCount = 1,
  }) : plants = plants ?? sampleHomePlants,
       dashboards = dashboards ?? sampleHomeDashboards;

  final List<HomePlant> plants;
  final Map<String, HomeDashboard> dashboards;
  final int unreadAlertCount;
  final List<String> requestedPlantIds = [];

  @override
  Future<HomeDashboard> getDashboard(HomePlant plant) async {
    requestedPlantIds.add(plant.id);
    final dashboard = dashboards[plant.id];
    if (dashboard == null) {
      throw StateError('Missing fake dashboard for ${plant.id}.');
    }
    return dashboard;
  }

  @override
  Future<List<HomePlant>> getPlants() async => plants;

  @override
  Future<int> getUnreadAlertCount() async => unreadAlertCount;
}

final sampleHomePlants = [
  HomePlant(
    id: 'plant-rose',
    name: '로지',
    createdAt: DateTime(2026, 1, 10),
    adoptedDate: DateTime(2026, 1, 10),
  ),
  HomePlant(
    id: 'plant-monstera',
    name: '몬스테라',
    createdAt: DateTime(2026, 2, 12),
    adoptedDate: DateTime(2026, 2, 12),
  ),
];

final sampleHomeDashboards = {
  'plant-rose': HomeDashboard(
    plant: sampleHomePlants[0],
    mood: const HomeMood(
      grade: 'GOOD',
      headline: '아주 좋아요!',
      detail: '햇살도 물도 딱 좋아요 :)',
    ),
    sensors: _sampleSensors(
      soilMoisture: 56,
      illuminance: 1250,
      temperature: 24.8,
      humidity: 58,
    ),
  ),
  'plant-monstera': HomeDashboard(
    plant: sampleHomePlants[1],
    mood: const HomeMood(
      grade: 'FAIR',
      headline: '조금 목말라요',
      detail: '흙이 마르고 있어요. 수분을 확인해 주세요.',
    ),
    sensors: _sampleSensors(
      soilMoisture: 31,
      illuminance: 850,
      temperature: 25.2,
      humidity: 52,
      soilStatus: HomeSensorStatus.low,
    ),
  ),
};

List<HomeSensorReading> _sampleSensors({
  required num soilMoisture,
  required num illuminance,
  required num temperature,
  required num humidity,
  HomeSensorStatus soilStatus = HomeSensorStatus.normal,
}) {
  final measuredAt = DateTime.now().toUtc();
  return [
    HomeSensorReading(
      type: HomeSensorType.soilMoisture,
      unit: 'PERCENT',
      value: soilMoisture,
      measuredAt: measuredAt,
      status: soilStatus,
    ),
    HomeSensorReading(
      type: HomeSensorType.illuminance,
      unit: 'LUX',
      value: illuminance,
      measuredAt: measuredAt,
      status: HomeSensorStatus.notApplicable,
    ),
    HomeSensorReading(
      type: HomeSensorType.temperature,
      unit: 'CELSIUS',
      value: temperature,
      measuredAt: measuredAt,
      status: HomeSensorStatus.normal,
    ),
    HomeSensorReading(
      type: HomeSensorType.humidity,
      unit: 'PERCENT',
      value: humidity,
      measuredAt: measuredAt,
      status: HomeSensorStatus.normal,
    ),
  ];
}
