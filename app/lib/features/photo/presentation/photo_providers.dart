import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/features/photo/data/photo_repository_impl.dart';
import 'package:potner_app/features/photo/domain/photo_models.dart';

/// 식물의 전체 성장 사진이다(오래된 순). 포토 로그·상세·비교 화면이 함께 쓴다.
/// 시작일을 넉넉히 잡는 것은 입양일이 없는 식물도 있기 때문이다.
final plantPhotosProvider = FutureProvider.autoDispose
    .family<List<PlantPhoto>, String>((ref, plantId) {
      final now = DateTime.now();
      return ref
          .read(photoRepositoryProvider)
          .getPhotos(
            plantId: plantId,
            from: DateTime(2000, 1, 1),
            to: DateTime(now.year, now.month, now.day),
          );
    });

String photoDateLabel(DateTime date) {
  return '${date.year}년 ${date.month}월 ${date.day}일';
}

String photoDateShort(DateTime date) {
  final month = date.month.toString().padLeft(2, '0');
  final day = date.day.toString().padLeft(2, '0');
  return '${date.year}.$month.$day';
}
