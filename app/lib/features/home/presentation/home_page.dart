import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/navigation/app_menu_button.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/device/presentation/controllers/robot_status_controller.dart';
import 'package:potner_app/features/home/domain/home_dashboard.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/home/presentation/widgets/robot_status_button.dart';

class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(authControllerProvider).user;
    final home = ref.watch(homeControllerProvider);
    final robotStatus = ref.watch(robotStatusControllerProvider);
    final header = _HomeHeader(
      onAlerts: () =>
          context.go(withNavigationOrigin('/alerts', AppNavigationOrigin.home)),
      onMy: () => context.go(
        withNavigationOrigin('/my/profile', AppNavigationOrigin.home),
      ),
      hasUnreadAlerts: home.unreadAlertCount > 0,
      robotStatusButton: RobotStatusButton(status: robotStatus),
    );

    Widget withHeader(Widget child) {
      return Column(
        children: [
          header,
          Expanded(child: child),
        ],
      );
    }

    return Scaffold(
      key: const Key('home_page'),
      backgroundColor: const Color(0xFFF8F6F1),
      body: SafeArea(
        child: switch (home.status) {
          HomeStatus.loading => withHeader(const _LoadingView()),
          HomeStatus.empty => withHeader(
            _EmptyView(
              nickname: user?.nickname,
              onRegisterPlant: () => context.go('/plants/register'),
            ),
          ),
          HomeStatus.failure => withHeader(
            _FailureView(
              nickname: user?.nickname,
              message: home.message,
              onRetry: () => ref.read(homeControllerProvider.notifier).load(),
            ),
          ),
          HomeStatus.loaded => _DashboardView(
            header: header,
            nickname: user?.nickname,
            state: home,
            onRefresh: () =>
                ref.read(homeControllerProvider.notifier).refresh(),
            onPlantSelected: (plantId) =>
                ref.read(homeControllerProvider.notifier).selectPlant(plantId),
            onRegisterPlant: () => context.go('/plants/register'),
            onRegisterDevice: () => context.go(
              withNavigationOrigin(
                '/devices/register',
                AppNavigationOrigin.home,
              ),
            ),
            onPlantDetail: () {
              final plantId = home.dashboard?.plant.id;
              if (plantId != null) {
                context.go('/plants/$plantId');
              }
            },
            onPhotoLog: () => context.go(
              withNavigationOrigin('/growth/photos', AppNavigationOrigin.home),
            ),
            onCareSettings: () {
              final plantId = home.dashboard?.plant.id;
              if (plantId != null) {
                context.go(
                  withNavigationOrigin(
                    '/plants/$plantId/care',
                    AppNavigationOrigin.home,
                  ),
                );
              }
            },
          ),
        },
      ),
    );
  }
}

class _HomeHeader extends StatelessWidget {
  const _HomeHeader({
    required this.onAlerts,
    required this.onMy,
    required this.hasUnreadAlerts,
    required this.robotStatusButton,
  });

