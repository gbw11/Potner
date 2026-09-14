import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/core/api/media_url.dart';

/// 서버는 사진 URL 을 API 와 같은 origin 의 상대 경로(`/media/...`)로 내려준다.
/// 그대로 `Image.network` 에 넘기면 스킴이 없어 항상 실패하고, 실패가 errorBuilder 폴백으로
/// 흡수되어 "사진이 아직 없는 것"과 구별되지 않는다. 그래서 변환을 테스트로 고정한다.
void main() {
  test('상대 경로에 API origin 을 붙인다', () {
    expect(
      resolveMediaUrl('/media/plant-1/photo-1/thumbnail.jpg'),
      '$mediaOrigin/media/plant-1/photo-1/thumbnail.jpg',
    );
  });

  test('앞 슬래시가 없어도 경로 구분자를 하나만 넣는다', () {
    expect(
      resolveMediaUrl('media/plant-1/photo-1/original.jpg'),
      '$mediaOrigin/media/plant-1/photo-1/original.jpg',
    );
  });

  test('이미 절대 URL 이면 그대로 둔다', () {
    // PHOTO_BASE_URL 을 CDN 절대 URL 로 바꾸면 서버 응답이 이미 절대 URL 이다.
    const cdn = 'https://cdn.example.com/media/plant-1/photo-1/thumbnail.jpg';
    expect(resolveMediaUrl(cdn), cdn);
  });

  test('번들 asset 경로는 건드리지 않는다', () {
    // 홈 화면이 같은 위젯으로 번들 이미지도 그린다.
    expect(
      resolveMediaUrl('assets/images/home/stitch_rose_photo.jpg'),
      'assets/images/home/stitch_rose_photo.jpg',
    );
  });

  test('값이 없으면 null 이다', () {
    expect(resolveMediaUrl(null), isNull);
    expect(resolveMediaUrl(''), isNull);
    expect(resolveMediaUrl('   '), isNull);
  });

  test('필수 URL 은 변환해서 non-null 로 돌려준다', () {
    expect(
      requireMediaUrl('/media/plant-1/photo-1/playback.jpg'),
      '$mediaOrigin/media/plant-1/photo-1/playback.jpg',
    );
  });

  test('origin 은 API base URL 의 스킴과 호스트만 남긴다', () {
    // 기본 API_BASE_URL 은 에뮬레이터 루프백이다. /api/v1 경로가 떨어져야 /media 와 겹치지 않는다.
    expect(mediaOrigin, isNot(contains('/api/v1')));
    expect(mediaOrigin, anyOf(startsWith('http://'), startsWith('https://')));
  });
}
