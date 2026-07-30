import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/domain/plant_reference.dart';
import 'package:potner_app/features/plant/domain/plant_repository.dart';

class FakePlantRepository implements PlantRepository {
  FakePlantRepository({
    List<PlantCategoryOption>? categories,
    List<MyPlant>? myPlants,
  }) : categories = categories ?? samplePlantCategories,
       myPlants = List.of(myPlants ?? sampleMyPlants);

  final List<PlantCategoryOption> categories;
  final List<MyPlant> myPlants;
  final List<CreatePlantCall> createCalls = [];
  final List<String> deletedPlantIds = [];

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

  @override
  Future<List<MyPlant>> getMyPlants() async => List.of(myPlants);

  @override
  Future<void> deletePlant(String plantId) async {
    deletedPlantIds.add(plantId);
    myPlants.removeWhere((plant) => plant.plantId == plantId);
  }
}

final sampleMyPlants = [
  MyPlant(
    plantId: 'plant-rose',
    name: '로지',
    speciesName: '장미',
    categoryName: '꽃',
    lifeStageName: '개화기',
    adoptedDate: DateTime(2026, 1, 10),
  ),
  MyPlant(
    plantId: 'plant-rosemary',
    name: '마리아',
    speciesName: '로즈마리',
    categoryName: '허브',
    lifeStageName: '성장기',
    adoptedDate: DateTime(2026, 2, 12),
  ),
  const MyPlant(
    plantId: 'plant-portulaca',
    name: '해님',
    speciesName: '채송화',
    categoryName: '꽃',
    lifeStageName: '모종기',
  ),
];

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
