import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/plant/data/plant_api.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';
import 'package:potner_app/features/plant/domain/plant_reference.dart';
import 'package:potner_app/features/plant/domain/plant_repository.dart';

final plantRepositoryProvider = Provider<PlantRepository>((ref) {
  return PlantRepositoryImpl(PlantApi(ref.watch(apiClientProvider).dio));
});

class PlantRepositoryImpl implements PlantRepository {
  PlantRepositoryImpl(this._api);

  final PlantApi _api;

  @override
  Future<List<PlantCategoryOption>> getCategoryTree() {
    return _api.getCategoryTree();
  }

  @override
  Future<RegisteredPlant> createPlant({
    required String speciesId,
    required String lifeStageId,
    required String name,
    DateTime? adoptedDate,
  }) {
    return _api.createPlant(
      speciesId: speciesId,
      lifeStageId: lifeStageId,
      name: name,
      adoptedDate: adoptedDate,
    );
  }

  @override
  Future<List<MyPlant>> getMyPlants() {
    return _api.getMyPlants();
  }

  @override
  Future<void> deletePlant(String plantId) {
    return _api.deletePlant(plantId);
  }

  @override
  Future<void> sendRepottingReminder(String plantId) {
    return _api.sendRepottingReminder(plantId);
  }

  @override
  Future<PlantDetail> getPlantDetail(String plantId) {
    return _api.getPlantDetail(plantId);
  }

  @override
  Future<PlantDetail> updatePlant({
    required String plantId,
    String? name,
    DateTime? adoptedDate,
  }) {
    return _api.updatePlant(
      plantId: plantId,
      name: name,
      adoptedDate: adoptedDate,
    );
  }

  @override
  Future<PlantDetail> changeLifeStage({
    required String plantId,
    required String lifeStageId,
  }) {
    return _api.changeLifeStage(plantId: plantId, lifeStageId: lifeStageId);
  }

  @override
  Future<List<GrowthStageOption>> getGrowthStages(String speciesId) {
    return _api.getGrowthStages(speciesId);
  }

  @override
  Future<GrowthProfile> getGrowthProfile(String plantId) {
    return _api.getGrowthProfile(plantId);
  }

  @override
  Future<GrowthProfile> updateGrowthProfile({
    required String plantId,
    required GrowthProfileUpdate update,
  }) {
    return _api.updateGrowthProfile(plantId: plantId, update: update);
  }

  @override
  Future<GrowthProfile> resetGrowthProfile(String plantId) {
    return _api.resetGrowthProfile(plantId);
  }
}
