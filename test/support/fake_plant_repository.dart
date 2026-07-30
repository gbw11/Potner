import 'package:potner_app/features/plant/domain/plant_reference.dart';
import 'package:potner_app/features/plant/domain/plant_repository.dart';

class FakePlantRepository implements PlantRepository {
  FakePlantRepository({List<PlantCategoryOption>? categories})
    : categories = categories ?? samplePlantCategories;

  final List<PlantCategoryOption> categories;
  final List<CreatePlantCall> createCalls = [];

  @override
  Future<List<PlantCategoryOption>> getCategoryTree() async => categories;

  @override
  Future<RegisteredPlant> createPlant({
    required String speciesId,
    required String lifeStageId,
    required String name,
    DateTime? adoptedDate,
  }) async {
    createCalls.add(
      CreatePlantCall(
        speciesId: speciesId,
        lifeStageId: lifeStageId,
        name: name,
        adoptedDate: adoptedDate,
      ),
    );
    return RegisteredPlant(plantId: 'plant-new', name: name);
  }
}

class CreatePlantCall {
  const CreatePlantCall({
    required this.speciesId,
    required this.lifeStageId,
    required this.name,
    this.adoptedDate,
  });

  final String speciesId;
  final String lifeStageId;
  final String name;
  final DateTime? adoptedDate;
}

final samplePlantCategories = [
  const PlantCategoryOption(
    categoryId: 'cat-herb',
    name: '허브',
    species: [
      PlantSpeciesOption(
        speciesId: 'species-basil',
        name: '바질',
        growthStages: [
          GrowthStageOption(
            lifeStageId: 'stage-seedling',
            code: 'SEEDLING',
            name: '모종기',
          ),
          GrowthStageOption(
            lifeStageId: 'stage-growth',
            code: 'GROWTH',
            name: '성장기',
          ),
        ],
      ),
    ],
  ),
  const PlantCategoryOption(
    categoryId: 'cat-flower',
    name: '꽃',
    species: [
      PlantSpeciesOption(
        speciesId: 'species-rose',
        name: '장미',
        growthStages: [
          GrowthStageOption(
            lifeStageId: 'stage-bloom',
            code: 'BLOOM',
            name: '개화기',
          ),
        ],
      ),
    ],
  ),
];
