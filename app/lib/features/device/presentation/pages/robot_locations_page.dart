import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/command/presentation/widgets/mapping_card.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/device/presentation/pages/device_management_page.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final robotLocationsProvider = FutureProvider.autoDispose
    .family<List<RobotLocationInfo>, String>((ref, robotId) {
      return ref.watch(deviceRepositoryProvider).getRobotLocations(robotId);
    });

/// 로봇 위치 설정이다. 스테이션·대기 장소·햇빛 자리·마중 지점을
/// 등록하고 RViz 에서 읽은 좌표를 입력한다.
class RobotLocationsPage extends ConsumerWidget {
  const RobotLocationsPage({required this.robotId, super.key});

  final String robotId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final locations = ref.watch(robotLocationsProvider(robotId));

    return Scaffold(
      appBar: AppBar(
        title: const Text('스테이션·위치 설정'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('robot_locations_back'),
          onPressed: () =>
              context.go(withCurrentNavigationOrigin(context, '/devices')),
        ),
      ),
      body: SafeArea(
        child: locations.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '위치 정보를 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('robot_locations_retry'),
                    onPressed: () =>
                        ref.invalidate(robotLocationsProvider(robotId)),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (items) {
            final byType = <RobotLocationType, RobotLocationInfo>{
              for (final location in items) location.type: location,
            };
            return SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 480),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // 좌표는 지도 위의 점이므로 지도가 먼저다. 그래서 위치 목록보다 위에 둔다.
                      _MappingSection(robotId: robotId),
                      const Text(
                        '로봇이 오가는 위치를 등록하고 지도의 좌표를 넣어 주세요.\n'
                        '좌표가 없는 위치로는 로봇을 보낼 수 없어요.',
                        style: TextStyle(
                          color: AppColors.textMuted,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 18),
                      for (final type in RobotLocationType.values) ...[
                        _LocationCard(
                          robotId: robotId,
                          type: type,
                          location: byType[type],
                        ),
                        const SizedBox(height: 14),
                      ],
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// 지도 제작 카드를 로봇 정보와 함께 띄운다.
///
/// 명령은 식물 단위로 나가는데 이 화면은 로봇만 안다. 목록에서 이 로봇을 찾아 배정된 식물을
/// 얻는다 — 로봇 목록은 이미 다른 화면이 쓰는 조회라 요청이 새로 늘지 않는다.
/// 목록을 못 불러오면 카드를 감춘다. 지도 제작은 위치 설정의 선행 단계일 뿐이라, 여기서
/// 실패 문구를 띄우면 정작 필요한 좌표 입력이 가려진다.
class _MappingSection extends ConsumerWidget {
  const _MappingSection({required this.robotId});

  final String robotId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final robots = ref.watch(robotsProvider);
    return robots.maybeWhen(
      data: (items) {
        final robot = items.where((it) => it.robotId == robotId).firstOrNull;
        if (robot == null) {
          return const SizedBox.shrink();
        }
        return MappingCard(robot: robot);
      },
      orElse: () => const SizedBox.shrink(),
    );
  }
}

class _LocationCard extends ConsumerWidget {
  const _LocationCard({
    required this.robotId,
    required this.type,
    required this.location,
  });

  final String robotId;
  final RobotLocationType type;
  final RobotLocationInfo? location;

  IconData get _icon => switch (type) {
    RobotLocationType.waterStation => Icons.water_drop_outlined,
    RobotLocationType.home => Icons.home_outlined,
    RobotLocationType.sunlight => Icons.wb_sunny_outlined,
    RobotLocationType.greeting => Icons.waving_hand_outlined,
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final registered = location;
    return Container(
      key: Key('location_card_${type.name}'),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(22),
        boxShadow: [
          BoxShadow(
            color: AppColors.primary.withValues(alpha: 0.07),
            blurRadius: 14,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: const Color(0xFFE4F0DC),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(_icon, size: 22, color: AppColors.primary),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          type.label,
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        if (registered != null && registered.waterLow) ...[
                          const SizedBox(width: 8),
                          Container(
                            key: const Key('water_low_badge'),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: const Color(0xFFFBE4E4),
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: const Text(
                              '물 부족',
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w800,
                                color: AppColors.error,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(
                      type.description,
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (registered == null)
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.tonal(
                key: Key('register_location_${type.name}'),
                style: FilledButton.styleFrom(minimumSize: const Size(100, 40)),
                onPressed: () => _register(context, ref),
                child: const Text('등록'),
              ),
            )
          else ...[
            if (registered.stationCode != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text(
                  '스테이션 코드  ${registered.stationCode}',
                  style: const TextStyle(
                    fontSize: 13,
                    fontFamily: 'monospace',
                    color: AppColors.textMuted,
                  ),
                ),
              ),
            Row(
              children: [
                Expanded(
                  child: registered.poseConfigured
                      ? Text(
                          '좌표  x ${_trim(registered.poseX)} · '
                          'y ${_trim(registered.poseY)} · '
                          'yaw ${_trim(registered.poseYaw)}',
                          style: const TextStyle(fontSize: 13),
                        )
                      : const Text(
                          '좌표 입력 전이에요',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                            color: Color(0xFFB07E09),
                          ),
                        ),
                ),
                FilledButton.tonal(
                  key: Key('edit_pose_${type.name}'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(100, 40),
                  ),
                  onPressed: () => _editPose(context, ref, registered),
                  child: Text(registered.poseConfigured ? '좌표 수정' : '좌표 입력'),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  static String _trim(double? value) {
    if (value == null) {
      return '-';
    }
    return value == value.roundToDouble()
        ? value.round().toString()
        : value.toStringAsFixed(2);
  }

  Future<void> _register(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    String? stationCode;
    if (type.needsStationCode) {
      stationCode = await showDialog<String>(
        context: context,
        builder: (dialogContext) => const _StationCodeDialog(),
      );
      if (stationCode == null) {
        return;
      }
    }
    if (!context.mounted) {
      return;
    }
    try {
      await ref
          .read(deviceRepositoryProvider)
          .registerRobotLocation(
            robotId: robotId,
            type: type,
            stationCode: stationCode,
          );
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text('${type.label}을(를) 등록했어요. 좌표를 입력해 주세요.')),
        );
    } catch (error) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '위치를 등록하지 못했습니다.'))),
        );
    } finally {
      ref.invalidate(robotLocationsProvider(robotId));
    }
  }

  Future<void> _editPose(
    BuildContext context,
    WidgetRef ref,
    RobotLocationInfo current,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    final pose = await showDialog<({double x, double y, double yaw})>(
      context: context,
      builder: (dialogContext) => _PoseDialog(
        typeLabel: type.label,
        initialX: current.poseX,
        initialY: current.poseY,
        initialYaw: current.poseYaw,
      ),
    );
    if (pose == null || !context.mounted) {
      return;
    }
    try {
      await ref
          .read(deviceRepositoryProvider)
          .updateLocationPose(
            robotId: robotId,
            type: type,
            x: pose.x,
            y: pose.y,
            yaw: pose.yaw,
          );
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('${type.label} 좌표를 저장했어요.')));
    } catch (error) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '좌표를 저장하지 못했습니다.'))),
        );
    } finally {
      ref.invalidate(robotLocationsProvider(robotId));
    }
  }
}

