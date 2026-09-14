import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/core/api/media_url.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';
import 'package:potner_app/features/plant/domain/plant_reference.dart';

class PlantApi {
  PlantApi(this._dio);

  final Dio _dio;

  Future<List<PlantCategoryOption>> getCategoryTree() async {
    try {
      final response = await _dio.get<Object?>('plant-categories');
      final data = _asMap(response.data);
      return _asList(data['categories'])
          .map((item) {
            final category = _asMap(item);
            return PlantCategoryOption(
              categoryId: _requiredString(category['categoryId']),
              name: _requiredString(category['name']),
              species: _asList(category['species'])
                  .map(_parseSpecies)
                  .toList(growable: false),
            );
          })
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<RegisteredPlant> createPlant({
    required String speciesId,
    required String lifeStageId,
    required String name,
    DateTime? adoptedDate,
  }) async {
    try {
      final response = await _dio.post<Object?>(
        'plants',
        data: {
          'speciesId': speciesId,
          'lifeStageId': lifeStageId,
          'name': name,
          if (adoptedDate != null) 'adoptedDate': _dateParameter(adoptedDate),
        },
      );
      final data = _asMap(response.data);
      return RegisteredPlant(
        plantId: _requiredString(data['plantId']),
        name: _requiredString(data['name']),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<List<MyPlant>> getMyPlants() async {
    try {
      final response = await _dio.get<Object?>('plants');
      final data = _asMap(response.data);
      return _asList(data['plants'])
          .map((item) {
            final plant = _asMap(item);
            final photo = _nullableMap(plant['representativePhoto']);
            return MyPlant(
              plantId: _requiredString(plant['plantId']),
              name: _requiredString(plant['name']),
              speciesName: _requiredString(plant['speciesName']),
              categoryName: _requiredString(plant['categoryName']),
              lifeStageName: _requiredString(plant['lifeStageName']),
              adoptedDate: _optionalDate(plant['adoptedDate']),
              thumbnailUrl: resolveMediaUrl(
                _optionalString(photo?['thumbnailUrl']) ??
                    _optionalString(photo?['originalUrl']),
              ),
            );
          })
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> deletePlant(String plantId) async {
    try {
      await _dio.delete<Object?>('plants/$plantId');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> sendRepottingReminder(String plantId) async {
    try {
      await _dio.post<Object?>('plants/$plantId/repot-reminder');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<PlantDetail> getPlantDetail(String plantId) async {
    try {
      final response = await _dio.get<Object?>('plants/$plantId');
      return _parsePlantDetail(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<PlantDetail> updatePlant({
    required String plantId,
    String? name,
    DateTime? adoptedDate,
  }) async {
    try {
      final response = await _dio.patch<Object?>(
        'plants/$plantId',
        data: {
          'name': ?name,
          if (adoptedDate != null) 'adoptedDate': _dateParameter(adoptedDate),
        },
      );
      return _parsePlantDetail(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<PlantDetail> changeLifeStage({
    required String plantId,
    required String lifeStageId,
  }) async {
    try {
      final response = await _dio.patch<Object?>(
        'plants/$plantId/growth-stage',
        data: {'lifeStageId': lifeStageId},
      );
      return _parsePlantDetail(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<List<GrowthStageOption>> getGrowthStages(String speciesId) async {
    try {
      final response = await _dio.get<Object?>(
        'plant-species/$speciesId/growth-stages',
      );
      final data = _asMap(response.data);
      return _asList(data['growthStages'])
          .map(_parseGrowthStage)
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<GrowthProfile> getGrowthProfile(String plantId) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/growth-profile',
      );
      return _parseGrowthProfile(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<GrowthProfile> updateGrowthProfile({
    required String plantId,
    required GrowthProfileUpdate update,
  }) async {
    try {
      final response = await _dio.patch<Object?>(
        'plants/$plantId/growth-profile',
        data: update.toJson(),
      );
      return _parseGrowthProfile(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<GrowthProfile> resetGrowthProfile(String plantId) async {
    try {
      final response = await _dio.post<Object?>(
        'plants/$plantId/growth-profile/reset',
      );
      return _parseGrowthProfile(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  PlantDetail _parsePlantDetail(Object? value) {
    final data = _asMap(value);
    final species = _asMap(data['species']);
    final category = _asMap(data['category']);
    final photo = _nullableMap(data['representativePhoto']);
    return PlantDetail(
      plantId: _requiredString(data['plantId']),
      name: _requiredString(data['name']),
      speciesId: _requiredString(species['speciesId']),
      speciesName: _requiredString(species['name']),
      categoryName: _requiredString(category['name']),
      lifeStage: _parseGrowthStage(data['lifeStage']),
      adoptedDate: _optionalDate(data['adoptedDate']),
      thumbnailUrl: resolveMediaUrl(
        _optionalString(photo?['thumbnailUrl']) ??
            _optionalString(photo?['originalUrl']),
      ),
      persona: _parsePersona(data['persona']),
    );
  }

  /// 성격을 읽는다. 없으면 null 이다.
  ///
  /// 없다고 오류로 보지 않는다. 페르소나가 없는 종이 있을 수 있고, 서버가 아직 이 필드를
  /// 내보내지 않는 배포에서도 프로필 화면 전체가 깨지면 안 된다.
  SpeciesPersona? _parsePersona(Object? value) {
    final persona = _nullableMap(value);
    if (persona == null) {
      return null;
    }
    return SpeciesPersona(
      characterName: _optionalString(persona['characterName']) ?? '',
      flowerMeaning: _optionalString(persona['flowerMeaning']) ?? '',
      tags: _asList(persona['tags'])
          .map((tag) => tag?.toString().trim() ?? '')
          .where((tag) => tag.isNotEmpty)
          .toList(growable: false),
      personality: _optionalString(persona['personality']) ?? '',
      coreValue: _optionalString(persona['coreValue']) ?? '',
    );
  }

  GrowthProfile _parseGrowthProfile(Object? value) {
    final data = _asMap(value);
    final soil = _nullableMap(data['soilMoisture']);
    final watering = _nullableMap(data['watering']);
    final temperature = _nullableMap(data['temperature']);
    final humidity = _nullableMap(data['humidity']);
    final illuminance = _nullableMap(data['illuminance']);
    final dailyLight = _nullableMap(data['dailyLight']);
    return GrowthProfile(
      plantId: _requiredString(data['plantId']),
      customized: data['customized'] == true,
      dailyLightMinLuxHour: _optionalDouble(dailyLight?['minLuxHour']),
      dailyLightMaxLuxHour: _optionalDouble(dailyLight?['maxLuxHour']),
      dailyLightTargetLuxHour: _optionalDouble(dailyLight?['targetLuxHour']),
      soilMoistureMinPct: _optionalDouble(soil?['minPct']),
      soilMoistureMaxPct: _optionalDouble(soil?['maxPct']),
      temperatureMinC: _optionalDouble(temperature?['minC']),
      temperatureMaxC: _optionalDouble(temperature?['maxC']),
      humidityMinPct: _optionalDouble(humidity?['minPct']),
      humidityMaxPct: _optionalDouble(humidity?['maxPct']),
      illuminanceMinLux: _optionalDouble(illuminance?['minLux']),
      illuminanceMaxLux: _optionalDouble(illuminance?['maxLux']),
      recommendedWateringMl: _optionalDouble(watering?['recommendedVolumeMl']),
      wateringCycleDays: _optionalDouble(watering?['cycleDays']),
    );
  }

  GrowthStageOption _parseGrowthStage(Object? value) {
    final stage = _asMap(value);
    return GrowthStageOption(
      lifeStageId: _requiredString(stage['lifeStageId']),
      code: _requiredString(stage['code']),
      name: _requiredString(stage['name']),
      description: _optionalString(stage['description']),
    );
  }

  double? _optionalDouble(Object? value) {
    if (value == null) {
      return null;
    }
    if (value is num) {
      return value.toDouble();
    }
    final parsed = double.tryParse(value.toString());
    if (parsed == null) {
      throw const FormatException('Expected a number.');
    }
    return parsed;
  }

  PlantSpeciesOption _parseSpecies(Object? value) {
    final species = _asMap(value);
    return PlantSpeciesOption(
      speciesId: _requiredString(species['speciesId']),
      name: _requiredString(species['name']),
      scientificName: _optionalString(species['scientificName']),
      description: _optionalString(species['description']),
      growthStages: _asList(species['growthStages'])
          .map(_parseGrowthStage)
          .toList(growable: false),
    );
  }

  Map<Object?, Object?> _asMap(Object? value) {
    if (value is Map<Object?, Object?>) {
      return value;
    }
    if (value is Map) {
      return Map<Object?, Object?>.from(value);
    }
    throw const FormatException('Expected a JSON object.');
  }

  Map<Object?, Object?>? _nullableMap(Object? value) {
    if (value == null) {
      return null;
    }
    return _asMap(value);
  }

  DateTime? _optionalDate(Object? value) {
    final text = _optionalString(value);
    if (text == null) {
      return null;
    }
    final parsed = DateTime.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date.');
    }
    return parsed;
  }

  List<Object?> _asList(Object? value) {
    if (value is List) {
      return List<Object?>.from(value);
    }
    throw const FormatException('Expected a JSON list.');
  }

  String _requiredString(Object? value) {
    final parsed = _optionalString(value);
    if (parsed == null) {
      throw const FormatException('Expected a non-empty string.');
    }
    return parsed;
  }

  String? _optionalString(Object? value) {
    return value is String && value.trim().isNotEmpty ? value : null;
  }

  String _dateParameter(DateTime value) {
    final month = value.month.toString().padLeft(2, '0');
    final day = value.day.toString().padLeft(2, '0');
    return '${value.year}-$month-$day';
  }
}
