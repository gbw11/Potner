import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/conversation/domain/conversation_models.dart';

class ConversationApi {
  ConversationApi(this._dio);

  final Dio _dio;

  /// [beforeSeq] 를 주면 그 seq 보다 오래된 것만 받는다. 위로 스크롤할 때 쓴다.
  Future<ConversationMessagePage> getMessages({
    required String plantId,
    int? beforeSeq,
    int? size,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/conversations/messages',
        // 비어 있으면 키를 빼야 한다. null 을 실어 보내면 서버가 400 이다.
        queryParameters: {'beforeSeq': ?beforeSeq, 'size': ?size},
      );
      final data = _asMap(response.data);
      return ConversationMessagePage(
        conversationId: _optionalString(data['conversationId']),
        messages: _asList(data['messages'])
            .map(_parseMessage)
            .toList(growable: false),
        nextBeforeSeq: _optionalInt(data['nextBeforeSeq']),
        hasMore: data['hasMore'] == true,
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  ConversationMessage _parseMessage(Object? value) {
    final message = _asMap(value);
    return ConversationMessage(
      seq: _requiredInt(message['seq']),
      role: _role(message['role']),
      content: _optionalString(message['content']) ?? '',
      createdAt: _requiredDateTime(message['createdAt']),
    );
  }

  /// 모르는 값을 `unknown` 으로 떨어뜨린다. 서버가 역할을 늘려도 앱이 죽지 않아야 한다.
  ConversationRole _role(Object? value) {
    return switch (value) {
      'user' => ConversationRole.user,
      'assistant' => ConversationRole.assistant,
      _ => ConversationRole.unknown,
    };
  }

  Map<Object?, Object?> _asMap(Object? value) {
    if (value is Map<Object?, Object?>) {
      return value;
    }
    if (value is Map) {
      return Map<Object?, Object?>.from(value);
    }
    throw const FormatException('Expected a JSON object.');
  }

  List<Object?> _asList(Object? value) {
    if (value is List) {
      return List<Object?>.from(value);
    }
    throw const FormatException('Expected a JSON list.');
  }

  String? _optionalString(Object? value) {
    return value is String && value.trim().isNotEmpty ? value : null;
  }

  int _requiredInt(Object? value) {
    final parsed = _optionalInt(value);
    if (parsed == null) {
      throw const FormatException('Expected an integer.');
    }
    return parsed;
  }

  int? _optionalInt(Object? value) {
    if (value == null) {
      return null;
    }
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.round();
    }
    return int.tryParse(value.toString());
  }

  DateTime _requiredDateTime(Object? value) {
    final text = _optionalString(value);
    if (text == null) {
      throw const FormatException('Expected an ISO date time.');
    }
    final parsed = DateTime.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date time.');
    }
    if (parsed.isUtc) {
      return parsed;
    }
    // 서버 시각은 UTC 인데 오프셋을 붙이지 않는다. 그대로 두면 로컬로 해석되어 날짜 구분선이
    // 9시간 밀리고, 자정 근처 대화가 전날로 묶인다. alert 조회와 같은 처리다.
    return DateTime.utc(
      parsed.year,
      parsed.month,
      parsed.day,
      parsed.hour,
      parsed.minute,
      parsed.second,
      parsed.millisecond,
      parsed.microsecond,
    );
  }
}
