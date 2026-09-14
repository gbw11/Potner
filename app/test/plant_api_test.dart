import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/plant/data/plant_api.dart';

void main() {
  test('PlantApi maps the category tree and create responses', () async {
    Object? capturedCreateBody;
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1/'));
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          final data = switch (options.path) {
            'plant-categories' => {
              'categories': [
                {
                  'categoryId': 'cat-herb',
                  'name': '허브',
                  'sortOrder': 1,
                  'species': [
                    {
                      'speciesId': 'species-basil',
                      'name': '바질',
                      'scientificName': 'Ocimum basilicum',
                      'description': '요리에 쓰는 향긋한 허브',
                      'growthStages': [
                        {
                          'lifeStageId': 'stage-seedling',
                          'code': 'SEEDLING',
                          'name': '모종기',
                          'description': null,
                          'sortOrder': 1,
                        },
                      ],
                    },
                  ],
                },
              ],
            },
            'plants' => () {
              capturedCreateBody = options.data;
              return {
                'plantId': 'plant-new',
                'name': '바질이',
                'adoptedDate': '2026-07-01',
              };
            }(),
            _ => throw StateError('Unexpected path: ${options.path}'),
          };
          handler.resolve(
            Response<Object?>(
              requestOptions: options,
              statusCode: options.path == 'plants' ? 201 : 200,
              data: data,
            ),
          );
        },
      ),
    );

    final api = PlantApi(dio);

    final categories = await api.getCategoryTree();
    expect(categories, hasLength(1));
    expect(categories.first.categoryId, 'cat-herb');
    expect(categories.first.species.first.name, '바질');
    expect(
      categories.first.species.first.growthStages.first.lifeStageId,
      'stage-seedling',
    );

    final plant = await api.createPlant(
      speciesId: 'species-basil',
      lifeStageId: 'stage-seedling',
      name: '바질이',
      adoptedDate: DateTime(2026, 7, 1),
    );
    expect(plant.plantId, 'plant-new');
    expect(plant.name, '바질이');
    expect(capturedCreateBody, {
      'speciesId': 'species-basil',
      'lifeStageId': 'stage-seedling',
      'name': '바질이',
      'adoptedDate': '2026-07-01',
    });
  });
}
