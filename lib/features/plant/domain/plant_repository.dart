import 'package:potner_app/features/plant/domain/plant_reference.dart';

abstract class PlantRepository {
  Future<List<PlantCategoryOption>> getCategoryTree();

  Future<RegisteredPlant> createPlant({
    required String speciesId,
    required String lifeStageId,
    required String name,
    DateTime? adoptedDate,
  });
}
