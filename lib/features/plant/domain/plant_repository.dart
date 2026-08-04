import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';
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

  /// 그 식물의 분갈이 안내 푸시를 사용자 기기로 보낸다.
  ///
  /// 시기 판정은 서버가 하지 않는다. 이력을 남기지 않으므로 두 번 부르면 두 번 나간다.
  Future<void> sendRepottingReminder(String plantId);

  Future<PlantDetail> getPlantDetail(String plantId);

  /// 이름·데려온 날짜 부분 수정이다. null 인 항목은 보내지 않는다.
  Future<PlantDetail> updatePlant({
    required String plantId,
    String? name,
    DateTime? adoptedDate,
  });

  Future<PlantDetail> changeLifeStage({
    required String plantId,
    required String lifeStageId,
  });

  Future<List<GrowthStageOption>> getGrowthStages(String speciesId);

  Future<GrowthProfile> getGrowthProfile(String plantId);

  Future<GrowthProfile> updateGrowthProfile({
    required String plantId,
    required GrowthProfileUpdate update,
  });

  Future<GrowthProfile> resetGrowthProfile(String plantId);
}
