import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/home/data/home_api.dart';
import 'package:potner_app/features/home/domain/home_dashboard.dart';

void main() {
  test('HomeApi maps the backend home responses', () async {
    Map<String, dynamic>? capturedPhotoQuery;
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1/'));
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          final data = switch (options.path) {
            'plants' => {
              'plants': [
                {
                  'plantId': 'plant-1',
                  'name': '로지',
                  'adoptedDate': null,
                  'createdAt': '2026-01-10T09:30:00',
                  'representativePhoto': {
                    'thumbnailUrl': 'https://images.test/rose-thumb.jpg',
                    'originalUrl': 'https://images.test/rose.jpg',
                  },
                },
              ],
            },
            'plants/plant-1/happiness' => {
              'grade': 'GOOD',
              'headline': '아주 좋아요!',
              'detail': '햇살도 물도 딱 좋아요 :)',
            },
            'plants/plant-1/sensors/current' => {
              'plantId': 'plant-1',
              'sensors': [
                {
                  'sensorType': 'ILLUMINANCE',
                  'unit': 'LUX',
                  'value': 1250,
                  'measuredAt': '2026-07-30T03:00:00',
                  'status': 'NOT_APPLICABLE',
                },
              ],
            },
            'plants/plant-1/photos' => () {
              capturedPhotoQuery = Map<String, dynamic>.from(
                options.queryParameters,
              );
              return {
                'photos': [
                  {
                    'thumbnailUrl': 'https://images.test/old.jpg',
                    'originalUrl': 'https://images.test/old-original.jpg',
                  },
                  {
                    'thumbnailUrl': 'https://images.test/latest.jpg',
                    'originalUrl': 'https://images.test/latest-original.jpg',
                  },
                ],
              };
            }(),
            'alerts' => {'unreadCount': 2},
            _ => throw StateError('Unexpected path: ${options.path}'),
          };
          handler.resolve(
            Response<Object?>(
              requestOptions: options,
              statusCode: 200,
              data: data,
            ),
          );
        },
      ),
    );
    final api = HomeApi(dio);

    final plants = await api.getPlants();
    final mood = await api.getMood(plants.single.id);
    final sensors = await api.getCurrentSensors(plants.single.id);
    final latestPhoto = await api.getLatestPhoto(plants.single);
    final unreadCount = await api.getUnreadAlertCount();

    expect(plants.single.name, '로지');
    expect(plants.single.adoptedDate, isNull);
    expect(plants.single.createdAt, DateTime(2026, 1, 10, 9, 30));
    expect(plants.single.imageUrl, 'https://images.test/rose.jpg');
    expect(mood.headline, '아주 좋아요!');
    expect(sensors.single.type, HomeSensorType.illuminance);
    expect(sensors.single.status, HomeSensorStatus.notApplicable);
    expect(sensors.single.measuredAt, DateTime.utc(2026, 7, 30, 3));
    expect(latestPhoto, 'https://images.test/latest.jpg');
    expect(capturedPhotoQuery?['from'], '2026-01-10');
    expect(unreadCount, 2);
  });
}
