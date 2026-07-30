import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/plant/data/plant_api.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
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
}
