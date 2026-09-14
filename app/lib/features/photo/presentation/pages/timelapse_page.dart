import 'dart:async';

import 'package:flutter/material.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/photo/domain/photo_models.dart';
import 'package:potner_app/features/photo/presentation/photo_providers.dart';

/// 성장 사진을 오래된 순으로 넘겨 보는 타임랩스다.
///
/// 서버가 사진마다 재생용 축소본(`playbackUrl`, 긴 변 1280px)을 따로 만들어 둔다. 원본은
/// 한 장에 수 MB 라 30장을 연속으로 넘기면 모바일에서 끊긴다. 축소본이 없는 사진은 썸네일로
/// 떨어뜨린다 — 옛 사진이나 변환에 실패한 사진도 재생에서 빠지지 않게 하려는 것이다.
///
/// 서버는 목록을 **오래된 것부터** 정렬해 주므로 받은 순서를 그대로 쓴다.
///
/// 결측일은 건너뛴다. 로봇이 꺼져 있던 날은 사진이 없어 응답에도 없고, 빈 프레임을 끼우면
/// 재생이 멈춘 것처럼 보인다. 대신 진행 막대 아래에 실제 촬영 일수를 적어 며칠분인지 밝힌다.
class TimelapsePage extends StatefulWidget {
  const TimelapsePage({required this.plantName, required this.photos, super.key});

  final String plantName;
  final List<PlantPhoto> photos;

  @override
  State<TimelapsePage> createState() => _TimelapsePageState();
}

class _TimelapsePageState extends State<TimelapsePage> {
  /// 재생 속도. 8x 는 프레임당 100ms 라 한 달치가 3초에 끝난다 — 성장 과정을 보는 게
  /// 아니라 어디까지 찍혔는지 훑는 용도다.
  static const _speeds = <double>[0.5, 1, 2, 4, 8];

  Timer? _timer;
  int _index = 0;

  /// 2x 로 연다. 하루 한 장씩 쌓이므로 한 달치면 1x 에서 24초인데, 처음 보는 사람이
  /// 끝까지 기다리기엔 길다.
  int _speedIndex = 2;
  bool _isPlaying = true;
  bool _precached = false;

  List<PlantPhoto> get _photos => widget.photos;
  double get _speed => _speeds[_speedIndex];

  String _frameUrl(PlantPhoto photo) => photo.playbackUrl ?? photo.thumbnailUrl;

  @override
  void initState() {
    super.initState();
    _restartTimer();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_precached) {
      return;
    }
    _precached = true;
    // 미리 받아두지 않으면 첫 재생이 프레임마다 멈춘다. 전부 받으면 시작이 늦어지므로
    // 앞쪽 몇 장만 받고 나머지는 재생하며 따라가게 둔다.
    for (final photo in _photos.take(5)) {
      _prefetch(photo);
    }
  }

  /// 미리 받기는 최적화일 뿐이라 실패를 삼킨다. 여기서 예외가 올라오면 재생 화면 전체가
  /// 깨지는데, 정작 그 사진은 `Image.network` 가 다시 받아 보고 실패하면 errorBuilder 가
  /// 처리한다. 네트워크가 불안한 곳에서 타임랩스가 아예 안 열리는 쪽이 더 나쁘다.
  void _prefetch(PlantPhoto photo) {
    unawaited(
      precacheImage(
        NetworkImage(_frameUrl(photo)),
        context,
        onError: (_, _) {},
      ),
    );
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _restartTimer() {
    _timer?.cancel();
    if (!_isPlaying || _photos.length < 2) {
      return;
    }
    final interval = Duration(milliseconds: (800 / _speed).round());
    _timer = Timer.periodic(interval, (_) => _advance());
  }

  void _advance() {
    if (!mounted) {
      return;
    }
    final next = _index + 1;
    if (next >= _photos.length) {
      // 끝나면 멈춘다. 반복 재생하면 마지막 프레임(가장 최근 모습)을 볼 시간이 없다.
      setState(() {
        _index = _photos.length - 1;
        _isPlaying = false;
      });
      _timer?.cancel();
      return;
    }
    setState(() => _index = next);
    _prefetchAhead(next);
  }

  void _prefetchAhead(int from) {
    for (var i = from + 1; i <= from + 3 && i < _photos.length; i++) {
      _prefetch(_photos[i]);
    }
  }

  void _togglePlay() {
    setState(() {
      // 끝까지 본 뒤 다시 누르면 처음부터 재생한다.
      if (!_isPlaying && _index >= _photos.length - 1) {
        _index = 0;
      }
      _isPlaying = !_isPlaying;
    });
    _restartTimer();
  }

  void _cycleSpeed() {
    setState(() => _speedIndex = (_speedIndex + 1) % _speeds.length);
    _restartTimer();
  }

  void _seek(int index) {
    setState(() {
      _index = index.clamp(0, _photos.length - 1);
      _isPlaying = false;
    });
    _timer?.cancel();
    _prefetchAhead(_index);
  }

  @override
  Widget build(BuildContext context) {
    final photo = _photos[_index];
    final speedLabel = _speed == _speed.roundToDouble()
        ? '${_speed.round()}x'
        : '${_speed}x';

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text('${widget.plantName} 타임랩스'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('timelapse_back'),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: Center(
                child: Image.network(
                  _frameUrl(photo),
                  key: const Key('timelapse_frame'),
                  fit: BoxFit.contain,
                  gaplessPlayback: true, // 다음 장을 받는 동안 이전 장을 남겨 깜빡임을 막는다
                  errorBuilder: (_, _, _) => const Center(
                    child: Icon(
                      Icons.image_not_supported_outlined,
                      color: Colors.white38,
                      size: 48,
                    ),
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
              child: Column(
                children: [
                  Text(
                    photoDateLabel(photo.photoDate),
                    key: const Key('timelapse_date'),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${_index + 1} / ${_photos.length}일차',
                    style: const TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                  Slider(
                    key: const Key('timelapse_slider'),
                    value: _index.toDouble(),
                    min: 0,
                    max: (_photos.length - 1).toDouble(),
                    divisions: _photos.length > 1 ? _photos.length - 1 : null,
                    activeColor: AppColors.primarySoft,
                    inactiveColor: Colors.white24,
                    onChanged: (value) => _seek(value.round()),
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      IconButton(
                        key: const Key('timelapse_restart'),
                        onPressed: () => _seek(0),
                        color: Colors.white,
                        icon: const Icon(Icons.replay_rounded),
                        tooltip: '처음으로',
                      ),
                      const SizedBox(width: 8),
                      FilledButton.icon(
                        key: const Key('timelapse_play'),
                        // 테마의 minimumSize 가 Size.fromHeight(56) 이라 기본값이면 가로를
                        // 꽉 채우려 한다. Row 안에서는 그 폭이 무한이 되어 깨진다.
                        style: FilledButton.styleFrom(
                          minimumSize: const Size(0, 44),
                          padding: const EdgeInsets.symmetric(horizontal: 20),
                        ),
                        onPressed: _togglePlay,
                        icon: Icon(
                          _isPlaying
                              ? Icons.pause_rounded
                              : Icons.play_arrow_rounded,
                        ),
                        label: Text(_isPlaying ? '일시정지' : '재생'),
                      ),
                      const SizedBox(width: 8),
                      TextButton(
                        key: const Key('timelapse_speed'),
                        onPressed: _cycleSpeed,
                        style: TextButton.styleFrom(
                          foregroundColor: Colors.white,
                        ),
                        child: Text(speedLabel),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
