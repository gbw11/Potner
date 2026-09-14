import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/home/data/home_api.dart';
import 'package:potner_app/features/home/domain/home_dashboard.dart';
import 'package:potner_app/features/home/domain/home_repository.dart';

final homeRepositoryProvider = Provider<HomeRepository>((ref) {
  return HomeRepositoryImpl(HomeApi(ref.watch(apiClientProvider).dio));
});

class HomeRepositoryImpl implements HomeRepository {
  HomeRepositoryImpl(this._api);

  final HomeApi _api;

  @override
  Future<List<HomePlant>> getPlants() {
    return _api.getPlants();
  }

  @override
  Future<HomeDashboard> getDashboard(HomePlant plant) async {
    final results = await Future.wait<Object?>([
      _api.getMood(plant.id),
      _api.getCurrentSensors(plant.id),
      _getLatestPhotoBestEffort(plant),
    ]);

    return HomeDashboard(
      plant: plant,
      mood: results[0] as HomeMood,
      sensors: results[1] as List<HomeSensorReading>,
      latestPhotoUrl: results[2] as String?,
    );
  }

  @override
  Future<int> getUnreadAlertCount() {
    return _api.getUnreadAlertCount();
  }

  Future<String?> _getLatestPhotoBestEffort(HomePlant plant) async {
    try {
      return await _api.getLatestPhoto(plant);
    } catch (_) {
      return null;
    }
  }
}