  final VoidCallback onAlerts;
  final VoidCallback onMy;
  final bool hasUnreadAlerts;
  final Widget robotStatusButton;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 12, 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Align(
              alignment: Alignment.centerLeft,
              child: Transform.translate(
                offset: const Offset(-30, 0),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 140),
                  child: SizedBox(
                    height: 88,
                    child: Image.asset(
                      'assets/images/auth/potner_logo_login.png',
                      fit: BoxFit.contain,
                      semanticLabel: 'Potner',
                    ),
                  ),
                ),
              ),
            ),
          ),
          Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Stack(
                    clipBehavior: Clip.none,
                    children: [
                      IconButton(
                        key: const Key('home_alerts_button'),
                        tooltip: '알림',
                        visualDensity: VisualDensity.compact,
                        onPressed: onAlerts,
                        icon: const Icon(
                          Icons.notifications_none_rounded,
                          color: Color(0xFF2D2D2D),
                          size: 26,
                        ),
                      ),
                      if (hasUnreadAlerts)
                        const Positioned(
                          right: 9,
                          top: 8,
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: Color(0xFFFFC107),
                              shape: BoxShape.circle,
                              border: Border.fromBorderSide(
                                BorderSide(
                                  color: Color(0xFFF8F6F1),
                                  width: 1.5,
                                ),
                              ),
                            ),
                            child: SizedBox(width: 8, height: 8),
                          ),
                        ),
                    ],
                  ),
                  robotStatusButton,
                  IconButton(
                    key: const Key('home_my_button'),
                    tooltip: '마이페이지',
                    visualDensity: VisualDensity.compact,
                    onPressed: onMy,
                    icon: const Icon(
                      Icons.account_circle_outlined,
                      color: Color(0xFF2D2D2D),
                      size: 26,
                    ),
                  ),
                  const SizedBox(width: 42, height: 42, child: AppMenuButton()),
                ],
              ),
              const _BatteryBadge(),
            ],
          ),
        ],
      ),
    );
  }
}

class _BatteryBadge extends StatelessWidget {
  const _BatteryBadge();

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(right: 8),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.62),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white),
      ),
      child: const Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            '100%',
            style: TextStyle(
              color: AppColors.primary,
              fontSize: 10,
              height: 1,
              fontWeight: FontWeight.w700,
            ),
          ),
          SizedBox(width: 5),
          _BatteryIcon(),
        ],
      ),
    );
  }
}

class _BatteryIcon extends StatelessWidget {
  const _BatteryIcon();

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 23,
      height: 11,
      child: Stack(
        alignment: Alignment.centerLeft,
        children: [
          Positioned(
            left: 0,
            top: 0,
            bottom: 0,
            child: Container(
              width: 20,
              padding: const EdgeInsets.all(1.5),
              decoration: BoxDecoration(
                border: Border.all(color: AppColors.primary, width: 1),
                borderRadius: BorderRadius.circular(2),
              ),
              child: const ColoredBox(color: AppColors.primary),
            ),
          ),
          const Positioned(
            right: 0,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: AppColors.primary,
                borderRadius: BorderRadius.horizontal(
                  right: Radius.circular(1),
                ),
              ),
              child: SizedBox(width: 2, height: 5),
            ),
          ),
        ],
      ),
    );
  }
}

class _DashboardView extends StatelessWidget {
  const _DashboardView({
    required this.header,
    required this.nickname,
    required this.state,
    required this.onRefresh,
    required this.onPlantSelected,
    required this.onRegisterPlant,
    required this.onRegisterDevice,
    required this.onPlantDetail,
    required this.onPhotoLog,
    required this.onCareSettings,
  });

  final Widget header;
  final String? nickname;
  final HomeState state;
  final Future<void> Function() onRefresh;
  final ValueChanged<String> onPlantSelected;
  final VoidCallback onRegisterPlant;
  final VoidCallback onRegisterDevice;
  final VoidCallback onPlantDetail;
  final VoidCallback onPhotoLog;
  final VoidCallback onCareSettings;

