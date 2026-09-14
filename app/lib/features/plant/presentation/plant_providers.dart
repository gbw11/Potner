import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';

/// 나의 식물·등록 식물 화면이 함께 쓰는 목록이다.
/// 등록·삭제 후에는 invalidate 로 다시 불러온다.
final myPlantsProvider = FutureProvider.autoDispose<List<MyPlant>>((ref) {
  return ref.watch(plantRepositoryProvider).getMyPlants();
});

String plantErrorMessage(Object error, String fallback) {
  if (error is ApiException) {
    return switch (error.kind) {
      ApiExceptionKind.connection ||
      ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
      ApiExceptionKind.problem => error.problem?.detail ?? fallback,
      ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
      _ => fallback,
    };
  }
  return fallback;
}
