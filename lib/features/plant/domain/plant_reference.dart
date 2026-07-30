/// 식물 등록 화면의 대분류 하나와 그 아래 선택 가능한 종 목록이다.
class PlantCategoryOption {
  const PlantCategoryOption({
    required this.categoryId,
    required this.name,
    required this.species,
  });

  final String categoryId;
  final String name;
  final List<PlantSpeciesOption> species;
}

/// 소분류(종) 하나다. 자람 수준은 종마다 다르므로 종에 붙어 내려온다.
class PlantSpeciesOption {
  const PlantSpeciesOption({
    required this.speciesId,
    required this.name,
    required this.growthStages,
    this.scientificName,
    this.description,
  });

  final String speciesId;
  final String name;
  final String? scientificName;
  final String? description;
  final List<GrowthStageOption> growthStages;
}

class GrowthStageOption {
  const GrowthStageOption({
    required this.lifeStageId,
    required this.code,
    required this.name,
    this.description,
  });

  final String lifeStageId;
  final String code;
  final String name;
  final String? description;
}

/// 등록 직후 서버가 돌려준 식물이다. 화면 전환에 필요한 최소 정보만 담는다.
class RegisteredPlant {
  const RegisteredPlant({required this.plantId, required this.name});

  final String plantId;
  final String name;
}