  @override
  Widget build(BuildContext context) {
    final dashboard = state.dashboard;
    if (dashboard == null) {
      return const _LoadingView();
    }

    return Stack(
      children: [
        RefreshIndicator(
          onRefresh: onRefresh,
          color: AppColors.primary,
          child: ListView(
            key: const Key('home_scroll_view'),
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.only(bottom: 28),
            children: [
              header,
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      nickname == null ? '안녕하세요!' : '안녕하세요, $nickname님!',
                      key: const Key('home_greeting'),
                      style: Theme.of(context).textTheme.headlineLarge
                          ?.copyWith(
                            color: const Color(0xFF2D2D2D),
                            fontSize: 30,
                            height: 1.2,
                            letterSpacing: -0.7,
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                    const SizedBox(height: 16),
                    Wrap(
                      spacing: 12,
                      runSpacing: 8,
                      children: [
                        _QuickAction(
                          key: const Key('home_register_plant'),
                          label: '내 식물 등록하기',
                          icon: Icons.add_circle_outline_rounded,
                          onTap: onRegisterPlant,
                        ),
                        _QuickAction(
                          key: const Key('home_register_device'),
                          label: '디바이스 등록하기',
                          icon: Icons.devices_outlined,
                          onTap: onRegisterDevice,
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    _PlantSelector(
                      plants: state.plants,
                      selectedPlantId: state.selectedPlantId,
                      onSelected: onPlantSelected,
                    ),
                    const SizedBox(height: 20),
                    _StatusHero(
                      dashboard: dashboard,
                      onPlantDetail: onPlantDetail,
                    ),
                    const SizedBox(height: 30),
                    _SensorOverview(dashboard: dashboard),
                    if (state.message != null) ...[
                      const SizedBox(height: 12),
                      _InlineMessage(message: state.message!),
                    ],
                    const SizedBox(height: 30),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: _PhotoLogCard(
                            imageUrl: dashboard.latestPhotoUrl,
                            onTap: onPhotoLog,
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: _CareSettingsCard(onTap: onCareSettings),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        if (state.isRefreshing)
          const Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: LinearProgressIndicator(
              key: Key('home_refreshing'),
              minHeight: 3,
              color: AppColors.primaryContainer,
            ),
          ),
      ],
    );
  }
}

class _QuickAction extends StatelessWidget {
  const _QuickAction({
    required this.label,
    required this.icon,
    required this.onTap,
    super.key,
  });

  final String label;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0xFFE9E9E0),
      borderRadius: BorderRadius.circular(24),
      child: InkWell(
        borderRadius: BorderRadius.circular(24),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, color: AppColors.primary, size: 18),
              const SizedBox(width: 7),
              Text(
                label,
                style: const TextStyle(
                  color: AppColors.primary,
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PlantSelector extends StatelessWidget {
  const _PlantSelector({
    required this.plants,
    required this.selectedPlantId,
    required this.onSelected,
  });

  final List<HomePlant> plants;
  final String? selectedPlantId;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    HomePlant? selected;
    for (final plant in plants) {
      if (plant.id == selectedPlantId) {
        selected = plant;
        break;
      }
    }

    return Container(
      key: const Key('home_plant_selector'),
      constraints: const BoxConstraints(minHeight: 80),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.05),
            blurRadius: 4,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          _CircularPlantImage(url: selected?.thumbnailUrl, size: 56),
          const SizedBox(width: 14),
          Expanded(
            child: DropdownButtonHideUnderline(
              child: DropdownButton<String>(
                key: const Key('home_plant_dropdown'),
                value: selected?.id,
                isExpanded: true,
                icon: const Icon(Icons.keyboard_arrow_down_rounded),
                borderRadius: BorderRadius.circular(18),
                // 항목이 '내 식물' + 별명 두 줄이라 기본 높이(48)에 글꼴을 키운 두 줄이
                // 안 들어간다. null 로 두면 항목이 자기 높이를 갖는다.
                itemHeight: null,
                items: [
                  for (final plant in plants)
                    DropdownMenuItem<String>(
                      value: plant.id,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            '내 식물',
                            style: TextStyle(
                              color: Color(0xFF90968D),
                              fontSize: 12,
                            ),
                          ),
                          Text(
                            plant.name,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppColors.text,
                              fontSize: 18,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
                onChanged: (value) {
                  if (value != null) {
                    onSelected(value);
                  }
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusHero extends StatelessWidget {
  const _StatusHero({required this.dashboard, required this.onPlantDetail});

  final HomeDashboard dashboard;
  final VoidCallback onPlantDetail;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 4 / 3,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(40),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.12),
              blurRadius: 16,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(40),
          child: Stack(
            fit: StackFit.expand,
            children: [
              _PlantBackdrop(url: dashboard.plant.imageUrl),
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [
                      Color(0xFFF8F6F1),
                      Color(0xF2F8F6F1),
                      Color(0x99F8F6F1),
                      Colors.transparent,
                    ],
                    stops: [0, 0.38, 0.66, 1],
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(32, 32, 24, 28),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${dashboard.plant.name}의 상태',
                      style: const TextStyle(
                        color: AppColors.primary,
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      dashboard.mood.headline,
                      key: const Key('home_status_headline'),
                      style: Theme.of(context).textTheme.headlineLarge
                          ?.copyWith(
                            color: AppColors.text,
                            fontSize: 30,
                            letterSpacing: -0.6,
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                    const SizedBox(height: 16),
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 230),
                      child: Text(
                        dashboard.mood.detail,
                        key: const Key('home_status_detail'),
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 14,
                          height: 1.4,
                        ),
                      ),
                    ),
                    const Spacer(),
                    FilledButton.icon(
                      key: const Key('home_plant_detail'),
                      style: FilledButton.styleFrom(
                        minimumSize: const Size(0, 40),
                        padding: const EdgeInsets.symmetric(horizontal: 24),
                        backgroundColor: AppColors.primaryContainer,
                        foregroundColor: Colors.white,
                        shape: const StadiumBorder(),
                        textStyle: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      onPressed: onPlantDetail,
                      icon: const Icon(Icons.arrow_forward_rounded, size: 16),
                      iconAlignment: IconAlignment.end,
                      label: Text('${dashboard.plant.name} 보러가기'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SensorOverview extends StatelessWidget {
  const _SensorOverview({required this.dashboard});

  final HomeDashboard dashboard;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 22),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(40),
        border: Border.all(color: Colors.white),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.05),
            blurRadius: 8,
            offset: const Offset(0, 3),
          ),
        ],
      ),
      child: Column(
        children: [
          Row(
            children: [
              const Icon(
                Icons.eco_outlined,
                color: AppColors.primary,
                size: 22,
              ),
              const SizedBox(width: 8),
              const Text(
                '현재 상태',
                style: TextStyle(
                  color: AppColors.text,
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const Spacer(),
              const SizedBox(
                width: 7,
                height: 7,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Color(0xFF6E9B4B),
                    shape: BoxShape.circle,
                  ),
                ),
              ),
              const SizedBox(width: 5),
              Flexible(
                child: Text(
                  _updateLabel(dashboard.latestMeasuredAt),
                  key: const Key('home_last_updated'),
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFF979C94),
                    fontSize: 10,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final type in HomeSensorType.values) ...[
                Expanded(
                  child: _SensorCard(
                    reading: dashboard.sensor(type),
                    type: type,
                  ),
                ),
                if (type != HomeSensorType.values.last)
                  const SizedBox(width: 8),
              ],
            ],
          ),
        ],
      ),
    );
  }

  String _updateLabel(DateTime? measuredAt) {
    if (measuredAt == null) {
      return '업데이트 정보 없음';
    }
    final difference = DateTime.now().toUtc().difference(measuredAt.toUtc());
    if (difference.isNegative || difference.inMinutes < 1) {
      return '실시간 · 방금 업데이트';
    }
    if (difference.inMinutes < 60) {
      return '실시간 · ${difference.inMinutes}분 전';
    }
    if (difference.inHours < 24) {
      return '${difference.inHours}시간 전 업데이트';
    }
    return '${difference.inDays}일 전 업데이트';
  }
}

class _SensorCard extends StatelessWidget {
  const _SensorCard({required this.reading, required this.type});

  final HomeSensorReading? reading;
  final HomeSensorType type;

  @override
  Widget build(BuildContext context) {
    final spec = _sensorSpec(type);
    final statusLabel = _statusLabel(type, reading?.status);

    return Container(
      key: Key('home_sensor_${type.name}'),
      constraints: const BoxConstraints(minHeight: 136),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFFFAF9F4),
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: const Color(0xFFF0F0EA)),
      ),
      child: Column(
        children: [
          Text(
            spec.label,
            style: const TextStyle(
              color: AppColors.text,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
          const SizedBox(height: 12),
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: spec.background,
              shape: BoxShape.circle,
            ),
            child: Icon(spec.icon, color: spec.foreground, size: 21),
          ),
          const SizedBox(height: 14),
          FittedBox(
            fit: BoxFit.scaleDown,
            child: _sensorValueText(type, reading?.value),
          ),
          const SizedBox(height: 5),
          if (statusLabel != null)
            _SensorStatusLabel(label: statusLabel, status: reading?.status)
          else
            const SizedBox(height: 13),
        ],
      ),
    );
  }

  String? _statusLabel(HomeSensorType type, HomeSensorStatus? status) {
    if (status == null) {
      return '데이터 없음';
    }
    if (type == HomeSensorType.illuminance) {
      return switch (status) {
        HomeSensorStatus.noData => '데이터 없음',
        HomeSensorStatus.stale => '업데이트 지연',
        _ => null,
      };
    }
    return switch (status) {
      HomeSensorStatus.low => '낮음',
      HomeSensorStatus.normal => '적정',
      HomeSensorStatus.high => '높음',
      HomeSensorStatus.notApplicable => null,
      HomeSensorStatus.noData => '데이터 없음',
      HomeSensorStatus.stale => '업데이트 지연',
    };
  }

  Widget _sensorValueText(HomeSensorType type, num? value) {
    const valueStyle = TextStyle(
      color: AppColors.text,
      fontSize: 14,
      fontWeight: FontWeight.w700,
    );
    if (type == HomeSensorType.illuminance && value != null) {
      return Text.rich(
        TextSpan(
          style: valueStyle,
          children: [
            TextSpan(text: _thousands(value.round())),
            const TextSpan(text: ' lux'),
          ],
        ),
      );
    }
    return Text(_sensorValue(type, value), style: valueStyle);
  }

  String _sensorValue(HomeSensorType type, num? value) {
    if (value == null) {
      return '--';
    }
    return switch (type) {
      HomeSensorType.soilMoisture ||
      HomeSensorType.humidity => '${_compactNumber(value)}%',
      HomeSensorType.temperature => '${_compactNumber(value)}°C',
      HomeSensorType.illuminance => '${_thousands(value.round())} lux',
    };
  }

  String _compactNumber(num value) {
    if (value == value.roundToDouble()) {
      return value.round().toString();
    }
    return value.toStringAsFixed(1);
  }

  String _thousands(int value) {
    final source = value.toString();
    final buffer = StringBuffer();
    for (var index = 0; index < source.length; index++) {
      if (index > 0 && (source.length - index) % 3 == 0) {
        buffer.write(',');
      }
      buffer.write(source[index]);
    }
    return buffer.toString();
  }
}

class _SensorStatusLabel extends StatelessWidget {
  const _SensorStatusLabel({required this.label, required this.status});

  final String label;
  final HomeSensorStatus? status;

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      HomeSensorStatus.normal => const Color(0xFF6E9B4B),
      HomeSensorStatus.low || HomeSensorStatus.high => const Color(0xFFE38D31),
      HomeSensorStatus.stale ||
      HomeSensorStatus.noData => const Color(0xFF91978E),
      _ => const Color(0xFF91978E),
    };

    return FittedBox(
      fit: BoxFit.scaleDown,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 5,
            height: 5,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 4),
          Text(label, style: TextStyle(color: color, fontSize: 9)),
        ],
      ),
    );
  }
}

class _PhotoLogCard extends StatelessWidget {
  const _PhotoLogCard({required this.imageUrl, required this.onTap});

  final String? imageUrl;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _HomeFeatureCard(
      key: const Key('home_photo_log'),
      title: '포토 로그',
      onTap: onTap,
      child: AspectRatio(
        aspectRatio: 1,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(18),
          child: imageUrl == null
              ? const _PhotoPlaceholder()
              : _HomeImage(
                  source: imageUrl!,
                  fit: BoxFit.cover,
                  fallback: const _PhotoPlaceholder(),
                ),
        ),
      ),
    );
  }
}

class _CareSettingsCard extends StatelessWidget {
  const _CareSettingsCard({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _HomeFeatureCard(
      key: const Key('home_care_settings'),
      title: '케어 설정',
      onTap: onTap,
      child: Stack(
        alignment: Alignment.bottomRight,
        children: [
          const Positioned(right: -8, bottom: -8, child: _PottedPlantMark()),
          Align(
            alignment: Alignment.topLeft,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '선택된 식물에 맞는 환경을\n'
                  '확인하고 필요에 맞게\n'
                  '조정해보세요 :)',
                  style: TextStyle(
                    color: AppColors.textMuted,
                    fontSize: 12,
                    fontWeight: FontWeight.w400,
                    height: 1.35,
                  ),
                ),
                const Spacer(),
                FilledButton(
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(0, 34),
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    backgroundColor: AppColors.primaryContainer,
                    shape: const StadiumBorder(),
                    textStyle: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  onPressed: onTap,
                  child: const Text('조정하기'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _HomeFeatureCard extends StatelessWidget {
  const _HomeFeatureCard({
    required this.title,
    required this.onTap,
    required this.child,
    super.key,
  });

  final String title;
  final VoidCallback onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(24),
      child: InkWell(
        borderRadius: BorderRadius.circular(24),
        onTap: onTap,
        child: Container(
          height: 224,
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: Colors.white),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.05),
                blurRadius: 8,
                offset: const Offset(0, 3),
              ),
            ],
          ),
          child: Column(
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      title,
                      style: const TextStyle(
                        color: AppColors.text,
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  const Icon(
                    Icons.chevron_right_rounded,
                    color: Color(0xFF92988F),
                    size: 19,
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Expanded(child: child),
            ],
          ),
        ),
      ),
    );
  }
}

class _PottedPlantMark extends StatelessWidget {
  const _PottedPlantMark();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: const Size(80, 88),
      painter: _PottedPlantPainter(
        color: AppColors.primary.withValues(alpha: 0.16),
      ),
    );
  }
}

class _PottedPlantPainter extends CustomPainter {
  const _PottedPlantPainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final fill = Paint()
      ..color = color
      ..style = PaintingStyle.fill;
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 10
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;

    final stemX = size.width * 0.5;
    canvas.drawLine(
      Offset(stemX, size.height * 0.12),
      Offset(stemX, size.height * 0.58),
      stroke,
    );

    final leftLeaf = Path()
      ..moveTo(stemX - 3, size.height * 0.37)
      ..quadraticBezierTo(
        size.width * 0.12,
        size.height * 0.30,
        size.width * 0.17,
        size.height * 0.06,
      )
      ..quadraticBezierTo(
        size.width * 0.45,
        size.height * 0.08,
        stemX - 3,
        size.height * 0.37,
      );
    final rightLeaf = Path()
      ..moveTo(stemX + 3, size.height * 0.48)
      ..quadraticBezierTo(
        size.width * 0.89,
        size.height * 0.40,
        size.width * 0.83,
        size.height * 0.19,
      )
      ..quadraticBezierTo(
        size.width * 0.58,
        size.height * 0.22,
        stemX + 3,
        size.height * 0.48,
      );
    canvas.drawPath(leftLeaf, fill);
    canvas.drawPath(rightLeaf, fill);

    final pot = Path()
      ..moveTo(size.width * 0.20, size.height * 0.58)
      ..lineTo(size.width * 0.80, size.height * 0.58)
      ..lineTo(size.width * 0.70, size.height * 0.94)
      ..quadraticBezierTo(
        size.width * 0.68,
        size.height,
        size.width * 0.60,
        size.height,
      )
      ..lineTo(size.width * 0.40, size.height)
      ..quadraticBezierTo(
        size.width * 0.32,
        size.height,
        size.width * 0.30,
        size.height * 0.94,
      )
      ..close();
    canvas.drawPath(pot, fill);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(
          size.width * 0.14,
          size.height * 0.54,
          size.width * 0.72,
          size.height * 0.12,
        ),
        const Radius.circular(3),
      ),
      fill,
    );
  }

