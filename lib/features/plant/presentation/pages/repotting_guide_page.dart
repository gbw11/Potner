import 'package:flutter/material.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';

/// 분갈이 방법 안내다.
///
/// 서버를 부르지 않는다. 분갈이 절차는 식물 상태나 센서값에 따라 달라지지 않는 일반 지식이고,
/// 종별로 갈라 봐야 흙 배합 정도만 다르다. 그건 참조 데이터에 없으므로 화면에 고정해 둔다.
///
/// 분갈이 시기 알림(`route: /repotting`)을 누르면 이 화면이 열린다. 알림 목록이 아니라
/// 이쪽으로 보내는 이유는, 분갈이는 확인하고 넘기는 알림이 아니라 지금 무엇을 어떻게 해야
/// 하는지가 필요한 안내이기 때문이다.
class RepottingGuidePage extends StatelessWidget {
  const RepottingGuidePage({super.key});

  static const _steps = <_Step>[
    _Step(
      title: '하루 전에 물을 흠뻑 준다',
      body: '흙이 촉촉하면 뿌리가 덜 끊기고 화분에서 잘 빠져나온다. 마른 흙에서 뽑으면 뿌리가 부서진다.',
    ),
    _Step(
      title: '화분을 눕혀서 뽑는다',
      body: '화분 벽을 손으로 두드려 흙을 떼어내고, 줄기 밑동을 잡고 살살 당긴다. 잎이나 줄기 윗부분을 잡아당기면 끊어진다.',
    ),
    _Step(
      title: '뿌리를 정리한다',
      body: '겉흙을 가볍게 털고, 검게 썩었거나 말라 죽은 뿌리는 가위로 자른다. 흰 뿌리는 건강한 것이니 남긴다. 뿌리가 화분 모양대로 뭉쳐 있으면 아래쪽을 살살 풀어 준다.',
    ),
    _Step(
      title: '새 화분에 배수층부터 깐다',
      body: '바닥에 자갈이나 마사토를 2~3cm 깔고 새 흙을 조금 올린다. 배수층이 없으면 물이 고여 뿌리가 썩는다.',
    ),
    _Step(
      title: '이전과 같은 깊이로 심는다',
      body: '식물을 가운데 놓고 빈 곳에 흙을 채운다. 줄기가 흙에 묻히면 그 부분이 썩으니 원래 흙 높이를 넘기지 않는다. 흙을 꾹 누르지 말고 화분을 바닥에 톡톡 쳐서 자리를 잡는다.',
    ),
    _Step(
      title: '물이 흘러나올 때까지 준다',
      body: '배수구로 물이 나올 만큼 충분히 준다. 새 흙과 뿌리 사이의 빈 공간이 이때 메워진다.',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('분갈이 방법'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('repotting_guide_back'),
          onPressed: () => returnFromSharedPage(context, fallbackLocation: '/'),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const _WhenCard(),
                  const SizedBox(height: 16),
                  const _SuppliesCard(),
                  const SizedBox(height: 22),
                  const Text(
                    '순서',
                    style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
                  ),
                  const SizedBox(height: 4),
                  for (var index = 0; index < _steps.length; index++)
                    _StepTile(number: index + 1, step: _steps[index]),
                  const SizedBox(height: 16),
                  const _AfterCard(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 왜 지금인지. 알림을 눌러 들어온 사용자가 가장 먼저 묻는 것이다.
class _WhenCard extends StatelessWidget {
  const _WhenCard();

  @override
  Widget build(BuildContext context) {
    return _Card(
      key: const Key('repotting_when'),
      icon: Icons.event_available_outlined,
      title: '이런 신호가 보이면 할 때다',
      children: const [
        _Bullet('배수구로 뿌리가 삐져나온다'),
        _Bullet('물을 주면 흙에 스미지 않고 바로 빠진다'),
        _Bullet('물을 제때 줘도 성장이 멈췄다'),
        _Bullet('흙 표면에 하얀 가루가 낀다'),
        SizedBox(height: 10),
        Text(
          '봄에서 초여름이 가장 좋다. 한겨울과 한여름은 뿌리가 회복할 힘이 없어 피한다. '
          '꽃이 피어 있는 동안에도 미룬다.',
          style: TextStyle(color: AppColors.textMuted, height: 1.5),
        ),
      ],
    );
  }
}

class _SuppliesCard extends StatelessWidget {
  const _SuppliesCard();

  static const _supplies = <String>[
    '지금보다 지름 2~3cm 큰 화분',
    '새 배양토',
    '자갈 또는 마사토',
    '모종삽',
    '가위',
    '신문지',
  ];

  @override
  Widget build(BuildContext context) {
    return _Card(
      key: const Key('repotting_supplies'),
      icon: Icons.shopping_basket_outlined,
      title: '준비물',
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final item in _supplies)
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 7,
                ),
                decoration: BoxDecoration(
                  color: AppColors.surfaceLow,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(item, style: const TextStyle(fontSize: 13)),
              ),
          ],
        ),
        const SizedBox(height: 12),
        const Text(
          '화분을 한 번에 크게 키우지 않는다. 뿌리가 닿지 않는 흙은 계속 젖어 있어서 '
          '오히려 뿌리가 썩는다.',
          style: TextStyle(color: AppColors.textMuted, height: 1.5),
        ),
      ],
    );
  }
}

/// 분갈이 뒤가 실제로 식물이 죽는 구간이다. 순서만 알려 주고 끝내면 안 된다.
class _AfterCard extends StatelessWidget {
  const _AfterCard();

  @override
  Widget build(BuildContext context) {
    return _Card(
      key: const Key('repotting_after'),
      icon: Icons.spa_outlined,
      title: '끝난 뒤 2주가 더 중요하다',
      children: const [
        _Bullet('직사광선을 피해 밝은 그늘에 둔다'),
        _Bullet('비료는 한 달 뒤부터. 상한 뿌리에 비료는 독이다'),
        _Bullet('잎이 조금 처지는 것은 정상이다. 물을 더 주지 말고 기다린다'),
        _Bullet('가지치기를 함께 하지 않는다. 회복할 일을 두 개 주는 셈이다'),
      ],
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({
    required this.icon,
    required this.title,
    required this.children,
    super.key,
  });

  final IconData icon;
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Container(
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
              Icon(icon, size: 20, color: AppColors.primary),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    fontWeight: FontWeight.w800,
                    fontSize: 15,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          ...children,
        ],
      ),
    );
  }
}

class _StepTile extends StatelessWidget {
  const _StepTile({required this.number, required this.step});

  final int number;
  final _Step step;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            alignment: Alignment.center,
            decoration: const BoxDecoration(
              color: AppColors.primarySoft,
              shape: BoxShape.circle,
            ),
            child: Text(
              '$number',
              style: const TextStyle(
                fontWeight: FontWeight.w800,
                fontSize: 13,
                color: AppColors.primary,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  step.title,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  step.body,
                  style: const TextStyle(
                    color: AppColors.textMuted,
                    height: 1.55,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Bullet extends StatelessWidget {
  const _Bullet(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 6, right: 8),
            child: Icon(Icons.circle, size: 5, color: AppColors.primarySoft),
          ),
          Expanded(
            child: Text(text, style: const TextStyle(height: 1.5)),
          ),
        ],
      ),
    );
  }
}

class _Step {
  const _Step({required this.title, required this.body});

  final String title;
  final String body;
}
