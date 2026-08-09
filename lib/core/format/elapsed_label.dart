/// 측정 시각을 '얼마나 지났는지' 로 옮긴다.
///
/// 홈과 환경 정보가 같은 함수를 쓴다. 두 화면이 같은 센서값을 보여주는데 한쪽은
/// '3시간 전 업데이트', 다른 쪽은 '오래됨' 이라고 쓰면 사용자는 서로 다른 데이터로 읽는다.
///
/// 시각 비교는 UTC 로 한다. 서버가 UTC 로 주고 기기 시간대는 제각각이라, 그대로 빼면
/// 시간대 차이만큼 어긋난 값이 나온다.
String elapsedLabel(DateTime? measuredAt, {String fallback = '업데이트 정보 없음'}) {
  if (measuredAt == null) {
    return fallback;
  }
  final difference = DateTime.now().toUtc().difference(measuredAt.toUtc());
  // 기기 시계가 서버보다 뒤처져 있으면 음수가 나온다. '-3분 전' 대신 방금으로 읽는다.
  if (difference.isNegative || difference.inMinutes < 1) {
    return '방금 업데이트';
  }
  if (difference.inMinutes < 60) {
    return '${difference.inMinutes}분 전 업데이트';
  }
  if (difference.inHours < 24) {
    return '${difference.inHours}시간 전 업데이트';
  }
  return '${difference.inDays}일 전 업데이트';
}

/// 위와 같은 값을 칩 한 칸에 넣을 만큼 줄인 것이다.
///
/// 센서 카드의 상태 칩은 '정상'·'낮음' 과 같은 자리라 '3시간 전 업데이트' 는 넘친다.
String elapsedChipLabel(DateTime? measuredAt) {
  if (measuredAt == null) {
    return '측정 전';
  }
  final difference = DateTime.now().toUtc().difference(measuredAt.toUtc());
  if (difference.isNegative || difference.inMinutes < 1) {
    return '방금';
  }
  if (difference.inMinutes < 60) {
    return '${difference.inMinutes}분 전';
  }
  if (difference.inHours < 24) {
    return '${difference.inHours}시간 전';
  }
  return '${difference.inDays}일 전';
}
