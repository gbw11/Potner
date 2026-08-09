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
    this.persona,
  });

  final String plantId;
  final String name;
  final String speciesId;
  final String speciesName;
  final String categoryName;
  final GrowthStageOption lifeStage;
  final DateTime? adoptedDate;
  final String? thumbnailUrl;

  /// 종에 정해진 성격이다. 서버가 아직 배포되지 않았거나 페르소나가 없는 종이면 null 이다.
  final SpeciesPersona? persona;
}

/// 종에 정해진 성격이다. 꽃말을 성격으로 옮긴 것으로, 일기 말투도 같은 근거를 따른다.
class SpeciesPersona {
  const SpeciesPersona({
    required this.characterName,
    required this.flowerMeaning,
    required this.tags,
    required this.personality,
    required this.coreValue,
  });

  final String characterName;

  /// 대표 꽃말. 성격의 근거다.
  final String flowerMeaning;

  /// 해시태그용 짧은 키워드다. 꽃말은 여기 없어 화면이 앞에 따로 붙인다.
  final List<String> tags;

  /// 성격을 사람 말로 옮긴 문장이다.
  ///
  /// 서버의 일기 프롬프트가 쓰는 역할 지시문과 다른 값이다. 그쪽은 모델에게 문체를 시키려고
  /// 직업 은유로 쓰여 있어("섬세한 조향사") 성격을 물은 화면에 그대로 내면 겉돈다.
  final String personality;

  final String coreValue;

  /// 화면에 찍을 해시태그다. 꽃말을 맨 앞에 둔다. 성격의 근거라 먼저 읽혀야 한다.
  List<String> get hashtags => [
    if (flowerMeaning.isNotEmpty) flowerMeaning,
    ...tags,
  ];

  /// 보여줄 것이 하나도 없으면 성격 영역을 통째로 감춘다.
  bool get isEmpty =>
      hashtags.isEmpty && personality.isEmpty && coreValue.isEmpty;
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
    this.dailyLightMinLuxHour,
    this.dailyLightMaxLuxHour,
    this.dailyLightTargetLuxHour,
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

  /// 순간 조도 범위다. 서버가 이 값으로 판정하지 않는다 — 밤에는 0 lux 가 정상이라
  /// 순간값으로 보면 매일 해 질 때 부족 알림이 간다. 그래프 참고용으로만 남긴다.
  final double? illuminanceMinLux;
  final double? illuminanceMaxLux;

  final double? recommendedWateringMl;
  final double? wateringCycleDays;

  /// 하루에 받아야 할 빛의 총량(lux·h)이다. 조도를 시간 적분한 값으로, 광량 부족 알림과
  /// 로봇의 햇빛 자리 이동이 모두 이 값을 기준으로 움직인다.
  final double? dailyLightMinLuxHour;
  final double? dailyLightMaxLuxHour;
  final double? dailyLightTargetLuxHour;
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
    this.dailyLightMinLuxHour,
    this.dailyLightMaxLuxHour,
    this.dailyLightTargetLuxHour,
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
  final double? dailyLightMinLuxHour;
  final double? dailyLightMaxLuxHour;
  final double? dailyLightTargetLuxHour;

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
      'dailyLightMinLuxHour': ?dailyLightMinLuxHour,
      'dailyLightMaxLuxHour': ?dailyLightMaxLuxHour,
      'dailyLightTargetLuxHour': ?dailyLightTargetLuxHour,
    };
  }

  bool get isEmpty => toJson().isEmpty;
}