class _StationCodeDialog extends StatefulWidget {
  const _StationCodeDialog();

  @override
  State<_StationCodeDialog> createState() => _StationCodeDialogState();
}

class _StationCodeDialogState extends State<_StationCodeDialog> {
  static final _codePattern = RegExp(r'^[A-Za-z0-9][A-Za-z0-9_-]*$');
  final _controller = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('스테이션 등록'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '급수·촬영·송풍을 하는 스테이션의 고유번호를 입력해 주세요.\n'
            '물 부족 보고가 이 코드로 연결돼요.',
            style: TextStyle(color: AppColors.textMuted, height: 1.5),
          ),
          const SizedBox(height: 14),
          TextField(
            key: const Key('station_code_input'),
            controller: _controller,
            maxLength: 50,
            decoration: InputDecoration(
              hintText: '스테이션 코드',
              counterText: '',
              errorText: _error,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          key: const Key('station_code_cancel'),
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('취소'),
        ),
        FilledButton(
          key: const Key('station_code_submit'),
          onPressed: () {
            final code = _controller.text.trim();
            if (!_codePattern.hasMatch(code)) {
              setState(() => _error = '코드는 영문·숫자로 시작하고 영문·숫자·하이픈·밑줄만 쓸 수 있어요.');
              return;
            }
            Navigator.of(context).pop(code);
          },
          child: const Text('등록'),
        ),
      ],
    );
  }
}

