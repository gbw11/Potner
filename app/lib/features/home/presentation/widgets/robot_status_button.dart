import 'package:flutter/material.dart';
import 'package:potner_app/features/device/domain/robot_status.dart';

class RobotStatusButton extends StatefulWidget {
  const RobotStatusButton({required this.status, super.key});

  final RobotStatus status;

  @override
  State<RobotStatusButton> createState() => _RobotStatusButtonState();
}

class _RobotStatusButtonState extends State<RobotStatusButton> {
  final _tooltipKey = GlobalKey<TooltipState>();

  @override
  Widget build(BuildContext context) {
    final visual = _visualFor(widget.status);

    return Tooltip(
      key: _tooltipKey,
      message: visual.label,
      triggerMode: TooltipTriggerMode.manual,
      preferBelow: true,
      verticalOffset: 20,
      showDuration: const Duration(seconds: 2),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: const Color(0xFF2D2D2D),
        borderRadius: BorderRadius.circular(10),
      ),
      textStyle: const TextStyle(
        color: Colors.white,
        fontSize: 13,
        fontWeight: FontWeight.w600,
      ),
      child: IconButton(
        key: const Key('home_robot_status_button'),
        visualDensity: VisualDensity.compact,
        onPressed: () => _tooltipKey.currentState?.ensureTooltipVisible(),
        icon: Icon(
          visual.icon,
          key: Key('home_robot_status_icon_${widget.status.name}'),
          color: visual.color,
          size: 26,
        ),
      ),
    );
  }
}

({IconData icon, Color color, String label}) _visualFor(RobotStatus status) {
  return switch (status) {
    RobotStatus.drinkingWater => (
      icon: Icons.water_drop_outlined,
      color: const Color(0xFF4A90C2),
      label: '물 마시는 중',
    ),
    RobotStatus.takingSunlight => (
      icon: Icons.wb_sunny_outlined,
      color: const Color(0xFFE0A52B),
      label: '햇빛 쬐는 중',
    ),
    RobotStatus.takingWind => (
      icon: Icons.air_rounded,
      color: const Color(0xFF5F9F8C),
      label: '바람 쐬는 중',
    ),
    RobotStatus.resting => (
      icon: Icons.bedtime_outlined,
      color: const Color(0xFF7C709A),
      label: '휴식 중',
    ),
  };
}
