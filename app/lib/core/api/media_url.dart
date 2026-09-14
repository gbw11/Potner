// 서버가 내려준 사진 URL 을 앱이 바로 그릴 수 있는 절대 URL 로 바꾼다.
//
// 사진은 API 와 같은 origin 의 Nginx 가 정적으로 서빙하므로, 서버는 도메인을 모른 채
// `potner.photo.base-url` 기본값을 붙인 상대 경로를 응답한다.
//
//   /media/{plantId}/{photoId}/thumbnail.jpg
//
// `Image.network` 는 스킴이 없는 URL 을 로드할 수 없어, 이 변환이 없으면 사진이 한 장도
// 보이지 않는다. 실패가 errorBuilder 폴백으로 조용히 흡수되어 "사진이 아직 없는 것"과
// 구별되지 않으므로 원인을 찾기 어렵다.
//
// 사진을 CDN 이나 오브젝트 스토리지로 옮겨 PHOTO_BASE_URL 이 절대 URL 이 되면 서버 응답이
// 이미 절대 URL 이라 그대로 통과한다. 그래서 양쪽 설정에서 모두 맞다.

import 'package:potner_app/core/config/app_config.dart';

/// 사진 URL 이 붙을 origin 이다. API base URL 에서 스킴과 호스트만 남긴다.
///
/// 계산에 실패하면 빈 문자열이다. 그 경우 상대 경로를 그대로 돌려주므로 동작이 변환 이전과
/// 같아지고, 잘못된 origin 을 붙여 조용히 엉뚱한 곳을 가리키는 것보다 낫다.
final String mediaOrigin = _originOf(normalizedApiBaseUrl);

String _originOf(String baseUrl) {
  final uri = Uri.tryParse(baseUrl);
  if (uri == null || uri.host.isEmpty) {
    return '';
  }
  // Uri.origin 은 http·https 가 아니면 예외를 던진다.
  if (uri.scheme != 'http' && uri.scheme != 'https') {
    return '';
  }
  return uri.origin;
}

/// 값이 없으면 null 을 돌려준다. 이미 절대 URL 이거나 번들 asset 이면 그대로 둔다.
String? resolveMediaUrl(String? url) {
  final text = url?.trim();
  if (text == null || text.isEmpty) {
    return null;
  }
  // 홈 화면은 같은 위젯으로 번들 이미지도 그린다. asset 경로를 건드리면 안 된다.
  if (text.startsWith('assets/')) {
    return text;
  }
  if (Uri.tryParse(text)?.hasScheme ?? false) {
    return text;
  }
  if (mediaOrigin.isEmpty) {
    return text;
  }
  return text.startsWith('/') ? '$mediaOrigin$text' : '$mediaOrigin/$text';
}

/// 값이 있는 것이 이미 검증된 자리에서 쓴다. 변환에 실패하면 원본을 그대로 돌려준다.
String requireMediaUrl(String url) => resolveMediaUrl(url) ?? url;
