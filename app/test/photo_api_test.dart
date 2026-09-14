import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/photo/data/photo_api.dart';

void main() {
  test(
    'PhotoApi uploads a representative photo as multipart form data',
    () async {
      final temporaryDirectory = await Directory.systemTemp.createTemp(
        'potner-photo-api-test-',
      );
      addTearDown(() => temporaryDirectory.delete(recursive: true));
      final photoFile = File('${temporaryDirectory.path}/plant.jpg');
      await photoFile.writeAsBytes([0xFF, 0xD8, 0xFF, 0xD9]);

      String? capturedPath;
      FormData? capturedBody;
      final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1/'));
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            capturedPath = options.path;
            capturedBody = options.data as FormData;
            handler.resolve(
              Response<Object?>(
                requestOptions: options,
                statusCode: 201,
                data: const <String, Object?>{},
              ),
            );
          },
        ),
      );

      await PhotoApi(dio).uploadRepresentativePhoto(
        plantId: 'plant-new',
        filePath: photoFile.path,
        fileName: 'plant.jpg',
      );

      expect(capturedPath, 'plants/plant-new/representative-photo');
      expect(capturedBody, isNotNull);
      expect(capturedBody!.files, hasLength(1));
      expect(capturedBody!.files.single.key, 'file');
      expect(capturedBody!.files.single.value.filename, 'plant.jpg');
    },
  );
}