class _PoseDialog extends StatefulWidget {
  const _PoseDialog({
    required this.typeLabel,
    this.initialX,
    this.initialY,
    this.initialYaw,
  });

  final String typeLabel;
  final double? initialX;
  final double? initialY;
  final double? initialYaw;

  @override
  State<_PoseDialog> createState() => _PoseDialogState();
}

class _PoseDialogState extends State<_PoseDialog> {
  late final TextEditingController _x;
  late final TextEditingController _y;
  late final TextEditingController _yaw;
  String? _error;

  @override
  void initState() {
    super.initState();
    _x = TextEditingController(text: _initial(widget.initialX));
    _y = TextEditingController(text: _initial(widget.initialY));
    _yaw = TextEditingController(text: _initial(widget.initialYaw));
  }

  static String _initial(double? value) => value?.toString() ?? '';

  @override
  void dispose() {
    _x.dispose();
    _y.dispose();
    _yaw.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('${widget.typeLabel} 좌표'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'RViz 에서 해당 지점에 커서를 올리면 보이는 map 좌표를 넣어 주세요.\n'
            'x·y 는 m, yaw 는 rad(-3.1416 ~ 3.1416)예요.',
            style: TextStyle(color: AppColors.textMuted, height: 1.5),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(child: _numberField(_x, 'x', const Key('pose_x'))),
              const SizedBox(width: 10),
              Expanded(child: _numberField(_y, 'y', const Key('pose_y'))),
              const SizedBox(width: 10),
              Expanded(child: _numberField(_yaw, 'yaw', const Key('pose_yaw'))),
            ],
          ),
          if (_error != null) ...[
            const SizedBox(height: 10),
            Text(
              _error!,
              style: const TextStyle(fontSize: 12, color: AppColors.error),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          key: const Key('pose_cancel'),
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('취소'),
        ),
        FilledButton(
          key: const Key('pose_submit'),
          onPressed: _submit,
          child: const Text('저장'),
        ),
      ],
    );
  }

  Widget _numberField(TextEditingController controller, String label, Key key) {
    return TextField(
      key: key,
      controller: controller,
      keyboardType: const TextInputType.numberWithOptions(
        decimal: true,
        signed: true,
      ),
      inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.\-]'))],
      decoration: InputDecoration(labelText: label, isDense: true),
    );
  }

  void _submit() {
    final x = double.tryParse(_x.text.trim());
    final y = double.tryParse(_y.text.trim());
    final yaw = double.tryParse(_yaw.text.trim());
    if (x == null || y == null || yaw == null) {
      setState(() => _error = '세 값을 모두 숫자로 입력해 주세요.');
      return;
    }
    if (yaw < -3.1416 || yaw > 3.1416) {
      setState(() => _error = 'yaw 는 -3.1416 ~ 3.1416 범위여야 해요.');
      return;
    }
    Navigator.of(context).pop((x: x, y: y, yaw: yaw));
  }
}