  @override
  bool shouldRepaint(covariant _PottedPlantPainter oldDelegate) {
    return oldDelegate.color != color;
  }
}

class _CircularPlantImage extends StatelessWidget {
  const _CircularPlantImage({required this.url, required this.size});

  final String? url;
  final double size;

  @override
  Widget build(BuildContext context) {
    return ClipOval(
      child: SizedBox(
        width: size,
        height: size,
        child: url == null
            ? const _PlantPlaceholder()
            : _HomeImage(
                source: url!,
                fit: BoxFit.cover,
                fallback: const _PlantPlaceholder(),
              ),
      ),
    );
  }
}

class _PlantBackdrop extends StatelessWidget {
  const _PlantBackdrop({required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    if (url == null) {
      return const _PlantPlaceholder();
    }
    return _HomeImage(
      source: url!,
      fit: BoxFit.cover,
      alignment: Alignment.centerRight,
      fallback: const _PlantPlaceholder(),
    );
  }
}

class _HomeImage extends StatelessWidget {
  const _HomeImage({
    required this.source,
    required this.fit,
    required this.fallback,
    this.alignment = Alignment.center,
  });

  final String source;
  final BoxFit fit;
  final Widget fallback;
  final AlignmentGeometry alignment;

  @override
  Widget build(BuildContext context) {
    if (source.startsWith('assets/')) {
      return Image.asset(
        source,
        fit: fit,
        alignment: alignment,
        errorBuilder: (context, error, stackTrace) => fallback,
      );
    }
    return Image.network(
      source,
      fit: fit,
      alignment: alignment,
      errorBuilder: (context, error, stackTrace) => fallback,
    );
  }
}

class _PlantPlaceholder extends StatelessWidget {
  const _PlantPlaceholder();

