import 'package:potner_app/features/plant/domain/plant_reference.dart';

/// 식물 프로필 화면에 필요한 상세 정보다.
class PlantDetail {
  const PlantDetail({
    required this.plantId,
    required this.name,
    required this.speciesId,
    required this.speciesName,
    required this.categoryName,
    required this.lifeStage,
    this.adoptedDate,
    this.thumbnailUrl,
  });

  final String plantId;
  final String name;
  final String speciesId;
  final String speciesName;
  final String categoryName;
  final GrowthStageOption lifeStage;
  final DateTime? adoptedDate;
  final String? thumbnailUrl;
}

/// 식물별 적용 생육 기준이다. 케어 설정 화면이 편집하는 값만 담는다.
class GrowthProfile {
  const GrowthProfile({
    required this.plantId,
    required this.customized,
    this.soilMoistureMinPct,
    this.soilMoistureMaxPct,
    this.temperatureMinC,
    this.temperatureMaxC,
    this.humidityMinPct,
    this.humidityMaxPct,
    this.illuminanceMinLux,
    this.illuminanceMaxLux,
    this.recommendedWateringMl,
    this.wateringCycleDays,
  });

  final String plantId;

  /// 종 기본값에서 벗어나게 수정한 적이 있으면 true 다.
  final bool customized;

  final double? soilMoistureMinPct;
  final double? soilMoistureMaxPct;
  final double? temperatureMinC;
  final double? temperatureMaxC;
  final double? humidityMinPct;
  final double? humidityMaxPct;
  final double? illuminanceMinLux;
  final double? illuminanceMaxLux;
  final double? recommendedWateringMl;
  final double? wateringCycleDays;
}

/// 케어 설정 저장 요청이다. null 인 항목은 보내지 않아 기존 값을 유지한다.
/// 서버 응답은 중첩 구조지만 수정 요청은 평면 키를 쓴다.
class GrowthProfileUpdate {
  const GrowthProfileUpdate({
    this.soilMoistureMinPct,
    this.soilMoistureMaxPct,
    this.temperatureMinC,
    this.temperatureMaxC,
    this.humidityMinPct,
    this.humidityMaxPct,
    this.illuminanceMinLux,
    this.illuminanceMaxLux,
    this.recommendedWateringMl,
    this.wateringCycleDays,
  });

  final double? soilMoistureMinPct;
  final double? soilMoistureMaxPct;
  final double? temperatureMinC;
  final double? temperatureMaxC;
  final double? humidityMinPct;
  final double? humidityMaxPct;
  final double? illuminanceMinLux;
  final double? illuminanceMaxLux;
  final double? recommendedWateringMl;
  final double? wateringCycleDays;

  Map<String, Object> toJson() {
    return {
      'soilMoistureMinPct': ?soilMoistureMinPct,
      'soilMoistureMaxPct': ?soilMoistureMaxPct,
      'temperatureMinC': ?temperatureMinC,
      'temperatureMaxC': ?temperatureMaxC,
      'humidityMinPct': ?humidityMinPct,
      'humidityMaxPct': ?humidityMaxPct,
      'illuminanceMinLux': ?illuminanceMinLux,
      'illuminanceMaxLux': ?illuminanceMaxLux,
      'recommendedWateringMl': ?recommendedWateringMl,
      'wateringCycleDays': ?wateringCycleDays,
    };
  }

  bool get isEmpty => toJson().isEmpty;
}
