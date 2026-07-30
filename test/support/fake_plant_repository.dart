import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';
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

  PlantDetail detail = samplePlantDetail;
  GrowthProfile growthProfile = sampleGrowthProfile;
  final List<UpdatePlantCall> updatePlantCalls = [];
  final List<String> changedLifeStageIds = [];
  final List<GrowthProfileUpdate> growthProfileUpdates = [];
  int resetCalls = 0;

  @override
  Future<PlantDetail> getPlantDetail(String plantId) async => detail;

  @override
  Future<PlantDetail> updatePlant({
    required String plantId,
    String? name,
    DateTime? adoptedDate,
  }) async {
    updatePlantCalls.add(
      UpdatePlantCall(plantId: plantId, name: name, adoptedDate: adoptedDate),
    );
    return detail;
  }

  @override
  Future<PlantDetail> changeLifeStage({
    required String plantId,
    required String lifeStageId,
  }) async {
    changedLifeStageIds.add(lifeStageId);
    return detail;
  }

  @override
  Future<List<GrowthStageOption>> getGrowthStages(String speciesId) async {
    for (final category in categories) {
      for (final species in category.species) {
        if (species.speciesId == speciesId) {
          return species.growthStages;
        }
      }
    }
    return const [];
  }

  @override
  Future<GrowthProfile> getGrowthProfile(String plantId) async =>
      growthProfile;

  @override
  Future<GrowthProfile> updateGrowthProfile({
    required String plantId,
    required GrowthProfileUpdate update,
  }) async {
    growthProfileUpdates.add(update);
    return growthProfile;
  }

  @override
  Future<GrowthProfile> resetGrowthProfile(String plantId) async {
    resetCalls += 1;
    return growthProfile;
  }
}

class UpdatePlantCall {
  const UpdatePlantCall({required this.plantId, this.name, this.adoptedDate});

  final String plantId;
  final String? name;
  final DateTime? adoptedDate;
}

final samplePlantDetail = PlantDetail(
  plantId: 'plant-rose',
  name: '로지',
  speciesId: 'species-basil',
  speciesName: '바질',
  categoryName: '허브',
  lifeStage: const GrowthStageOption(
    lifeStageId: 'stage-seedling',
    code: 'SEEDLING',
    name: '모종기',
  ),
  adoptedDate: DateTime(2026, 3, 20),
);

const sampleGrowthProfile = GrowthProfile(
  plantId: 'plant-rose',
  customized: false,
  soilMoistureMinPct: 40,
  soilMoistureMaxPct: 50,
  temperatureMinC: 18,
  temperatureMaxC: 26,
  humidityMinPct: 50,
  humidityMaxPct: 70,
  illuminanceMinLux: 8000,
  illuminanceMaxLux: 15000,
  recommendedWateringMl: 300,
  wateringCycleDays: 7,
);

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