  @override
  Widget build(BuildContext context) {
    return const ColoredBox(
      color: Color(0xFFDDE7D6),
      child: Center(
        child: Icon(
          Icons.local_florist_rounded,
          color: AppColors.primaryContainer,
          size: 42,
        ),
      ),
    );
  }
}

class _PhotoPlaceholder extends StatelessWidget {
  const _PhotoPlaceholder();

  @override
  Widget build(BuildContext context) {
    return const ColoredBox(
      color: Color(0xFFF0EFE9),
      child: Center(
        child: Icon(Icons.photo_outlined, color: Color(0xFF92988F), size: 34),
      ),
    );
  }
}

class _InlineMessage extends StatelessWidget {
  const _InlineMessage({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF0EC),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Text(
        message,
        style: const TextStyle(color: AppColors.error, fontSize: 13),
      ),
    );
  }
}

class _LoadingView extends StatelessWidget {
  const _LoadingView();

  @override
  Widget build(BuildContext context) {
    return const Center(
      key: Key('home_loading'),
      child: CircularProgressIndicator(color: AppColors.primary),
    );
  }
}

class _EmptyView extends StatelessWidget {
  const _EmptyView({required this.nickname, required this.onRegisterPlant});

  final String? nickname;
  final VoidCallback onRegisterPlant;

