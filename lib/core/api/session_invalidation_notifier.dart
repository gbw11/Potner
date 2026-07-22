import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

final sessionInvalidationNotifierProvider =
    Provider<SessionInvalidationNotifier>((ref) {
      final notifier = SessionInvalidationNotifier();
      ref.onDispose(notifier.dispose);
      return notifier;
    });

class SessionInvalidationNotifier {
  final StreamController<void> _controller = StreamController<void>.broadcast(
    sync: true,
  );

  Stream<void> get events => _controller.stream;

  void notify() {
    if (!_controller.isClosed) {
      _controller.add(null);
    }
  }

  void dispose() {
    _controller.close();
  }
}
