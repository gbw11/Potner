import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
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

  PlantSpeciesOption _parseSpecies(Object? value) {
    final species = _asMap(value);
    return PlantSpeciesOption(
      speciesId: _requiredString(species['speciesId']),
      name: _requiredString(species['name']),
      scientificName: _optionalString(species['scientificName']),
      description: _optionalString(species['description']),
      growthStages: _asList(species['growthStages'])
          .map((item) {
            final stage = _asMap(item);
            return GrowthStageOption(
              lifeStageId: _requiredString(stage['lifeStageId']),
              code: _requiredString(stage['code']),
              name: _requiredString(stage['name']),
              description: _optionalString(stage['description']),
            );
          })
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