  @override
  Widget build(BuildContext context) {
    return ListView(
      key: const Key('home_empty'),
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
      children: [
        Text(
          nickname == null ? '안녕하세요!' : '안녕하세요, $nickname님!',
          key: const Key('home_greeting'),
          style: Theme.of(context).textTheme.headlineLarge?.copyWith(
            color: const Color(0xFF2D2D2D),
            fontSize: 30,
            height: 1.2,
            letterSpacing: -0.7,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 28),
        Container(
          padding: const EdgeInsets.fromLTRB(24, 38, 24, 28),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(32),
            boxShadow: [
              BoxShadow(
                color: AppColors.primary.withValues(alpha: 0.08),
                blurRadius: 20,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Column(
            children: [
              Container(
                width: 112,
                height: 112,
                decoration: const BoxDecoration(
                  color: Color(0xFFE5F2DF),
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.local_florist_outlined,
                  size: 54,
                  color: AppColors.primary,
                ),
              ),
              const SizedBox(height: 26),
              Text(
                '아직 등록된 식물이 없어요',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                  color: AppColors.text,
                  fontSize: 23,
                ),
              ),
              const SizedBox(height: 12),
              Text(
                // 줄을 손으로 끊는다. 자동 줄바꿈에 맡기면 카드 폭에서 마지막 "요." 만
                // 다음 줄에 남아 문장이 끊어져 보인다.
                '첫 식물을 등록하면 식물의 현재 환경과\n성장 기록을 홈에서 한눈에\n확인할 수 있어요.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                  color: AppColors.textMuted,
                  height: 1.55,
                ),
              ),
              const SizedBox(height: 28),
              FilledButton.icon(
                key: const Key('home_empty_register_plant'),
                onPressed: onRegisterPlant,
                icon: const Icon(Icons.add_circle_outline_rounded),
                label: const Text('내 식물 등록하기'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _FailureView extends StatelessWidget {
  const _FailureView({
    required this.nickname,
    required this.message,
    required this.onRetry,
  });

  final String? nickname;
  final String? message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return _HomeStateView(
      key: const Key('home_error'),
      icon: Icons.cloud_off_outlined,
      title: nickname == null
          ? '홈 정보를 불러오지 못했어요'
          : '$nickname님, 홈 정보를 불러오지 못했어요',
      message: message ?? '잠시 후 다시 시도해 주세요.',
      actionLabel: '다시 시도',
      onAction: onRetry,
    );
  }
}

class _HomeStateView extends StatelessWidget {
  const _HomeStateView({
    required this.icon,
    required this.title,
    required this.message,
    required this.actionLabel,
    required this.onAction,
    super.key,
  });

  final IconData icon;
  final String title;
  final String message;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(28),
      children: [
        const SizedBox(height: 80),
        Icon(icon, size: 72, color: AppColors.primarySoft),
        const SizedBox(height: 24),
        Text(
          title,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.headlineMedium,
        ),
        const SizedBox(height: 12),
        Text(
          message,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodyLarge,
        ),
        const SizedBox(height: 26),
        FilledButton(onPressed: onAction, child: Text(actionLabel)),
      ],
    );
  }
}

class _SensorSpec {
  const _SensorSpec({
    required this.label,
    required this.icon,
    required this.background,
    required this.foreground,
  });

  final String label;
  final IconData icon;
  final Color background;
  final Color foreground;
}

_SensorSpec _sensorSpec(HomeSensorType type) {
  return switch (type) {
    HomeSensorType.soilMoisture => const _SensorSpec(
      label: '수분',
      icon: Icons.water_drop_outlined,
      background: Color(0xFFEAF3FF),
      foreground: Color(0xFF60A5FA),
    ),
    HomeSensorType.illuminance => const _SensorSpec(
      label: '조도',
      icon: Icons.light_mode_outlined,
      background: Color(0xFFFFF3E4),
      foreground: Color(0xFFFF9D45),
    ),
    HomeSensorType.temperature => const _SensorSpec(
      label: '온도',
      icon: Icons.thermostat_rounded,
      background: Color(0xFFFFECEE),
      foreground: Color(0xFFFF6B78),
    ),
    HomeSensorType.humidity => const _SensorSpec(
      label: '습도',
      icon: Icons.cloud_outlined,
      background: Color(0xFFE7FAF7),
      foreground: Color(0xFF31B9AA),
    ),
  };
}
