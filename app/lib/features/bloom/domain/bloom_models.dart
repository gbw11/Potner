/// 개화 기록 한 건이다.
class BloomRecord {
  const BloomRecord({
    required this.bloomId,
    required this.plantId,
    required this.plantName,
    required this.bloomDate,
    required this.isUserRecorded,
    required this.read,
    this.note,
  });

  final String bloomId;
  final String plantId;
  final String plantName;
  final DateTime bloomDate;

  /// 사용자가 직접 남긴 기록이면 true, 장치가 판정한 기록이면 false 다.
  final bool isUserRecorded;
  final bool read;
  final String? note;
}
