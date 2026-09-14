import 'package:potner_app/features/bloom/domain/bloom_models.dart';
import 'package:potner_app/features/bloom/domain/bloom_repository.dart';

class FakeBloomRepository implements BloomRepository {
  FakeBloomRepository({List<BloomRecord>? blooms})
    : blooms = List.of(blooms ?? _defaultBlooms());

  final List<BloomRecord> blooms;
  final List<RecordBloomCall> recordCalls = [];
  final List<String> deletedBloomIds = [];

  static List<BloomRecord> _defaultBlooms() {
    return [
      BloomRecord(
        bloomId: 'bloom-1',
        plantId: 'plant-rose',
        plantName: '로지',
        bloomDate: DateTime(2026, 7, 26),
        isUserRecorded: true,
        read: true,
        note: '첫 꽃망울!',
      ),
    ];
  }

  @override
  Future<List<BloomRecord>> getBlooms() async => List.of(blooms);

  @override
  Future<BloomRecord> recordBloom({
    required String plantId,
    DateTime? bloomDate,
    String? note,
  }) async {
    recordCalls.add(
      RecordBloomCall(plantId: plantId, bloomDate: bloomDate, note: note),
    );
    final bloom = BloomRecord(
      bloomId: 'bloom-new-${recordCalls.length}',
      plantId: plantId,
      plantName: '로지',
      bloomDate: bloomDate ?? DateTime(2026, 7, 30),
      isUserRecorded: true,
      read: true,
      note: note,
    );
    blooms.insert(0, bloom);
    return bloom;
  }

  @override
  Future<void> deleteBloom({
    required String plantId,
    required String bloomId,
  }) async {
    deletedBloomIds.add(bloomId);
    blooms.removeWhere((bloom) => bloom.bloomId == bloomId);
  }

  @override
  Future<void> markRead(String bloomId) async {
    readBloomIds.add(bloomId);
  }

  final List<String> readBloomIds = [];
}

class RecordBloomCall {
  const RecordBloomCall({required this.plantId, this.bloomDate, this.note});

  final String plantId;
  final DateTime? bloomDate;
  final String? note;
}
