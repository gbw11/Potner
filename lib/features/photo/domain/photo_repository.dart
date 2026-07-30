import 'package:potner_app/features/photo/domain/photo_models.dart';

abstract class PhotoRepository {
  /// 기간 안의 성장 사진을 오래된 순으로 돌려준다. 양쪽 날짜 모두 포함이다.
  Future<List<PlantPhoto>> getPhotos({
    required String plantId,
    required DateTime from,
    required DateTime to,
  });

  /// 포토 로그의 저장된 사진을 대표 사진으로 지정한다.
  Future<void> selectRepresentativePhoto({
    required String plantId,
    required String photoId,
  });
}
