import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/domain/plant_reference.dart';

abstract class PlantRepository {
  Future<List<PlantCategoryOption>> getCategoryTree();

  Future<RegisteredPlant> createPlant({
    required String speciesId,
    required String lifeStageId,
    required String name,
    DateTime? adoptedDate,
  });

  Future<List<MyPlant>> getMyPlants();

  Future<void> deletePlant(String plantId);
}
